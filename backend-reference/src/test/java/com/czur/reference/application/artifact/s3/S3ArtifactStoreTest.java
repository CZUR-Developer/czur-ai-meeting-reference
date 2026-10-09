package com.czur.reference.application.artifact.s3;

import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S3 状态机的本机定向测试：传输替身保存与真实 S3 相同的对象键和值。 */
class S3ArtifactStoreTest {
    /**
     * 对本机 Moto 等兼容端点运行一次真实 SDK 往返；这能发现把 aws-chunked
     * 传输帧误存为对象正文的问题。仅显式传入测试端点和测试 Bucket 时启用。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_S3_ENDPOINT", matches = ".+")
    void compatibleEndpointPreservesRawArtifactBytes() throws Exception {
        String bucket = System.getenv("REFERENCE_TEST_S3_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("REFERENCE_TEST_S3_BUCKET required");
        }
        byte[] content = "S3 兼容端点原始字节往返".getBytes(StandardCharsets.UTF_8);
        var spec = spec("compat-" + UUID.randomUUID(), content);
        try (var store = new S3ArtifactStore(
                URI.create(System.getenv("REFERENCE_TEST_S3_ENDPOINT")),
                "us-east-1", bucket, "integration-tests/", "test", "test", 1024)) {
            var intent = store.createWriteIntent(spec).intent();
            var staged = store.writeContent(intent, new ByteArrayInputStream(content));
            store.completeWrite(intent, staged.content());
            try (var read = store.openRead(intent.binding())) {
                assertArrayEquals(content, read.content().readAllBytes());
            }
        }
    }

    @Test
    void stagePublishReadAndDeleteSurviveStoreRecreation() throws Exception {
        InMemoryObjects objects = new InMemoryObjects();
        byte[] content = "真实会议的最终字幕".getBytes(StandardCharsets.UTF_8);
        ArtifactStore.WriteIntentSpec spec = spec("artifact-1", content);
        S3ArtifactStore store = new S3ArtifactStore(objects, "artifacts/", 1024);

        var prepared = store.createWriteIntent(spec);
        assertEquals(ArtifactStore.PrepareStatus.APPLIED, prepared.status());
        assertEquals(ArtifactStore.StoreState.PENDING,
                store.inspect(spec).state());
        assertEquals(ArtifactStore.StageStatus.APPLIED,
                store.writeContent(prepared.intent(), new ByteArrayInputStream(content)).status());
        assertEquals(ArtifactStore.StoreState.PENDING,
                store.stat(prepared.intent().binding()).state());
        var claim = new ArtifactStore.ContentClaim(content.length, spec.expectedChecksum());
        assertEquals(ArtifactStore.CompleteStatus.APPLIED,
                store.completeWrite(prepared.intent(), claim).status());
        assertTrue(objects.objects.keySet().stream().allMatch(key -> key.startsWith("artifacts/")),
                "S3 对象键只使用配置的前缀，不应再额外包含 Bucket 名");

        S3ArtifactStore restarted = new S3ArtifactStore(objects, "artifacts/", 1024);
        assertEquals(ArtifactStore.PrepareStatus.REPLAYED,
                restarted.createWriteIntent(spec).status());
        assertEquals(ArtifactStore.StoreState.READY, restarted.inspect(spec).state());
        try (var read = restarted.openRead(prepared.intent().binding())) {
            assertArrayEquals(content, read.content().readAllBytes());
        }
        assertEquals(ArtifactStore.CompleteStatus.REPLAYED,
                restarted.completeWrite(prepared.intent(), claim).status());
        assertEquals(ArtifactStore.DeleteStatus.DELETED,
                restarted.deleteBySpec(spec).status());
        assertEquals(ArtifactStore.StoreState.DELETED, restarted.inspect(spec).state());
        assertEquals(ArtifactStore.DeleteStatus.ALREADY_DELETED,
                restarted.delete(prepared.intent().binding()).status());
        assertEquals(ArtifactStoreException.Code.DELETED,
                assertThrows(ArtifactStoreException.class,
                        () -> restarted.openRead(prepared.intent().binding())).code());
        assertEquals(ArtifactStoreException.Code.DELETED,
                assertThrows(ArtifactStoreException.class,
                        () -> restarted.createWriteIntent(spec)).code());
    }

    @Test
    void externalObjectTransportReusesPublishedContentAndClosesItsClient() throws Exception {
        InMemoryObjects objects = new InMemoryObjects();
        byte[] content = "部署方对象传输复用唯一产物实现".getBytes(StandardCharsets.UTF_8);
        var definition = spec("external-transport", content);
        var store = new S3ArtifactStore(objects, "meeting-artifacts", "local/endpoint/bucket", 1024);
        var intent = store.createWriteIntent(definition).intent();
        var staged = store.writeContent(intent, new ByteArrayInputStream(content));
        store.completeWrite(intent, staged.content());

        try (var reopened = new S3ArtifactStore(objects, "meeting-artifacts/",
                "local/endpoint/bucket", 1024)) {
            assertEquals(intent, reopened.createWriteIntent(definition).intent());
            assertEquals(ArtifactStore.StoreState.READY, reopened.inspect(definition).state());
            try (var read = reopened.openRead(intent.binding())) {
                assertArrayEquals(content, read.content().readAllBytes());
            }
        }
        assertTrue(objects.objects.keySet().stream()
                .allMatch(key -> key.startsWith("meeting-artifacts/")));
        assertEquals(1, objects.closeCalls);
    }

    @Test
    void sameIdDifferentDefinitionPreservesFirstWinner() throws Exception {
        InMemoryObjects objects = new InMemoryObjects();
        S3ArtifactStore store = new S3ArtifactStore(objects, "test/artifacts/", 1024);
        byte[] original = "first".getBytes(StandardCharsets.UTF_8);
        byte[] changed = "second".getBytes(StandardCharsets.UTF_8);
        var first = spec("same-id", original);
        var second = spec("same-id", changed);
        var winner = store.createWriteIntent(first).intent();

        var conflict = store.createWriteIntent(second);
        assertEquals(ArtifactStore.PrepareStatus.CONFLICT, conflict.status());
        assertEquals(winner, conflict.intent());
        assertEquals(ArtifactStoreException.Code.STORAGE_CONFLICT,
                assertThrows(ArtifactStoreException.class,
                        () -> store.inspect(second)).code());
        assertEquals(ArtifactStoreException.Code.STORAGE_CONFLICT,
                assertThrows(ArtifactStoreException.class,
                        () -> store.deleteBySpec(second)).code());
    }

    @Test
    void deleteAbsentSpecLeavesDurableTombstone() throws Exception {
        InMemoryObjects objects = new InMemoryObjects();
        var spec = spec("never-created", "body".getBytes(StandardCharsets.UTF_8));
        S3ArtifactStore store = new S3ArtifactStore(objects, "test/artifacts/", 1024);

        assertEquals(ArtifactStore.DeleteStatus.DELETED, store.deleteBySpec(spec).status());
        assertEquals(ArtifactStore.StoreState.DELETED,
                new S3ArtifactStore(objects, "test/artifacts/", 1024).inspect(spec).state());
    }

    private static ArtifactStore.WriteIntentSpec spec(String id, byte[] body) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body));
        return new ArtifactStore.WriteIntentSpec(id, "meeting-1", "transcript.final",
                "application/json", "zh-CN", 1, body.length, "sha256:" + digest);
    }

    private static final class InMemoryObjects implements S3ArtifactStore.ObjectsApi {
        private final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<>();
        private int closeCalls;

        @Override public byte[] get(String key) { return objects.get(key); }
        @Override public boolean putAbsent(String key, byte[] bytes) {
            return objects.putIfAbsent(key, bytes.clone()) == null;
        }
        @Override public void delete(String key) { objects.remove(key); }
        @Override public void close() { closeCalls++; }
    }
}
