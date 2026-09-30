package com.czur.reference.application.command;

import com.czur.reference.application.port.model.AudioProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 完整创建请求只冻结 OpenAPI 输入，不接入旧创建编排或改变 Create 501。 */
class CreateMeetingRequestSnapshotTest {
    private static final String KEY = "idempotency-key-123456";

    @Test
    void copiesCollectionsAndPreservesOpenApiFields() {
        Set<String> capabilities = new LinkedHashSet<>(
                Set.of("summary.structured", "transcript.realtime"));
        List<String> targets = new ArrayList<>(List.of("en-US", "ja-JP"));
        var snapshot = new CreateMeetingRequestSnapshot(
                "client-session-1", new CreateMeetingRequestSnapshot.ProfileRef("standard", 3),
                client(), "weekly meeting", "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", targets),
                AudioProfile.pcm16kMono(100), capabilities,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(true, false), KEY);
        capabilities.clear();
        targets.clear();

        assertEquals(Set.of("summary.structured", "transcript.realtime"),
                snapshot.requestedCapabilities());
        assertEquals(List.of("en-US", "ja-JP"), snapshot.languages().targets());
        assertEquals("standard", snapshot.profile().id());
        assertEquals(3, snapshot.profile().revision());
        assertEquals("reference-app", snapshot.client().appId());
        assertEquals(16_000, snapshot.audioProfile().sampleRateHz());
        assertTrue(snapshot.artifactPolicy().retainAudio());
        assertFalse(snapshot.artifactPolicy().presentationCapture());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.requestedCapabilities().add("translation.realtime"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.languages().targets().add("fr-FR"));
    }

    @Test
    void rejectsMissingInvalidAndOversizedRequiredValues() {
        assertThrows(IllegalArgumentException.class,
                () -> valid("title", "", KEY, Set.of("transcript.realtime")));
        assertThrows(IllegalArgumentException.class,
                () -> valid("title", "\udfff", KEY, Set.of("transcript.realtime")));
        assertThrows(IllegalArgumentException.class,
                () -> valid("x".repeat(257), "Asia/Shanghai", KEY,
                        Set.of("transcript.realtime")));
        assertThrows(IllegalArgumentException.class,
                () -> valid("title", "Asia/Shanghai", "short", Set.of("transcript.realtime")));
        assertThrows(IllegalArgumentException.class,
                () -> valid("title", "Asia/Shanghai", "k".repeat(129),
                        Set.of("transcript.realtime")));
        assertThrows(IllegalArgumentException.class,
                () -> valid("title", "Asia/Shanghai", KEY, Set.of("bad\ncapability")));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestSnapshot.LanguageSelection(
                        "zh-CN", List.of("en-US", "en-US")));
    }

    @Test
    void enforcesProfileAndAudioProfileContract() {
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestSnapshot.ProfileRef("", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestSnapshot.ProfileRef("standard", 0));
        for (AudioProfile audio : List.of(
                new AudioProfile(AudioProfile.AudioEncoding.UNKNOWN, 16_000, 1, 100),
                new AudioProfile(AudioProfile.AudioEncoding.PCM_S16LE, 48_000, 1, 100),
                new AudioProfile(AudioProfile.AudioEncoding.PCM_S16LE, 16_000, 2, 100),
                new AudioProfile(AudioProfile.AudioEncoding.PCM_S16LE, 16_000, 1, 19),
                new AudioProfile(AudioProfile.AudioEncoding.PCM_S16LE, 16_000, 1, 201))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CreateMeetingRequestSnapshot("session", profile(), client(),
                            null, "Asia/Shanghai", languages(), audio, Set.of(), policy(), KEY));
        }
    }

    @Test
    void toStringDoesNotExposeRequestText() {
        var snapshot = valid("private title", "Asia/Shanghai", KEY,
                Set.of("summary.structured"));
        String diagnostic = snapshot + " " + snapshot.profile() + " "
                + snapshot.client() + " " + snapshot.languages();
        assertFalse(diagnostic.contains("private title"));
        assertFalse(diagnostic.contains("device-opaque"));
        assertFalse(diagnostic.contains("summary.structured"));
        assertFalse(diagnostic.contains(KEY));
        assertFalse(diagnostic.contains("Asia/Shanghai"));
    }

    private static CreateMeetingRequestSnapshot valid(
            String title, String timezone, String key, Set<String> capabilities) {
        return new CreateMeetingRequestSnapshot("client-session-secret", profile(), client(),
                title, timezone, languages(), AudioProfile.pcm16kMono(100), capabilities,
                policy(), key);
    }

    private static CreateMeetingRequestSnapshot.ProfileRef profile() {
        return new CreateMeetingRequestSnapshot.ProfileRef("standard", 1);
    }

    private static CreateMeetingRequestSnapshot.ClientDescriptor client() {
        return new CreateMeetingRequestSnapshot.ClientDescriptor(
                "reference-app", "1.0", "0.1.0", "device-opaque");
    }

    private static CreateMeetingRequestSnapshot.LanguageSelection languages() {
        return new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of("en-US"));
    }

    private static CreateMeetingRequestSnapshot.ArtifactPolicy policy() {
        return new CreateMeetingRequestSnapshot.ArtifactPolicy(false, true);
    }
}
