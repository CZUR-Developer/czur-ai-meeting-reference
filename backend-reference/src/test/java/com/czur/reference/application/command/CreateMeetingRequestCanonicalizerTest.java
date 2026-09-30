package com.czur.reference.application.command;

import com.czur.reference.application.port.model.AudioProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 完整 Create 快照摘要的独立契约；不改变旧命令摘要和服务行为。 */
class CreateMeetingRequestCanonicalizerTest {
    @Test
    void snapshotDigestIsStableAcrossCollectionOrderAndKeyChanges() {
        var first = snapshot(Set.of("summary.structured", "transcript.realtime"),
                List.of("ja-JP", "en-US"), "first-idempotency-key");
        var reordered = snapshot(Set.of("transcript.realtime", "summary.structured"),
                List.of("en-US", "ja-JP"), "another-idempotency-key");

        assertEquals(MeetingRequestCanonicalizer.digest(first),
                MeetingRequestCanonicalizer.digest(reordered));
        assertNotEquals(MeetingRequestCanonicalizer.digest(first),
                MeetingRequestCanonicalizer.digest(snapshot(
                        Set.of("summary.structured"), List.of("ja-JP", "en-US"),
                        "first-idempotency-key")));
        assertEquals(64, MeetingRequestCanonicalizer.digest(first).length());
    }

    @Test
    void everyBusinessFieldChangesSnapshotDigest() {
        var base = snapshot(Set.of("transcript.realtime"), List.of("en-US"),
                "stable-idempotency-key");
        assertNotEquals(digest(base), digest(snapshotWith(base, "client-session-2", null,
                null, null, null, null, null, null, null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null,
                new CreateMeetingRequestSnapshot.ProfileRef("other", 1), null, null, null,
                null, null, null, null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null, null,
                new CreateMeetingRequestSnapshot.ClientDescriptor("other", "1", "c", "d"),
                null, null, null, null, null, null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null, null, null,
                "other title", null, null, null, null, null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null, null, null, null,
                "UTC", null, null, null, null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null, null, null, null, null,
                new CreateMeetingRequestSnapshot.LanguageSelection("en-US", List.of("zh-CN")),
                null, null, null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null, null, null, null, null,
                null, AudioProfile.pcm16kMono(80), null, null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null, null, null, null, null,
                null, null, Set.of("summary.structured"), null)));
        assertNotEquals(digest(base), digest(snapshotWith(base, null, null, null, null, null,
                null, null, null,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(true, false))));
    }

    @Test
    void oldDigestsRemainUnchangedAndNullIsRejected() {
        CreateMeetingIntent old = new CreateMeetingIntent(
                "session-会议", "profile:1", 7,
                Set.of("transcript.realtime", "summary.structured"),
                new com.czur.reference.application.port.entitlement.UsageEstimate(
                        com.czur.reference.application.port.entitlement.UsageUnit.MEETING_DURATION_MS,
                        60_000), "old-key");
        assertEquals("d36e82a8bb5459e1ed88104de274061e821042342b744f300ba7f8a98a2b03f6",
                MeetingRequestCanonicalizer.digest(old));
        assertThrows(NullPointerException.class,
                () -> MeetingRequestCanonicalizer.digest((CreateMeetingRequestSnapshot) null));
    }

    private static String digest(CreateMeetingRequestSnapshot snapshot) {
        return MeetingRequestCanonicalizer.digest(snapshot);
    }

    private static CreateMeetingRequestSnapshot snapshot(
            Set<String> capabilities, List<String> targets, String key) {
        return new CreateMeetingRequestSnapshot(
                "client-session", new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "c", "device"),
                "title", "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", targets),
                AudioProfile.pcm16kMono(100), capabilities,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, true), key);
    }

    private static CreateMeetingRequestSnapshot snapshotWith(
            CreateMeetingRequestSnapshot source,
            String clientSessionId,
            CreateMeetingRequestSnapshot.ProfileRef profile,
            CreateMeetingRequestSnapshot.ClientDescriptor client,
            String title,
            String timezone,
            CreateMeetingRequestSnapshot.LanguageSelection languages,
            AudioProfile audio,
            Set<String> capabilities,
            CreateMeetingRequestSnapshot.ArtifactPolicy policy) {
        return new CreateMeetingRequestSnapshot(
                clientSessionId == null ? source.clientSessionId() : clientSessionId,
                profile == null ? source.profile() : profile,
                client == null ? source.client() : client,
                title == null ? source.title() : title,
                timezone == null ? source.timezone() : timezone,
                languages == null ? source.languages() : languages,
                audio == null ? source.audioProfile() : audio,
                capabilities == null ? source.requestedCapabilities() : capabilities,
                policy == null ? source.artifactPolicy() : policy,
                source.idempotencyKey());
    }
}
