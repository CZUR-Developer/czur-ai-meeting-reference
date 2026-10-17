package com.czur.reference.application.speaker;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.AudioWindow;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.MeetingSpeakers;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.SpeakerSample;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 只验参考供应商协议／音频转换，不调用真实模型，也不代签声纹识别准确率。 */
class XfyunSpeakerClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T01:02:03Z"), ZoneOffset.UTC);
    @TempDir Path directory;

    @Test
    void unconfiguredProviderDoesNotRequireVendorOrDatabaseValues() {
        assertNull(ReferenceSpeakerIdentityConfig.factory(Map.of(), CLOCK));
        assertNull(ReferenceSpeakerIdentityConfig.factory(Map.of("AI_MEETING_SPEAKER_IDENTITY_PROVIDER", "none"), CLOCK));
        assertFalse(config().toString().contains("synthetic-api"));
    }

    @Test
    void vendorClientSignsAndNormalizesGroupEnrollmentAndMatch() throws Exception {
        List<String> operations = new ArrayList<>();
        var client = new XfyunSpeakerClient(config(), CLOCK, (uri, body, date, timeout) -> {
            try {
                assertEquals("Sat, 17 Oct 2026 01:02:03 GMT", date);
                assertEquals(Duration.ofSeconds(10), timeout);
                String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                String encoded = query.substring("authorization=".length(), query.indexOf("&host="));
                String auth = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                assertTrue(auth.contains("api_key=\"synthetic-api-key\""));
                assertTrue(auth.contains("headers=\"host date request-line\""));
                var request = JSON.readTree(body);
                assertEquals("synthetic-app", request.path("header").path("app_id").asText());
                var parameter = request.path("parameter").path("s1aa729d0");
                assertEquals("reference_group", parameter.path("groupId").asText());
                String operation = parameter.path("func").asText();
                operations.add(operation);
                String response;
                if ("createGroup".equals(operation)) response = "{\"groupId\":\"reference_group\"}";
                else if ("createFeature".equals(operation)) {
                    assertEquals("private_feature", parameter.path("featureId").asText());
                    assertEquals("raw", request.path("payload").path("resource").path("encoding").asText());
                    assertArrayEquals(new byte[]{1, 2, 3, 4}, Base64.getDecoder().decode(
                            request.path("payload").path("resource").path("audio").asText()));
                    response = "{\"featureId\":\"private_feature\"}";
                } else response = "{\"scoreList\":[{\"featureId\":\"private_feature\",\"score\":0.91}]}";
                return new XfyunSpeakerClient.Response(200,
                        "{\"header\":{\"code\":0},\"payload\":{\"" + operation + "Res\":{\"text\":\""
                                + Base64.getEncoder().encodeToString(response.getBytes(StandardCharsets.UTF_8)) + "\"}}}");
            } catch (IOException failure) { throw failure; }
        });
        client.ensureGroup("reference_group");
        client.createFeature("reference_group", "private_feature", new byte[]{1, 2, 3, 4});
        var result = client.identify("reference_group", new byte[]{1, 2, 3, 4});
        assertEquals("private_feature", result.featureId());
        assertEquals(0.91, result.score());
        assertEquals(List.of("createGroup", "createFeature", "searchFea"), operations);
    }

    @Test
    void samplesMergeTheSameSpeakersShortUtterancesAndLimitToFiveSeconds() {
        assertEquals(List.of(new AudioWindow(0, 2000), new AudioWindow(3000, 6000)),
                FfmpegSpeakerSamples.selectWindows(List.of(new AudioWindow(3000, 8000),
                        new AudioWindow(0, 1000), new AudioWindow(1000, 2000))));
    }

    @Test
    void realFfmpegConvertsStoredPcmToOneClosedSpeakerSample() throws Exception {
        try {
            var probe = new ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assumeTrue(probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0, "本机未提供 FFmpeg");
        } catch (IOException absent) { assumeTrue(false, "本机未提供 FFmpeg"); }
        var store = new PersistentFileSystemMockArtifactStore(directory, 1024 * 1024);
        byte[] pcm = new byte[16000 * 2 * 6];
        for (int index = 0; index < pcm.length / 2; index++) {
            short value = (short) (4000 * Math.sin(2 * Math.PI * 440 * index / 16000));
            pcm[index * 2] = (byte) value;
            pcm[index * 2 + 1] = (byte) (value >> 8);
        }
        String checksum = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm));
        var intent = store.createWriteIntent(new ArtifactStore.WriteIntentSpec("art_audio_sample",
                "mtg_sample", "audio.original", "application/octet-stream", null, 1, pcm.length, checksum)).intent();
        store.writeContent(intent, new ByteArrayInputStream(pcm));
        var audio = store.completeWrite(intent, new ArtifactStore.ContentClaim(pcm.length, checksum)).artifact();
        var speaker = new SpeakerSample("speaker-1", List.of(new AudioWindow(0, 1500),
                new AudioWindow(1500, 3000), new AudioWindow(4000, 6000)));
        var meeting = new MeetingSpeakers("test-deployment", "test-subject", "mtg_sample", audio, List.of(speaker));
        try (var samples = new FfmpegSpeakerSamples(meeting, store, config())) {
            assertEquals(16000 * 2 * 5, samples.sample(speaker).length);
        }
    }

    private static ReferenceSpeakerIdentityConfig config() {
        return new ReferenceSpeakerIdentityConfig(URI.create("https://voice.example.invalid/v1/private/s1aa729d0"),
                "s1aa729d0", "synthetic-app", "synthetic-api-key", "synthetic-api-secret",
                "reference_", 0.6, Duration.ofSeconds(10), "ffmpeg", 16000, 1);
    }
}
