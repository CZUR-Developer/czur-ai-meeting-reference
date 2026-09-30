package com.czur.reference.application.http;

import com.czur.reference.application.command.CreateMeetingCommand;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingCommandService;
import com.czur.reference.application.command.StopMeetingCommand;
import com.czur.reference.application.persistence.InMemoryIdempotencyStore;
import com.czur.reference.application.persistence.InMemoryMeetingRepository;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.MeetingSummary;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 会议 HTTP adapter 的 transport contract 测试。
 *
 * <p>只使用内存命令服务和 fake query/deletion ports，不启动 HTTP server、不
 * 连接 PostgreSQL；测试重点是路由边界、认证上下文传递和稳定错误状态。</p>
 */
class MeetingHttpAdapterContractTest {
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");

    private InMemoryMeetingRepository meetings;
    private MeetingCommandService commands;
    private RecordingQuery query;
    private RecordingDeletion deletion;
    private Decoder decoder;
    private MeetingHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        meetings = new InMemoryMeetingRepository();
        commands = new MeetingCommandService(meetings, new InMemoryIdempotencyStore());
        query = new RecordingQuery();
        deletion = new RecordingDeletion();
        decoder = new Decoder();
        adapter = new MeetingHttpAdapter(
                commands, query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"));
    }

    @Test
    void listRouteBuildsPortRequestAndMapsSuccess() {
        query.listResult = new MeetingQueryPort.ListPage(List.of(summary()), "next-token");

        HttpResponse response = adapter.handle(new HttpRequest(
                "GET", "/v1/meetings", Map.of(),
                Map.of("pageToken", "previous-token", "pageSize", "7"), ""), context());

        assertEquals(200, response.status());
        assertNotNull(query.listRequest);
        assertEquals(context(), query.listRequest.context());
        assertEquals("previous-token", query.listRequest.pageToken());
        assertEquals(7, query.listRequest.pageSize());
        assertEquals("next-token", response.body().contains("next-token") ? "next-token" : "");
    }

    @Test
    void detailRouteBuildsPortRequestAndMapsResult() {
        query.getResult = new MeetingQueryPort.Found(status(MeetingState.READY));

        HttpResponse response = adapter.handle(new HttpRequest(
                "GET", "/v1/meetings/mtg_detail", Map.of(), ""), context());

        assertEquals(200, response.status());
        assertNotNull(query.getRequest);
        assertEquals("mtg_detail", query.getRequest.meetingId());
        assertEquals(context(), query.getRequest.context());
    }

    @Test
    void deleteRouteUsesAuthenticatedContextAndMapsAccepted() {
        decoder.delete = new DeleteRequest(context(), "mtg_delete", "delete-key", "digest");
        deletion.result = new DeleteResult.Accepted(deletionStatus("mtg_delete"));

        HttpResponse response = adapter.handle(new HttpRequest(
                "DELETE", "/v1/meetings/mtg_delete",
                Map.of("idempotency-key", "delete-key-123456"), ""),
                context());

        assertEquals(400, response.status());

        decoder.delete = new DeleteRequest(
                context(), "mtg_delete", "delete-key-123456", "digest");
        response = adapter.handle(new HttpRequest(
                "DELETE", "/v1/meetings/mtg_delete",
                Map.of("idempotency-key", "delete-key-123456"), ""), context());

        assertEquals(202, response.status());
        assertEquals("mtg_delete", deletion.request.meetingId());
        assertEquals(context(), deletion.request.context());
    }

