package com.czur.reference.application.port;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 T14-01 的纯 Manifest 事实端口，防止基础设施细节提前渗入。 */
class ManifestFactStoreContractTest {
    private static final Instant NOW = Instant.parse("2026-09-21T01:00:00Z");

    @Test
    void portContainsOnlyLatestLookupAndAppendOperations() throws Exception {
        List<String> methods = Arrays.stream(ManifestFactStore.class.getDeclaredMethods())
                .map(Method::getName)
                .sorted()
                .toList();
        assertEquals(List.of("append", "findLatest"), methods);

        Method findLatest = ManifestFactStore.class.getMethod("findLatest", String.class);
        assertEquals(Optional.class, findLatest.getReturnType());
        ParameterizedType returnType = (ParameterizedType) findLatest.getGenericReturnType();
        assertEquals(MeetingManifest.class, returnType.getActualTypeArguments()[0]);
        assertEquals(ManifestFactStore.AppendResult.class,
                ManifestFactStore.class.getMethod(
                        "append", ManifestFactStore.AppendRequest.class).getReturnType());
    }

    @Test
    void noneAcceptsOnlyRevisionOne() {
        ManifestFactStore.ExpectedPreviousRevision.None none =
                new ManifestFactStore.ExpectedPreviousRevision.None();

        ManifestFactStore.AppendRequest request =
                new ManifestFactStore.AppendRequest(manifest(1), none);
        assertEquals(1, request.manifest().manifestRevision());
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestFactStore.AppendRequest(manifest(2), none));
    }

    @Test
    void presentAcceptsOnlyImmediateSuccessorAndPositiveRevision() {
        var previous = new ManifestFactStore.ExpectedPreviousRevision.Present(7);
        ManifestFactStore.AppendRequest request =
                new ManifestFactStore.AppendRequest(manifest(8), previous);

        assertEquals(7, previous.revision());
        assertEquals(8, request.manifest().manifestRevision());
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestFactStore.AppendRequest(manifest(7), previous));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestFactStore.AppendRequest(manifest(9), previous));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestFactStore.ExpectedPreviousRevision.Present(0));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestFactStore.AppendRequest(
                        manifest(1),
                        new ManifestFactStore.ExpectedPreviousRevision.Present(
                                Integer.MAX_VALUE)));
    }

    @Test
    void appendResultFreezesExactlyFourMutuallyExclusiveStatuses() {
        assertEquals(List.of(
                        ManifestFactStore.AppendStatus.APPENDED,
                        ManifestFactStore.AppendStatus.REPLAYED,
                        ManifestFactStore.AppendStatus.CONTENT_CONFLICT,
                        ManifestFactStore.AppendStatus.VERSION_CONFLICT),
                List.of(ManifestFactStore.AppendStatus.values()));
        for (ManifestFactStore.AppendStatus status : ManifestFactStore.AppendStatus.values()) {
            assertEquals(status, new ManifestFactStore.AppendResult(status).status());
        }
        assertThrows(NullPointerException.class,
                () -> new ManifestFactStore.AppendResult(null));
    }

    @Test
    void portHasNoJacksonJdbcHashTransactionOrWorkerTypes() {
        String shape = ManifestFactStore.class.toGenericString()
                + Arrays.toString(ManifestFactStore.class.getDeclaredClasses())
                + Arrays.toString(ManifestFactStore.class.getDeclaredMethods());
        assertFalse(shape.contains("jackson"));
        assertFalse(shape.contains("java.sql"));
        assertFalse(shape.contains("DataSource"));
        assertFalse(shape.contains("hash"));
        assertFalse(shape.contains("Transaction"));
        assertFalse(shape.contains("Worker"));
        assertTrue(ManifestFactStore.class.isInterface());
    }

    private static MeetingManifest manifest(int revision) {
        return new MeetingManifest(
                "0.1",
                "mtg_manifest_fact",
                revision,
                new MeetingManifest.Profile("transcript-only/v1", 1),
                null,
                ManifestStatus.READY,
                NOW,
                NOW,
                0,
                new MeetingManifest.Languages("zh-CN", List.of()),
                List.of(),
                List.of());
    }
}
