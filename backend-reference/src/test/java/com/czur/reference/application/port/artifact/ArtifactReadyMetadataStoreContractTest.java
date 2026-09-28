package com.czur.reference.application.port.artifact;

import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore.PersistResult;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore.PersistStatus;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore.ReadyMetadata;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ArtifactReadyMetadataStore} 的结果组合、时间精度和脱敏门禁。 */
class ArtifactReadyMetadataStoreContractTest {
    @Test
    void readyMetadataRequiresDescriptorAndMicrosecondTimestamp() {
        ArtifactDescriptor descriptor = descriptor();
        Instant recordedAt = Instant.parse("2026-09-28T08:01:02.123456Z");

        ReadyMetadata metadata = new ReadyMetadata(descriptor, recordedAt);

        assertEquals(descriptor, metadata.artifact());
        assertEquals(recordedAt, metadata.recordedAt());
        assertThrows(NullPointerException.class, () -> new ReadyMetadata(null, recordedAt));
        assertThrows(NullPointerException.class, () -> new ReadyMetadata(descriptor, null));
        assertThrows(IllegalArgumentException.class, () -> new ReadyMetadata(
                descriptor, Instant.parse("2026-09-28T08:01:02.123456789Z")));
    }

    @Test
    void persistResultClosesAllInvalidStatusMetadataCombinations() {
        ReadyMetadata metadata = new ReadyMetadata(
                descriptor(), Instant.parse("2026-09-28T08:01:02.123456Z"));

        for (PersistStatus status : List.of(PersistStatus.APPLIED, PersistStatus.REPLAYED)) {
            PersistResult result = new PersistResult(status, metadata);
            assertEquals(status, result.status());
            assertEquals(metadata, result.metadata());
            assertEquals(metadata, result.optionalMetadata().orElseThrow());
        }
        PersistResult conflict = PersistResult.conflict();
        assertEquals(PersistStatus.CONFLICT, conflict.status());
        assertTrue(conflict.optionalMetadata().isEmpty());

        assertThrows(NullPointerException.class, () -> new PersistResult(null, metadata));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistResult(PersistStatus.APPLIED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistResult(PersistStatus.REPLAYED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistResult(PersistStatus.CONFLICT, metadata));
    }

    @Test
    void diagnosticsDoNotExposeArtifactIdentityBindingChecksumOrTime() {
        ReadyMetadata metadata = new ReadyMetadata(
                descriptor(), Instant.parse("2026-09-28T08:01:02.123456Z"));
        String metadataText = metadata.toString();
        String resultText = new PersistResult(PersistStatus.APPLIED, metadata).toString();

        assertEquals("ArtifactReadyMetadataStore.ReadyMetadata[artifact=redacted"
                + ", recordedAt=redacted]", metadataText);
        assertEquals("ArtifactReadyMetadataStore.PersistResult[status=APPLIED"
                + ", metadataPresent=true]", resultText);
        for (String secret : List.of(
                "artifact-private", "meeting-private", "intent-private",
                "binding-private", CHECKSUM, "2026-09-28")) {
            assertFalse(metadataText.contains(secret), secret);
            assertFalse(resultText.contains(secret), secret);
        }
    }

    private static final String CHECKSUM = "sha256:" + "a".repeat(64);

    private static ArtifactDescriptor descriptor() {
        WriteIntentSpec spec = new WriteIntentSpec(
                "artifact-private",
                "meeting-private",
                "summary.structured",
                "application/json",
                "zh-CN",
                1,
                17,
                CHECKSUM);
        WriteIntent intent = new WriteIntent(
                "intent-private", new StorageBinding("binding-private"), spec);
        return new ArtifactDescriptor(intent, new ContentClaim(17, CHECKSUM));
    }
}
