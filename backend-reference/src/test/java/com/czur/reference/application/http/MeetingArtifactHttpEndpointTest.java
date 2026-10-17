package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.MeetingArtifactMediaPort;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只覆盖正常媒体 GET 与 JSON 分流，不启动服务，也不依赖数据库。 */
class MeetingArtifactHttpEndpointTest {
    private static final ClientMeetingInputPort UNUSED_INPUT = new ClientMeetingInputPort() {
        @Override public StartResult start(MeetingCommandContext context, String meetingId) {
            throw new AssertionError("下载不能启动会议");
        }
        @Override public AppendResult append(MeetingCommandContext context, String meetingId,
                ClientEvent request) {
            throw new AssertionError("下载不能写入会议事件");
        }
    };

    @Test
    void audioGetOpensSameManifestArtifactAndReadsStream() throws Exception {
        String artifactId = "art_client_audio_test";
        byte[] audio = {0x4f, 0x67, 0x67, 0x53, 0, 1, 2, 3};
        String checksum = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(audio));
        var spec = new ArtifactStore.WriteIntentSpec(artifactId, "mtg_audio_test",
                "audio.original", "audio/ogg", null, 1, audio.length, checksum);
        var descriptor = new ArtifactStore.ArtifactDescriptor(
                new ArtifactStore.WriteIntent("intent-audio-test",
                        new ArtifactStore.StorageBinding("opaque-audio-test"), spec),
                new ArtifactStore.ContentClaim(audio.length, checksum));
        var reference = new MeetingManifest.ArtifactRef(artifactId, "audio.original",
                "audio/ogg", null, 1, audio.length, checksum,
                MeetingManifest.ArtifactStatus.READY, Instant.parse("2026-10-17T00:00:00Z"),
                null, null);
        AtomicBoolean closed = new AtomicBoolean();
        var handle = new ArtifactStore.ReadHandle(descriptor, new ByteArrayInputStream(audio) {
            @Override public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        });
        AtomicReference<MeetingArtifactContentPort.Request> opened = new AtomicReference<>();
        var endpoint = new MeetingArtifactHttpEndpoint(UNUSED_INPUT, request -> {
            opened.set(request);
            return new MeetingArtifactMediaPort.Found(reference, handle);
        }, new HttpErrorMapper());
        var request = new HttpRequest("GET", "/v1/artifacts/" + artifactId,
                Map.of(), Map.of("genTime", "1792195200000"), "");

        assertTrue(endpoint.matchesDownload(request));
        try (var found = assertInstanceOf(MeetingArtifactMediaPort.Found.class,
                endpoint.openDownload(request))) {
            assertEquals(new MeetingArtifactContentPort.Request(artifactId, 1792195200000L),
                    opened.get());
            assertEquals(reference, found.reference());
            assertArrayEquals(audio, found.content().readAllBytes());
            assertFalse(closed.get());
        }
        assertTrue(closed.get());
    }

    @Test
    void jsonArtifactsStayOutsideMediaDownloadRoute() {
        var endpoint = new MeetingArtifactHttpEndpoint(UNUSED_INPUT, request -> {
            throw new AssertionError("JSON 产物必须留给原 JSON 入口");
        }, new HttpErrorMapper());
        for (String artifactId : List.of("art_client_transcript_test",
                "art_client_translation_test", "art_client_summary_test",
                "art_client_presentation_test")) {
            var request = new HttpRequest("GET", "/v1/artifacts/" + artifactId,
                    Map.of(), Map.of("genTime", "1792195200000"), "");
            assertFalse(endpoint.matchesDownload(request), artifactId);
        }
    }
}
