package com.czur.reference.application.manifest;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 删除 revision 的纯 Java 字段保持和拒绝规则门禁。 */
class ManifestDeletionRevisionFactoryTest {
    @Test
    void onlyRevisionAndStatusChangeForEveryCompleteSourceStatus() {
        for (ManifestStatus sourceStatus : List.of(
                ManifestStatus.READY,
                ManifestStatus.READY_WITH_WARNINGS,
                ManifestStatus.FAILED)) {
            MeetingManifest source = manifest(7, sourceStatus);

            MeetingManifest deletion =
                    ManifestDeletionRevisionFactory.deletionPendingAfter(source);

            assertEquals(8, deletion.manifestRevision());
            assertEquals(ManifestStatus.DELETION_PENDING, deletion.status());
            assertEquals(source.specVersion(), deletion.specVersion());
            assertEquals(source.meetingId(), deletion.meetingId());
            assertEquals(source.profile(), deletion.profile());
            assertEquals(source.title(), deletion.title());
            assertEquals(source.startedAt(), deletion.startedAt());
            assertEquals(source.endedAt(), deletion.endedAt());
            assertEquals(source.durationMs(), deletion.durationMs());
            assertEquals(source.languages(), deletion.languages());
            assertEquals(source.artifacts(), deletion.artifacts());
            assertEquals(source.warnings(), deletion.warnings());
            assertEquals(sourceStatus, source.status(), "源 Manifest 不能被原地修改");
            assertNotSame(source, deletion);
        }
    }

    @Test
    void inProgressDeletionAndExhaustedRevisionSourcesAreRejected() {
        for (ManifestStatus status : List.of(
                ManifestStatus.DELETION_PENDING,
                ManifestStatus.DELETED)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ManifestDeletionRevisionFactory.deletionPendingAfter(
                            manifest(2, status)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ManifestDeletionRevisionFactory.deletionPendingAfter(
                        manifest(Integer.MAX_VALUE, ManifestStatus.READY)));
        assertThrows(NullPointerException.class,
                () -> ManifestDeletionRevisionFactory.deletionPendingAfter(null));
    }

    private static MeetingManifest manifest(int revision, ManifestStatus status) {
        Instant startedAt = Instant.parse("2026-09-21T01:00:00Z");
        Instant endedAt = startedAt.plusSeconds(60);
        MeetingManifest.ArtifactRef artifact = new MeetingManifest.ArtifactRef(
                "art_transcript",
                "transcript.final",
                "application/json",
                "zh-CN",
                3,
                42,
                "sha256:" + "ab".repeat(32),
                MeetingManifest.ArtifactStatus.READY,
                endedAt,
                "art_previous",
                null);
        MeetingManifest.Warning warning = new MeetingManifest.Warning(
                "TRANSLATION_UNAVAILABLE",
                "translation",
                "ai_meeting.translation_unavailable",
                "diag_reference");
        return new MeetingManifest(
                "0.1",
                "mtg_deletion_factory",
                revision,
                new MeetingManifest.Profile("profile-reference", 7),
                "字段保持测试",
                status,
                startedAt,
                endedAt,
                60_000,
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(artifact),
                List.of(warning));
    }
}
