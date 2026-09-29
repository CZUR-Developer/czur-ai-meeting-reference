package com.czur.reference.application.artifact.filesystem;

import com.czur.reference.application.port.artifact.ArtifactStore.CompleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.DeleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StageStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.StoreState;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.artifact.ArtifactStoreException.Code;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 被真实子 JVM 调用的文件系统 Artifact 恢复探针。 */
public final class PersistentFileSystemMockArtifactStoreProcessProbe {
    private static final byte[] CONTENT =
            "cross-process deterministic artifact".getBytes(StandardCharsets.UTF_8);
    private static final String ARTIFACT_ID = "art_process_restart";
    private static final long LIMIT = 1024 * 1024;

    private PersistentFileSystemMockArtifactStoreProcessProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2 || arguments.length > 3) {
            throw new IllegalArgumentException("expected root, action and optional barrier");
        }
        Path root = Path.of(arguments[0]);
        String action = arguments[1];
        PersistentFileSystemMockArtifactStore store =
                new PersistentFileSystemMockArtifactStore(root, LIMIT);
        WriteIntentSpec spec = spec();

        switch (action) {
            case "complete" -> complete(store, spec);
            case "verify-ready" -> verifyReady(store, spec);
            case "replay" -> replay(store, spec);
            case "crash-half" -> crashHalf(store, spec);
            case "verify-pending" -> verifyPending(store, spec);
            case "delete" -> delete(store, spec);
            case "verify-deleted" -> verifyDeleted(store);
            case "crash-delete-deleting" -> crashDeleteAt(
                    root, spec,
                    PersistentFileSystemMockArtifactStore.TombstonePublishPoint.DELETING);
            case "recover-deleting" -> recoverDeleting(store, spec);
            case "crash-delete-deleted" -> crashDeleteAt(
                    root, spec,
                    PersistentFileSystemMockArtifactStore.TombstonePublishPoint.DELETED);
            case "recover-deleted" -> recoverDeleted(store, spec);
            case "verify-stale-writer-fenced" -> verifyStaleWriterFenced(store, spec);
            case "concurrent-complete" -> {
                if (arguments.length != 3) {
                    throw new IllegalArgumentException("concurrent action requires barrier");
                }
                concurrentComplete(store, spec, Path.of(arguments[2]));
            }
            default -> throw new IllegalArgumentException("unknown action");
        }
        System.out.println("PROBE_OK action=" + action);
    }

    private static void complete(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        WriteIntent intent = store.createWriteIntent(spec).intent();
        store.writeContent(intent, new ByteArrayInputStream(CONTENT));
        store.completeWrite(intent, claim());
        require(store.stat(intent.binding()).state() == StoreState.READY);
    }

    private static void verifyReady(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        var prepared = store.createWriteIntent(spec);
        require(prepared.status() == PrepareStatus.REPLAYED);
        require(store.stat(prepared.intent().binding()).state() == StoreState.READY);
        try (var read = store.openRead(prepared.intent().binding())) {
            require(java.util.Arrays.equals(CONTENT, read.content().readAllBytes()));
        }
    }

    private static void replay(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        var prepared = store.createWriteIntent(spec);
        require(prepared.status() == PrepareStatus.REPLAYED);
        require(store.writeContent(
                prepared.intent(), new ByteArrayInputStream(CONTENT)).status()
                == StageStatus.REPLAYED);
        require(store.completeWrite(prepared.intent(), claim()).status()
                == CompleteStatus.REPLAYED);
    }

    private static void crashHalf(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        WriteIntent intent = store.createWriteIntent(spec).intent();
        store.writeContent(intent, new HaltingInputStream(CONTENT, 5));
        throw new AssertionError("halting stream returned unexpectedly");
    }

    private static void verifyPending(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        WriteIntent intent = store.createWriteIntent(spec).intent();
        require(store.stat(intent.binding()).state() == StoreState.PENDING);
        try {
            store.openRead(intent.binding());
            throw new AssertionError("pending artifact was readable");
        } catch (ArtifactStoreException expected) {
            require(expected.code() == Code.NOT_READY);
        }
    }

    private static void delete(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        WriteIntent intent = store.createWriteIntent(spec).intent();
        DeleteStatus status = store.delete(intent.binding()).status();
        require(status == DeleteStatus.DELETED || status == DeleteStatus.ALREADY_DELETED);
    }

    private static void verifyDeleted(PersistentFileSystemMockArtifactStore store)
            throws Exception {
        StorageBinding binding = derivedBinding();
        require(store.stat(binding).state() == StoreState.DELETED);
        require(store.delete(binding).status() == DeleteStatus.ALREADY_DELETED);
        try {
            store.openRead(binding);
            throw new AssertionError("deleted artifact was readable");
        } catch (ArtifactStoreException expected) {
            require(expected.code() == Code.DELETED);
        }
        try {
            store.createWriteIntent(spec());
            throw new AssertionError("deleted artifact was recreated");
        } catch (ArtifactStoreException expected) {
            require(expected.code() == Code.DELETED);
        }
    }

    /** 在 tombstone rename 成功后真实 halt，确保下一 JVM 不依赖进程内对象恢复。 */
    private static void crashDeleteAt(
            Path root,
            WriteIntentSpec spec,
            PersistentFileSystemMockArtifactStore.TombstonePublishPoint haltPoint)
            throws Exception {
        PersistentFileSystemMockArtifactStore faulting =
                new PersistentFileSystemMockArtifactStore(
                        root, LIMIT, () -> "process-probe", point -> {
                            if (point == haltPoint) {
                                Runtime.getRuntime().halt(47);
                            }
                        });
        faulting.deleteBySpec(spec);
        throw new AssertionError("delete did not halt at " + haltPoint);
    }

    private static void recoverDeleting(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        require(store.inspect(spec).state() == StoreState.DELETING);
        require(store.deleteBySpec(spec).status() == DeleteStatus.DELETED);
        require(store.inspect(spec).state() == StoreState.DELETED);
    }

    private static void recoverDeleted(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        require(store.inspect(spec).state() == StoreState.DELETED);
        require(store.deleteBySpec(spec).status() == DeleteStatus.ALREADY_DELETED);
    }

    /**
     * 用删除前可合法获得的旧 intent 重放三条写路径，证明新进程也不能越过 tombstone。
     */
    private static void verifyStaleWriterFenced(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        WriteIntent stale = new WriteIntent(
                "fsmock_intent_v1_" + bindingHash(), derivedBinding(), spec);
        expectDeleted(() -> store.createWriteIntent(spec));
        expectDeleted(() -> store.writeContent(stale, new ByteArrayInputStream(CONTENT)));
        expectDeleted(() -> store.completeWrite(stale, claim()));
        require(store.inspect(spec).state() == StoreState.DELETED);
    }

    private static void expectDeleted(CheckedOperation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("late writer was not fenced");
        } catch (ArtifactStoreException expected) {
            require(expected.code() == Code.DELETED);
        }
    }

    private static void concurrentComplete(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec,
            Path barrier) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!java.nio.file.Files.exists(barrier)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("barrier timed out");
            }
            Thread.onSpinWait();
        }
        var prepared = store.createWriteIntent(spec);
        var staged = store.writeContent(prepared.intent(), new ByteArrayInputStream(CONTENT));
        var completed = store.completeWrite(prepared.intent(), claim());
        System.out.println("CONCURRENT_STATUS prepare=" + prepared.status()
                + " stage=" + staged.status() + " complete=" + completed.status());
    }

    private static WriteIntentSpec spec() {
        return new WriteIntentSpec(
                ARTIFACT_ID,
                "meeting-process-restart",
                "transcript.final",
                "application/json",
                "zh-CN",
                1,
                CONTENT.length,
                checksum(CONTENT));
    }

    private static ContentClaim claim() {
        return new ContentClaim(CONTENT.length, checksum(CONTENT));
    }

    private static StorageBinding derivedBinding() {
        MessageDigest digest = digest();
        digest.update("artifact-binding-v1\0".getBytes(StandardCharsets.UTF_8));
        digest.update(ARTIFACT_ID.getBytes(StandardCharsets.UTF_8));
        return new StorageBinding("fsmock_v1_" + HexFormat.of().formatHex(digest.digest()));
    }

    private static String bindingHash() {
        return derivedBinding().value().substring("fsmock_v1_".length());
    }

    private static String checksum(byte[] content) {
        return "sha256:" + HexFormat.of().formatHex(digest().digest(content));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("probe assertion failed");
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }

    /** 第一段写入后直接终止子 JVM，模拟没有 finally/close 的真实进程崩溃。 */
    private static final class HaltingInputStream extends InputStream {
        private final byte[] content;
        private final int haltAfter;
        private int position;

        private HaltingInputStream(byte[] content, int haltAfter) {
            this.content = content.clone();
            this.haltAfter = haltAfter;
        }

        @Override
        public int read() throws IOException {
            if (position >= haltAfter) {
                Runtime.getRuntime().halt(47);
            }
            return position >= content.length ? -1 : content[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= haltAfter) {
                Runtime.getRuntime().halt(47);
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, haltAfter - position), content.length - position);
            System.arraycopy(content, position, buffer, offset, count);
            position += count;
            return count;
        }
    }
}
