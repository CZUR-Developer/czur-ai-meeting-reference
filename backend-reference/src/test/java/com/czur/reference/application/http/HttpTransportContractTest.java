package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.error.IdempotencyConflictException;
import com.czur.reference.application.error.MeetingNotFoundException;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 传输切片的契约测试；不启动网络服务，也不连接数据库。
 *
 * <p>这些测试把“公开字段稳定、诊断信息脱敏、端口结果不被异常文本覆盖”
 * 固定下来，具体 HTTP runtime 可以在后续装配中复用同一对象。</p>
 */
class HttpTransportContractTest {
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void requestNormalizesHeadersAndRejectsLineBreaksAndEmbeddedQuery() {
        HttpRequest request = new HttpRequest(
                "GET", "/v1/meetings", Map.of("Authorization", "Bearer secret"),
                Map.of("pageSize", "20"), "");

        assertEquals("Bearer secret", request.header("authorization"));
        assertEquals("Bearer secret", request.header("AUTHORIZATION"));
        assertTrue(request.toString().contains("pathPresent=true"));
        assertFalse(request.toString().contains("Bearer secret"));
        assertFalse(request.toString().contains("pageSize"));

        assertThrows(IllegalArgumentException.class,
                () -> new HttpRequest("GET", "/v1/meetings?secret=yes", Map.of(), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpRequest("GET", "/v1/meetings", Map.of("X-Test", "bad\nvalue"), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpRequest("GET", "/v1/meetings", Map.of("X\nTest", "value"), ""));
    }

    @Test
    void requestEnforcesStrictUtf8BodyAndHeaderByteLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpRequest("POST", "/v1/meetings", Map.of(),
                        "é".repeat(HttpRequest.MAX_BODY_BYTES / 2 + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpRequest("POST", "/v1/meetings", Map.of(), "bad\ud800"));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpRequest("GET", "/v1/meetings",
                        Map.of("X-Large", "x".repeat(HttpRequest.MAX_HEADER_BYTES)), ""));
    }

    @Test
    void responseNormalizesHeadersAndDoesNotLogBody() {
        HttpResponse response = HttpResponse.json(200, "{\"token\":\"private\"}");

        assertEquals("application/json", response.headers().get("content-type"));
        assertEquals("no-store", response.headers().get("cache-control"));
        assertFalse(response.toString().contains("private"));
        assertFalse(response.toString().contains("token"));
    }

    @Test
    void responseEnforcesStrictUtf8BodyAndHeaderByteLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpResponse(200, Map.of(),
                        "é".repeat(HttpResponse.MAX_BODY_BYTES / 2 + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpResponse(200, Map.of(), "bad\udfff"));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpResponse(200,
                        Map.of("X-Large", "x".repeat(HttpResponse.MAX_HEADER_BYTES)), ""));
    }

    @Test
    void errorEnvelopeContainsExactlyOpenApiErrorFields() throws Exception {
        HttpErrorEnvelope envelope = new HttpErrorEnvelope(
                HttpErrorMapper.SPEC_VERSION,
                new HttpErrorEnvelope.Error(
                        HttpErrorEnvelope.Domain.NETWORK,
                        "TEMPORARY_FAILURE",
                        HttpErrorEnvelope.Severity.FATAL,
                        true,
                        1_000,
                        HttpErrorEnvelope.UserAction.RETRY,
                        "ai_meeting.network.temporary_failure",
                        "diag-opaque"));

        JsonNode root = JSON.readTree(envelope.toJson());
        assertEquals("0.1", root.path("specVersion").asText());
        List<String> errorFields = new ArrayList<>();
        root.path("error").fieldNames().forEachRemaining(errorFields::add);
        assertEquals(List.of(
                        "domain", "code", "severity", "retryable", "retryAfterMs",
                        "userAction", "messageKey", "diagnosticId"), errorFields);
        assertEquals("NETWORK", root.path("error").path("domain").asText());
        assertEquals("diag-opaque", root.path("error").path("diagnosticId").asText());
        assertThrows(IllegalArgumentException.class,
                () -> new HttpErrorEnvelope.Error(
                        HttpErrorEnvelope.Domain.NETWORK, "BAD",
                        HttpErrorEnvelope.Severity.FATAL, false, 1_000,
                        HttpErrorEnvelope.UserAction.NONE, "message", "diag"));
    }

    @Test
    void queryAndDeletionResultsMapToStableStatusAndEnvelope() throws Exception {
        HttpErrorMapper mapper = new HttpErrorMapper(() -> "diag-query");
        HttpResponse denied = mapper.query(new MeetingQueryPort.AccessDenied());
        assertEquals(403, denied.status());
        assertEquals("IDENTITY", JSON.readTree(denied.body()).path("error").path("domain").asText());

        HttpResponse unavailable = mapper.query(new MeetingQueryPort.Unavailable(
                MeetingQueryPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE, true));
        JsonNode unavailableError = JSON.readTree(unavailable.body()).path("error");
        assertEquals(503, unavailable.status());
        assertTrue(unavailableError.path("retryable").asBoolean());
        assertEquals(1_000, unavailableError.path("retryAfterMs").asInt());

        HttpResponse accepted = mapper.deletion(new DeleteResult.Accepted(deletionStatus()));
        assertEquals(202, accepted.status());
        assertEquals("mtg_http", JSON.readTree(accepted.body()).path("meetingId").asText());

        assertEquals(404, mapper.deletion(new DeleteResult.NotFound()).status());
        assertEquals(409, mapper.deletion(new DeleteResult.Conflict(
                DeleteResult.ConflictReason.REQUEST_DIGEST_MISMATCH)).status());
    }

    @Test
    void commandExceptionsUseWhitelistAndNeverExposeFailureMessage() throws Exception {
        HttpErrorMapper mapper = new HttpErrorMapper(() -> "opaque-diagnostic");

        HttpResponse conflict = mapper.exception(new IdempotencyConflictException(
                "meeting.create", "private-key"));
        assertEquals(409, conflict.status());
        assertFalse(conflict.body().contains("private-key"));
        assertFalse(conflict.body().contains("meeting.create"));

        assertEquals(404, mapper.exception(new MeetingNotFoundException("mtg_private")).status());
        assertEquals(400, mapper.exception(new IllegalArgumentException("private message")).status());
        assertEquals(500, mapper.exception(new IllegalStateException("provider secret")).status());
        assertFalse(mapper.exception(new IllegalStateException("provider secret"))
                .body().contains("provider secret"));
    }

    @Test
    void successProjectionContainsOnlyTheDocumentedMinimalFields() throws Exception {
        HttpErrorMapper mapper = new HttpErrorMapper(() -> "diag-success");
        MeetingAggregate.MeetingSnapshot snapshot = MeetingAggregate.MeetingSnapshot.legacyUnowned(
                "mtg_http", "default", "session", "profile", 1,
                MeetingState.CREATED, NOW, NOW);
        MeetingCommandResult result = new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED, snapshot);

        JsonNode body = JSON.readTree(mapper.command(result).body());
        assertEquals(201, mapper.command(result).status());
        assertEquals("mtg_http", body.path("meetingId").asText());
        assertEquals("CREATED", body.path("state").asText());
        assertEquals(2, body.size());
    }

    private static MeetingStatus deletionStatus() {
        return new MeetingStatus(
                "mtg_http",
                MeetingState.DELETION_PENDING,
                FinalizationFact.pending(InputState.INPUT_CANCELLED),
                ManifestStatus.DELETION_PENDING,
                NOW);
    }
}
