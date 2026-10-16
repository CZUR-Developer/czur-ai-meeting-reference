package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 进程内最终快照的构造兼容与不可变语义，不访问数据库或存储。 */
class JdbcClientMeetingResultSnapshotTest {
    @Test
    void originalSubtitleAndSummaryConstructorsRemainCompatibleWithoutInventingCaptures() {
        var subtitle = new JdbcClientMeetingResultFinalizer.Ready(
                List.of(), List.of(), "und", 0, false, "zh-CN", null);
        assertNull(subtitle.summary());
        assertFalse(subtitle.presentationCaptureRequested());
        assertEquals(List.of(), subtitle.presentationCaptures());
        assertNull(subtitle.audioArtifact());

        var body = new ObjectMapper().createObjectNode().put("title", "原纪要");
        var summary = new JdbcClientMeetingResultFinalizer.Ready(
                List.of(), List.of(), "und", 0, true, "zh-CN", "原标题", body);
        body.put("title", "调用者修改");
        summary.summary().put("title", "消费者修改");
        assertEquals("原纪要", summary.summary().path("title").asText());
        assertFalse(summary.presentationCaptureRequested());
        assertEquals(List.of(), summary.presentationCaptures());
        assertNull(summary.audioArtifact());
    }

    @Test
    void presentationDescriptorsAreCopiedWithoutMutableContentOrAStoreDependency() {
        String checksum = "sha256:" + "a".repeat(64);
        var spec = new WriteIntentSpec("capture-1", "meeting-1", "presentation.capture",
                "application/json", null, 1, 10, checksum);
        var descriptor = new ArtifactDescriptor(
                new WriteIntent("intent-1", new StorageBinding("opaque-binding"), spec),
                new ContentClaim(10, checksum));
        var captures = new ArrayList<>(List.of(descriptor));
        var ready = new JdbcClientMeetingResultFinalizer.Ready(
                List.of(), List.of(), "und", 0, false, "zh-CN", null, null, true, captures);

        captures.clear();
        assertTrue(ready.presentationCaptureRequested());
        assertEquals(List.of(descriptor), ready.presentationCaptures());
        assertThrows(UnsupportedOperationException.class, () -> ready.presentationCaptures().clear());
    }

    @Test
    void audioDescriptorIsOptionalAndKeptOutOfTheLegacyConstructors() {
        String checksum = "sha256:" + "c".repeat(64);
        var spec = new WriteIntentSpec("art_client_audio_2", "mtg_audio",
                "audio.original", "application/octet-stream", null, 1, 4, checksum);
        var descriptor = new ArtifactDescriptor(
                new WriteIntent("intent-audio", new StorageBinding("opaque-audio"), spec),
                new ContentClaim(4, checksum));
        var ready = new JdbcClientMeetingResultFinalizer.Ready(
                List.of(), List.of(), "und", 0, true, "zh-CN", null, null,
                false, List.of(), descriptor);

        assertEquals(descriptor, ready.audioArtifact());
        assertNull(new JdbcClientMeetingResultFinalizer.Ready(
                List.of(), List.of(), "und", 0, true, "zh-CN", null).audioArtifact());
    }
}
