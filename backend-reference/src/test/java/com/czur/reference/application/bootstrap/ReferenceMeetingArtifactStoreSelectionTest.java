package com.czur.reference.application.bootstrap;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.artifact.s3.S3ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
