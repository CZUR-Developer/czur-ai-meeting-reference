package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
