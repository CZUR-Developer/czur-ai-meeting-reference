package com.czur.reference.application.command;

import com.czur.reference.application.port.model.AudioProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 完整 Create 请求事实的快照、摘要和版本绑定契约。 */
class CreateMeetingRequestFactTest {
    private static final int SCHEMA_VERSION =
            MeetingRequestCanonicalizer.CREATE_SNAPSHOT_SCHEMA_VERSION;
    private static final String IDEMPOTENCY_KEY = "fact-test-idempotency-123456";

    @Test
    void acceptsTheSameSnapshotAndCanonicalDigest() {
        CreateMeetingRequestSnapshot snapshot = snapshot("private title");
        String digest = MeetingRequestCanonicalizer.digest(snapshot);

        CreateMeetingRequestFact fact = new CreateMeetingRequestFact(
                snapshot, digest, SCHEMA_VERSION);

        assertSame(snapshot, fact.requestSnapshot());
        assertEquals(digest, fact.canonicalRequestDigest());
        assertEquals(SCHEMA_VERSION, fact.schemaVersion());
    }

    @Test
    void rejectsChangedSnapshotAndChangedDigest() {
        CreateMeetingRequestSnapshot original = snapshot("private title");
        String originalDigest = MeetingRequestCanonicalizer.digest(original);
        CreateMeetingRequestSnapshot changed = snapshot("changed private title");
        String changedDigest = MeetingRequestCanonicalizer.digest(changed);

        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestFact(changed, originalDigest, SCHEMA_VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestFact(original, changedDigest, SCHEMA_VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestFact(original, originalDigest.toUpperCase(),
                        SCHEMA_VERSION));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        CreateMeetingRequestSnapshot snapshot = snapshot("private title");
        String digest = MeetingRequestCanonicalizer.digest(snapshot);

        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestFact(snapshot, digest, SCHEMA_VERSION - 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingRequestFact(snapshot, digest, SCHEMA_VERSION + 1));
    }

    @Test
    void toStringRedactsSnapshotAndDigestContents() {
        CreateMeetingRequestSnapshot snapshot = snapshot("private title");
        String digest = MeetingRequestCanonicalizer.digest(snapshot);
        String diagnostic = new CreateMeetingRequestFact(snapshot, digest, SCHEMA_VERSION)
                .toString();

        assertFalse(diagnostic.contains("private title"));
        assertFalse(diagnostic.contains("client-session-private"));
        assertFalse(diagnostic.contains("device-private"));
        assertFalse(diagnostic.contains(IDEMPOTENCY_KEY));
        assertFalse(diagnostic.contains(digest));
        assertTrueDiagnosticShape(diagnostic);
    }

    /** 旧 CreateIntent 摘要契约仍独立存在，新的完整事实没有改写它。 */
    @Test
    void oldCreateIntentCanonicalDigestRemainsStable() {
        CreateMeetingIntent old = new CreateMeetingIntent(
                "session-会议", "profile:1", 7,
                Set.of("transcript.realtime", "summary.structured"),
                new com.czur.reference.application.port.entitlement.UsageEstimate(
                        com.czur.reference.application.port.entitlement.UsageUnit.MEETING_DURATION_MS,
                        60_000), "old-key");

        assertEquals("d36e82a8bb5459e1ed88104de274061e821042342b744f300ba7f8a98a2b03f6",
                MeetingRequestCanonicalizer.digest(old));
    }

    private static void assertTrueDiagnosticShape(String value) {
        assertEquals("CreateMeetingRequestFact[schemaVersion=" + SCHEMA_VERSION
                + ", requestSnapshotPresent=true, canonicalRequestDigestPresent=true]", value);
    }

    private static CreateMeetingRequestSnapshot snapshot(String title) {
        return new CreateMeetingRequestSnapshot(
                "client-session-private",
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor(
                        "reference-app", "1.0", "0.1.0", "device-private"),
                title,
                "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection(
                        "zh-CN", List.of("en-US")),
                AudioProfile.pcm16kMono(100),
                Set.of("transcript.realtime"),
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, true),
                IDEMPOTENCY_KEY);
    }
}
