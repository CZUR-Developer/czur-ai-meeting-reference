package com.czur.reference.application.port;

import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.application.persistence.jdbc.JdbcCompleteMeetingCreateReplayLookup;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v2 重放只读端口的五状态、输入边界与诊断脱敏契约。 */
class CompleteMeetingCreateReplayLookupContractTest {
    @Test
    void statusesAreExplicitAndToStringOmitsIdentityAndToken() {
        var request = new CompleteMeetingCreateReplayLookup.Request(
                "deployment-secret", "subject-secret", "key-secret",
                "a".repeat(64));
        var response = new CreateMeetingResponseFact("mtg_secret", MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("profile-secret", 1),
                Set.of("transcript.realtime"), List.of(), AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://example.test/secret"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "token-secret", Instant.parse("2030-01-01T00:02:00Z")),
                new CreateMeetingResponseFact.Limits(60_000, 64_000, 200, null));
        var results = List.of(new CompleteMeetingCreateReplayLookup.NotFound(),
                new CompleteMeetingCreateReplayLookup.Replayed(response),
                new CompleteMeetingCreateReplayLookup.Conflict(),
                new CompleteMeetingCreateReplayLookup.Inconsistent(),
                new CompleteMeetingCreateReplayLookup.Unavailable());
        assertEquals(List.of(CompleteMeetingCreateReplayLookup.Status.NOT_FOUND,
                CompleteMeetingCreateReplayLookup.Status.REPLAYED,
                CompleteMeetingCreateReplayLookup.Status.CONFLICT,
                CompleteMeetingCreateReplayLookup.Status.INCONSISTENT,
                CompleteMeetingCreateReplayLookup.Status.UNAVAILABLE),
                results.stream().map(CompleteMeetingCreateReplayLookup.Result::status).toList());
        String output = request + "\n" + results;
        for (String secret : List.of("deployment-secret", "subject-secret", "key-secret",
                "profile-secret", "token-secret", "mtg_secret")) {
            assertFalse(output.contains(secret));
        }
    }

    @Test
    void requestRejectsUnownedSubjectAndNonCanonicalDigest() {
        assertThrows(IllegalArgumentException.class, () ->
                new CompleteMeetingCreateReplayLookup.Request(
                        "deployment", "LEGACY_UNOWNED", "key", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
                new CompleteMeetingCreateReplayLookup.Request(
                        "deployment", "subject", "key", "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
                new CompleteMeetingCreateReplayLookup.Request(
                        "deployment", "subject", "key", "not-a-digest"));
    }

    @Test
    void connectionFailureNeverBecomesNotFound() {
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("secret database detail", "08006");
                    }
                    throw new AssertionError("unexpected call: " + method.getName());
                });
        var request = new CompleteMeetingCreateReplayLookup.Request(
                "deployment", "subject", "key", "a".repeat(64));
        assertEquals(CompleteMeetingCreateReplayLookup.Status.UNAVAILABLE,
                new JdbcCompleteMeetingCreateReplayLookup(dataSource).lookup(request).status());
    }
}
