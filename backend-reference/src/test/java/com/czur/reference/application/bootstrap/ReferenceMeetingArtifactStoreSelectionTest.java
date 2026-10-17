package com.czur.reference.application.bootstrap;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.artifact.s3.S3ArtifactStore;
import com.czur.reference.application.artifact.AudioArtifactBudget;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 运行时仅在装配层选择文件或 S3，业务层始终使用同一个 ArtifactStore 端口。 */
class ReferenceMeetingArtifactStoreSelectionTest {
    @TempDir Path temporary;

    @Test
    void fileRemainsDefault() throws Exception {
        assertInstanceOf(PersistentFileSystemMockArtifactStore.class,
                ReferenceMeetingProcess.createArtifactStore(
                        Map.of(ReferenceMeetingProcess.ARTIFACT_ROOT,
                                temporary.resolve("artifacts").toString())));
    }

    @Test
    void fileStoreUsesSameDeclaredPcmBudgetAsHttpForOneAndFiveHours() throws Exception {
        for (long durationMs : new long[]{3_600_000L, 18_000_000L}) {
            ArtifactStore store = ReferenceMeetingProcess.createArtifactStore(Map.of(
                    ReferenceMeetingProcess.ARTIFACT_ROOT, temporary.resolve("artifacts-" + durationMs).toString(),
                    MeetingDeploymentConfig.MAX_MEETING_DURATION_MS, Long.toString(durationMs)));
            long budget = AudioArtifactBudget.forMaxDurationMs(durationMs);
            var accepted = new ArtifactStore.WriteIntentSpec("audio-budget", "mtg_budget", "audio.original",
                    "application/octet-stream", null, 1, budget, "sha256:" + "0".repeat(64));
            assertEquals(ArtifactStore.PrepareStatus.APPLIED, store.createWriteIntent(accepted).status());
            var excessive = new ArtifactStore.WriteIntentSpec("audio-too-large", "mtg_budget", "audio.original",
                    "application/octet-stream", null, 1, budget + 1, "sha256:" + "0".repeat(64));
            assertEquals(ArtifactStoreException.Code.CONTENT_TOO_LARGE,
                    assertThrows(ArtifactStoreException.class, () -> store.createWriteIntent(excessive)).code());
        }
    }

    @Test
    void s3UsesExplicitPartnerConfiguration() throws Exception {
        var store = assertInstanceOf(S3ArtifactStore.class,
                ReferenceMeetingProcess.createArtifactStore(Map.of(
                        ReferenceMeetingProcess.ARTIFACT_STORE, "s3",
                        ReferenceMeetingProcess.S3_ENDPOINT, "http://127.0.0.1:19000",
                        ReferenceMeetingProcess.S3_REGION, "us-east-1",
                        ReferenceMeetingProcess.S3_BUCKET, "meeting",
                        ReferenceMeetingProcess.S3_ACCESS_KEY_ID, "test-access",
                        ReferenceMeetingProcess.S3_SECRET_ACCESS_KEY, "test-secret")));
        store.close();
    }

    @Test
    void unknownModeCannotSilentlyUseFileStore() {
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceMeetingProcess.createArtifactStore(Map.of(
                        ReferenceMeetingProcess.ARTIFACT_STORE, "other")));
    }
}
