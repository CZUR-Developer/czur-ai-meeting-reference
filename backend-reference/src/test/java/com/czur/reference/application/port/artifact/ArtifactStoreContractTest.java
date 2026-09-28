package com.czur.reference.application.port.artifact;

import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareResult;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StatResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.StoreState;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException.Code;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Artifact Store 公开值对象、结果和稳定错误的关闭式契约。 */
class ArtifactStoreContractTest {
    private static final String CHECKSUM = "sha256:" + "ab".repeat(32);

    @Test
    void specPreservesExactIdentityAndRequiresCanonicalChecksum() {
        WriteIntentSpec spec = spec("art_exact", "transcript.final", CHECKSUM);

        assertEquals("art_exact", spec.artifactId());
        assertEquals("transcript.final", spec.artifactType());
        assertThrows(IllegalArgumentException.class,
                () -> spec(" art_exact ", " ", CHECKSUM));
        assertThrows(IllegalArgumentException.class,
                () -> spec("art_exact", "transcript.final", "sha256:" + "AB".repeat(32)));
        assertThrows(IllegalArgumentException.class,
                () -> new WriteIntentSpec(
                        "art_exact", "meeting", "transcript.final", "application/json",
                        "zh-CN", 0, 12, CHECKSUM));
    }

    @Test
    void readyDescriptorRequiresExpectedAndActualClaimToMatch() {
        WriteIntentSpec spec = spec("art_descriptor", "transcript.final", CHECKSUM);
        WriteIntent intent = new WriteIntent(
                "intent-sensitive", new StorageBinding("binding-sensitive"), spec);

        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactDescriptor(intent, new ContentClaim(11, CHECKSUM)));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactDescriptor(
                        intent, new ContentClaim(12, "sha256:" + "cd".repeat(32))));
        ArtifactDescriptor descriptor =
                new ArtifactDescriptor(intent, new ContentClaim(12, CHECKSUM));
        assertEquals(spec, descriptor.intent().spec());
    }

    @Test
    void statResultOnlyAllowsDescriptorForReady() {
        ArtifactDescriptor descriptor = descriptor();

        assertThrows(IllegalArgumentException.class,
                () -> new StatResult(StoreState.PENDING, descriptor));
        assertThrows(IllegalArgumentException.class,
                () -> new StatResult(StoreState.READY, null));
        assertThrows(IllegalArgumentException.class,
                () -> StatResult.withoutArtifact(StoreState.READY));
        assertEquals(StoreState.DELETED,
                StatResult.withoutArtifact(StoreState.DELETED).state());
    }

    @Test
    void prepareConflictMayOnlyExposeRedactedWinnerDiagnostics() {
        PrepareResult result = new PrepareResult(PrepareStatus.CONFLICT, descriptor().intent());
        String diagnostic = result + " " + result.intent() + " " + result.intent().spec()
                + " " + result.intent().binding() + " " + descriptor();

        for (String secret : new String[]{
                "art_contract", "meeting-sensitive", "intent-sensitive",
                "binding-sensitive", CHECKSUM, "zh-CN"}) {
            assertFalse(diagnostic.contains(secret));
        }
        assertTrue(diagnostic.contains("CONFLICT"));
    }

    @Test
    void errorCodesHaveFixedRetryabilityAndRedactedDiagnostics() {
        for (Code code : Code.values()) {
            ArtifactStoreException error =
                    new ArtifactStoreException(code, "diagnostic-sensitive");
            assertEquals(code.retryable(), error.retryable());
            assertEquals(code, error.code());
            assertFalse(error.toString().contains("diagnostic-sensitive"));
            assertFalse(error.getMessage().contains("diagnostic-sensitive"));
        }
        assertTrue(Code.STORAGE_UNAVAILABLE.retryable());
        assertTrue(Code.COMMIT_UNKNOWN.retryable());
        assertTrue(Code.NOT_READY.retryable());
        assertFalse(Code.CHECKSUM_MISMATCH.retryable());
        assertFalse(Code.STORAGE_CORRUPT.retryable());
    }

    private static WriteIntentSpec spec(String artifactId, String type, String checksum) {
        return new WriteIntentSpec(
                artifactId,
                "meeting-sensitive",
                type,
                "application/json",
                "zh-CN",
                1,
                12,
                checksum);
    }

    private static ArtifactDescriptor descriptor() {
        WriteIntent intent = new WriteIntent(
                "intent-sensitive",
                new StorageBinding("binding-sensitive"),
                spec("art_contract", "transcript.final", CHECKSUM));
        return new ArtifactDescriptor(intent, new ContentClaim(12, CHECKSUM));
    }
}