    @Test
    void createRouteUsesDecoderAndCommandServiceWithoutClaimingBearerValidation() {
        decoder.create = new CreateMeetingCommand(
                "mtg_create", "default", "session-create", "profile", 1, NOW,
                "create-key-123456");

        HttpResponse response = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings",
                Map.of("authorization", "Bearer private", "idempotency-key", "create-key-123456"),
                "{}"), null);

        assertEquals(201, response.status());
        assertEquals("mtg_create", response.body().contains("mtg_create") ? "mtg_create" : "");
        assertFalse(response.body().contains("Bearer private"));
    }

    @Test
    void stopRouteChecksPathIdBeforeCallingCommandService() {
        decoder.create = new CreateMeetingCommand(
                "mtg_stop", "default", "session-stop", "profile", 1, NOW, "create-stop");
        commands.create(decoder.create);
        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(
                meetings.find("mtg_stop").orElseThrow());
        aggregate.apply(MeetingEvent.AUTHORIZE, NOW);
        aggregate.apply(MeetingEvent.START, NOW);
        aggregate.apply(MeetingEvent.START, NOW);
        meetings.save(aggregate.snapshot());
        decoder.stop = new StopMeetingCommand(
                "mtg_stop", NOW.plusSeconds(1), "stop-key-1234567");

        HttpResponse response = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567"), "{}"), null);

        assertEquals(200, response.status());
        assertEquals("STOPPING", response.body().contains("STOPPING") ? "STOPPING" : "");

        decoder.stop = new StopMeetingCommand(
                "mtg_other", NOW.plusSeconds(1), "stop-other-123456");
        HttpResponse mismatch = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-other-123456"), "{}"), null);
        assertEquals(400, mismatch.status());
    }

    @Test
    void protectedRoutesRequireContextAndDoNotCallPorts() {
        HttpResponse list = adapter.handle(new HttpRequest(
                "GET", "/v1/meetings", Map.of(), ""), null);
        HttpResponse detail = adapter.handle(new HttpRequest(
                "GET", "/v1/meetings/mtg_missing", Map.of(), ""), null);
        HttpResponse delete = adapter.handle(new HttpRequest(
                "DELETE", "/v1/meetings/mtg_missing", Map.of(), ""), null);

        assertEquals(401, list.status());
        assertEquals(401, detail.status());
        assertEquals(401, delete.status());
        assertNull(query.listRequest);
        assertNull(query.getRequest);
        assertNull(deletion.request);
    }

    @Test
    void unsupportedRoutesAndInvalidQueryReturnStableContractErrors() {
        HttpResponse unsupported = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_missing:cancel", Map.of(), "{}"), context());
        HttpResponse invalidPageSize = adapter.handle(new HttpRequest(
                "GET", "/v1/meetings", Map.of(), Map.of("pageSize", "not-a-number"), ""), context());

        assertEquals(404, unsupported.status());
        assertEquals(400, invalidPageSize.status());
        assertFalse(unsupported.body().contains("mtg_missing"));

        decoder.create = new CreateMeetingCommand(
                "mtg_request_id", "default", "session-request-id", "profile", 1,
                NOW, "request-id-key-123456");
        HttpResponse oversizedRequestId = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings",
                Map.of("idempotency-key", "request-id-key-123456",
                        "x-request-id", "x".repeat(129)), "{}"), null);
        assertEquals(400, oversizedRequestId.status());
    }

    @Test
    void deletePathIdMismatchIsRejectedBeforeDeletionPort() {
        decoder.delete = new DeleteRequest(
                context(), "mtg_other", "delete-key-123456", "digest");

        HttpResponse response = adapter.handle(new HttpRequest(
                "DELETE", "/v1/meetings/mtg_path",
                Map.of("idempotency-key", "delete-key-123456"), ""), context());

        assertEquals(400, response.status());
        assertNull(deletion.request);
    }

    @Test
    void commandAndDeleteRoutesRequireOpenApiIdempotencyHeader() {
        decoder.create = new CreateMeetingCommand(
                "mtg_missing_key", "default", "session", "profile", 1, NOW, "key");
        HttpResponse create = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings", Map.of(), "{}"), null);
        HttpResponse delete = adapter.handle(new HttpRequest(
                "DELETE", "/v1/meetings/mtg_missing_key", Map.of(), ""), context());

        assertEquals(400, create.status());
        assertEquals(400, delete.status());
    }

    private static MeetingCommandContext context() {
        return new MeetingCommandContext(
                "deployment",
                new VerifiedIdentity(
                        new Subject("subject"), Set.of("ROLE_USER"),
                        Set.of("MEETING_READ", "MEETING_DELETE"),
                        NOW.minusSeconds(60), NOW.plusSeconds(600), "credential-reference"));
    }

    private static MeetingStatus status(MeetingState state) {
        return new MeetingStatus(
                "mtg_detail", state, FinalizationFact.accepted("receipt"),
                ManifestStatus.READY, NOW);
    }

    private static MeetingStatus deletionStatus(String meetingId) {
        return new MeetingStatus(
                meetingId, MeetingState.DELETION_PENDING,
                FinalizationFact.pending(InputState.INPUT_CANCELLED),
                ManifestStatus.DELETION_PENDING, NOW);
    }

    private static MeetingSummary summary() {
        return new MeetingSummary(
                "mtg_list", null, MeetingState.READY, InputState.INPUT_ACCEPTED,
                ManifestStatus.READY, NOW.minusSeconds(60), NOW);
    }

    private static final class Decoder implements MeetingHttpAdapter.RequestDecoder {
        private CreateMeetingCommand create;
        private StopMeetingCommand stop;
        private DeleteRequest delete;

        @Override
        public CreateMeetingCommand decodeCreate(HttpRequest request) {
            return create;
        }

        @Override
        public StopMeetingCommand decodeStop(HttpRequest request) {
            return stop;
        }

        @Override
        public DeleteRequest decodeDelete(HttpRequest request, MeetingCommandContext context) {
            return delete;
        }
    }

    private static final class RecordingQuery implements MeetingQueryPort {
        private GetRequest getRequest;
        private ListRequest listRequest;
        private Result getResult = new NotFound();
        private ListResult listResult = new ListPage(List.of(), null);

        @Override
        public Result get(GetRequest request) {
            getRequest = request;
            return getResult;
        }

        @Override
        public ListResult list(ListRequest request) {
            listRequest = request;
            return listResult;
        }
    }

    private static final class RecordingDeletion implements MeetingDeletionPort {
        private DeleteRequest request;
        private DeleteResult result = new DeleteResult.NotFound();

        @Override
        public DeleteResult delete(DeleteRequest request) {
            this.request = request;
            return result;
        }
    }
}
