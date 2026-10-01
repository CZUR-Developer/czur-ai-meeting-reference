package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HTTP 运行时装配边界测试。
 *
 * <p>本测试不启动网络服务、不访问数据库，只证明 runtime 不会绕过 adapter，
 * 且关闭后不会把请求继续交给任何业务端口。</p>
 */
class MeetingHttpRuntimeTest {
    @Test
    void liveRuntimeDelegatesCreateBoundaryWithoutOpeningLegacyCommandService() {
        MeetingHttpAdapter adapter = new MeetingHttpAdapter(
                new NoopQuery(), new NoopDeletion(), new RejectingDecoder());
        MeetingHttpRuntime runtime = new MeetingHttpRuntime(adapter);

        HttpResponse response = runtime.handle(new HttpRequest(
                "POST", "/v1/meetings", Map.of(), "{}"), null);

        assertTrue(runtime.isLive());
        assertEquals(501, response.status());
        assertTrue(response.body().contains("ROUTE_NOT_IMPLEMENTED"));
    }

    @Test
    void closedRuntimeFailsClosedAndCloseIsIdempotent() {
        RecordingQuery query = new RecordingQuery();
        MeetingHttpRuntime runtime = new MeetingHttpRuntime(new MeetingHttpAdapter(
                query, new NoopDeletion(), new RejectingDecoder()));

        runtime.close();
        runtime.close();
        HttpResponse response = runtime.handle(new HttpRequest(
                "GET", "/v1/meetings", Map.of(), ""), null);

        assertFalse(runtime.isLive());
        assertEquals(503, response.status());
        assertTrue(response.body().contains("RUNTIME_NOT_READY"));
        assertEquals(0, query.calls);
    }

    @Test
    void newCreateAssemblyRequiresStopAndEnabledCreateDependencies() {
        var query = new NoopQuery();
        var deletion = new NoopDeletion();
        var decoder = new RejectingDecoder();
        var errors = new HttpErrorMapper(() -> "assembly-diag");
        var stop = new AuthenticatedMeetingStopApplicationService(
                "deployment", request -> { throw new AssertionError("no stop identity"); },
                request -> { throw new AssertionError("no stop UOW"); }, query);
        assertThrows(NullPointerException.class, () -> new MeetingHttpRuntime(
                query, deletion, decoder, errors, null, null, null, false));
        assertThrows(NullPointerException.class, () -> new MeetingHttpRuntime(
                query, deletion, decoder, errors, stop, null,
                new CreateMeetingHttpRequestDecoder("audience", "client"), true));
        assertThrows(NullPointerException.class, () -> new MeetingHttpRuntime(
                query, deletion, decoder, errors, stop, null, null, true));
        try (var disabled = new MeetingHttpRuntime(query, deletion, decoder, errors,
                stop, null, null, false)) {
            assertEquals(501, disabled.handle(new HttpRequest(
                    "POST", "/v1/meetings", Map.of(), "not JSON"), null).status());
        }
    }

    @Test
    void manifestRouteIsAnonymousExactAndRejectsInvalidInputBeforePort() {
        var manifest = new RecordingManifest();
        var query = new NoopQuery();
        var stop = new AuthenticatedMeetingStopApplicationService("deployment",
                request -> { throw new AssertionError("identity must not run"); },
                request -> { throw new AssertionError("stop must not run"); }, query);
        var errors = new HttpErrorMapper(() -> "manifest-diag");
        try (var disabled = new MeetingHttpRuntime(query, new NoopDeletion(),
                new RejectingDecoder(), errors, stop, null, null, false, null);
             var enabled = new MeetingHttpRuntime(query, new NoopDeletion(),
                     new RejectingDecoder(), errors, stop, null, null, false, manifest)) {
            String path = "/v1/meetings/mtg_one/manifest";
            assertEquals(501, disabled.handle(new HttpRequest("GET", path, Map.of(),
                    Map.of("genTime", "bad"), ""), null).status());
            for (Map<String, String> invalid : List.of(Map.<String, String>of(),
                    Map.of("genTime", ""), Map.of("genTime", "-2"),
                    Map.of("genTime", "+1"), Map.of("genTime", "01"),
                    Map.of("genTime", "9223372036854775808"),
                    Map.of("genTime", "0", "extra", "1"))) {
                assertEquals(400, enabled.handle(new HttpRequest("GET", path, Map.of(),
                        invalid, ""), null).status());
            }
            assertEquals(400, enabled.handle(new HttpRequest("GET", path, Map.of(),
                    Map.of("genTime", "0"), "{}"), null).status());
            assertEquals(404, enabled.handle(new HttpRequest("POST", path, Map.of(),
                    Map.of("genTime", "0"), ""), null).status());
            assertEquals(404, enabled.handle(new HttpRequest("GET", path + "/extra",
                    Map.of(), Map.of("genTime", "0"), ""), null).status());
            assertEquals(0, manifest.calls);

            var pending = new MeetingStatus("mtg_one", MeetingState.STOPPING,
                    FinalizationFact.pending(InputState.INPUT_OPEN),
                    ManifestStatus.GENERATING, Instant.parse("2026-10-01T00:00:00Z"));
            manifest.result = new MeetingManifestPort.Pending(pending);
            var response = enabled.handle(new HttpRequest("GET", path, Map.of(),
                    Map.of("genTime", "-1"), ""), null);
            assertEquals(202, response.status());
            assertTrue(response.body().contains("\"manifestState\":\"GENERATING\""));
            assertEquals(-1L, manifest.request.genTime());

            var complete = new MeetingManifest("0.1", "mtg_one", 1,
                    new MeetingManifest.Profile("profile", 1), null, ManifestStatus.READY,
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-10-01T00:00:01Z"), 1000,
                    new MeetingManifest.Languages("zh-CN", List.of()), List.of(), List.of());
            manifest.result = new MeetingManifestPort.Found(complete);
            response = enabled.handle(new HttpRequest("GET", path, Map.of(),
                    Map.of("genTime", "123"), ""), null);
            assertEquals(200, response.status());
            assertEquals(MeetingManifestJson.encode(complete), response.body());
            assertEquals(123L, manifest.request.genTime());
        }
    }

