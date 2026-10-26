package com.czur.reference.application.port;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V013 Create 事实端口的纯 Java 不可变值契约。 */
class CreateMeetingFactStoreTest {
    private static final String KEY = "create-fact-port-key-1";

    @Test
    void recordRequiresKeyToMatchRequestSnapshotAndRedactsAllFacts() {
        CreateMeetingRequestFact request = request();
        CreateMeetingResponseFact response = response();
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingFactStore.Record(
                "scope", "different-key", request, response, Instant.EPOCH));

        CreateMeetingFactStore.Record record = new CreateMeetingFactStore.Record(
                "opaque-scope", KEY, request, response, Instant.EPOCH);
        String diagnostic = record.toString();
        assertFalse(diagnostic.contains("opaque-scope"));
        assertFalse(diagnostic.contains(KEY));
        assertFalse(diagnostic.contains("mtg_fact_port_1"));
        assertFalse(diagnostic.contains("secret-token"));
        assertEquals("CreateMeetingFactStore.Record[requestPresent=true,responsePresent=true,"
                + "createdAtPresent=true]", diagnostic);
    }

    @Test
    void persistResultOnlyReportsStatus() {
        CreateMeetingFactStore.Record record = new CreateMeetingFactStore.Record(
                "opaque-scope", KEY, request(), response(), Instant.EPOCH);
        CreateMeetingFactStore.PersistResult result = new CreateMeetingFactStore.PersistResult(
                CreateMeetingFactStore.PersistStatus.REPLAYED, record);
        assertEquals(CreateMeetingFactStore.PersistStatus.REPLAYED, result.status());
        assertEquals("CreateMeetingFactStore.PersistResult[status=REPLAYED, record=redacted]",
                result.toString());
    }

    @Test
    void recordNormalizesCreatedAtToPostgresTimestampPrecision() {
        Instant supplied = Instant.parse("2030-01-01T00:00:00.123456789Z");
        CreateMeetingFactStore.Record record = new CreateMeetingFactStore.Record(
                "opaque-scope", KEY, request(), response(), supplied);
        assertEquals(Instant.parse("2030-01-01T00:00:00.123456Z"), record.createdAt());
    }

    private static CreateMeetingRequestFact request() {
        CreateMeetingRequestSnapshot snapshot = new CreateMeetingRequestSnapshot(
                "session-port", new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "c", "device"),
                null, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), Set.of(),
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), KEY);
        return new CreateMeetingRequestFact(snapshot, MeetingRequestCanonicalizer.digest(snapshot), 1);
    }

    private static CreateMeetingResponseFact response() {
        return new CreateMeetingResponseFact(
                "mtg_fact_port_1", MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1), Set.of(), List.of(),
                AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("https://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "secret-token", Instant.EPOCH),
                new CreateMeetingResponseFact.Limits(1, 1, 1, null, 1));
    }
}