    @Test
    void manifestUnavailableUsesStableRetryAndTerminalErrorEnvelopes() throws Exception {
        var manifest = new RecordingManifest();
        var query = new NoopQuery();
        var stop = new AuthenticatedMeetingStopApplicationService("deployment",
                request -> { throw new AssertionError("identity must not run"); },
                request -> { throw new AssertionError("stop must not run"); }, query);
        try (var runtime = new MeetingHttpRuntime(query, new NoopDeletion(),
                new RejectingDecoder(), new HttpErrorMapper(() -> "fixed-diag"), stop,
                null, null, false, manifest)) {
            var request = new HttpRequest("GET", "/v1/meetings/mtg_one/manifest",
                    Map.of(), Map.of("genTime", "0"), "");
            var json = new ObjectMapper();
            manifest.result = new MeetingManifestPort.Unavailable(
                    MeetingManifestPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE, true);
            var transientResponse = runtime.handle(request, null);
            assertEquals(503, transientResponse.status());
            var transientRoot = json.readTree(transientResponse.body());
            assertEquals(2, transientRoot.size());
            assertEquals("0.1", transientRoot.path("specVersion").asText());
            var transientError = transientRoot.path("error");
            assertEquals(8, transientError.size());
            assertEquals("STORAGE", transientError.path("domain").asText());
            assertEquals("MANIFEST_TRANSIENT_STORAGE_FAILURE",
                    transientError.path("code").asText());
            assertEquals("FATAL", transientError.path("severity").asText());
            assertTrue(transientError.path("retryable").asBoolean());
            assertEquals(1000, transientError.path("retryAfterMs").asInt());
            assertEquals("RETRY", transientError.path("userAction").asText());
            assertEquals("ai_meeting.storage.manifest_unavailable",
                    transientError.path("messageKey").asText());
            assertEquals("fixed-diag", transientError.path("diagnosticId").asText());

            manifest.result = new MeetingManifestPort.Unavailable(
                    MeetingManifestPort.UnavailableReason.STORAGE_FAILURE, false);
            var terminalResponse = runtime.handle(request, null);
            assertEquals(500, terminalResponse.status());
            var terminalRoot = json.readTree(terminalResponse.body());
            assertEquals(2, terminalRoot.size());
            assertEquals("0.1", terminalRoot.path("specVersion").asText());
            var terminalError = terminalRoot.path("error");
            assertEquals(8, terminalError.size());
            assertEquals("INTERNAL", terminalError.path("domain").asText());
            assertEquals("MANIFEST_STORAGE_FAILURE", terminalError.path("code").asText());
            assertEquals("FATAL", terminalError.path("severity").asText());
            assertFalse(terminalError.path("retryable").asBoolean());
            assertTrue(terminalError.path("retryAfterMs").isNull());
            assertEquals("CONTACT_ADMIN", terminalError.path("userAction").asText());
            assertEquals("ai_meeting.storage.manifest_unavailable",
                    terminalError.path("messageKey").asText());
            assertEquals("fixed-diag", terminalError.path("diagnosticId").asText());
            assertEquals(2, manifest.calls);
        }
    }

    private static final class RecordingManifest implements MeetingManifestPort {
        private Request request;
        private Result result = new NotFound();
        private int calls;

        @Override public Result get(Request request) {
            calls++;
            this.request = request;
            return result;
        }
    }

    private static final class NoopQuery implements MeetingQueryPort {
        @Override
        public Result get(GetRequest request) {
            throw new AssertionError("query must not be called in this test");
        }

        @Override
        public ListResult list(ListRequest request) {
            throw new AssertionError("list must not be called in this test");
        }
    }

    private static final class RecordingQuery implements MeetingQueryPort {
        private int calls;

        @Override
        public Result get(GetRequest request) {
            calls++;
            return new NotFound();
        }

        @Override
        public ListResult list(ListRequest request) {
            calls++;
            return new ListNotFound();
        }
    }

    private static final class NoopDeletion implements MeetingDeletionPort {
        @Override
        public com.czur.reference.application.port.DeleteResult delete(
                com.czur.reference.application.port.DeleteRequest request) {
            throw new AssertionError("deletion must not be called in this test");
        }
    }

    private static final class RejectingDecoder implements MeetingHttpAdapter.RequestDecoder {
        @Override
        public com.czur.reference.application.port.DeleteRequest decodeDelete(
                HttpRequest request, MeetingCommandContext context) {
            throw new AssertionError("decoder must not run in this test");
        }
    }
}
