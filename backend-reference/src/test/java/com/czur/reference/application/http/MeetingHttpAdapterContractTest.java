package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.CompleteMeetingResponseAssembler;
import com.czur.reference.application.command.TrustedCreateRuntimeLimits;
import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.command.StopMeetingIntent;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.MeetingEventPage;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.MeetingSummary;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议 HTTP adapter 的 transport contract 测试。
 *
 * <p>只使用 fake query/deletion ports，不启动 HTTP server、不连接 PostgreSQL；
 * create/stop 额外验证关闭式响应不会进入旧命令服务。</p>
 */
class MeetingHttpAdapterContractTest {
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");

    private RecordingQuery query;
    private RecordingDeletion deletion;
    private Decoder decoder;
    private MeetingHttpAdapter adapter;
    private RecordingStop stop;

    @BeforeEach
    void setUp() {
        query = new RecordingQuery();
        deletion = new RecordingDeletion();
        decoder = new Decoder();
        adapter = new MeetingHttpAdapter(
                query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"));
        stop = new RecordingStop();
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
    void eventsRouteReadsCanonicalPageWithGlobalCursorAndRejectsInvalidInputs() {
        RecordingEvents events = new RecordingEvents();
        assertEquals(501, adapter.handle(new HttpRequest("GET",
                "/v1/meetings/mtg_events/events", Map.of(), ""), context()).status());
        adapter = withEvents(events);
        EventLog event = transcriptEvent();
        events.result = new MeetingEventLogPort.Page(
                new MeetingEventPage(List.of(event), 1, true));
        HttpRequest request = new HttpRequest("GET", "/v1/meetings/mtg_events/events",
                Map.of(), Map.of("afterSequence", "0", "limit", "1"), "");
        assertEquals(MeetingHttpAdapter.AuthenticationRoute.READ,
                MeetingHttpAdapter.authenticationRoute(request));
        HttpResponse response = adapter.handle(request, context());
        assertEquals(200, response.status());
        assertEquals(context(), events.request.context());
        assertEquals("mtg_events", events.request.meetingId());
        assertEquals(0, events.request.afterSequence());
        assertEquals(1, events.request.limit());
        assertTrue(response.body().contains("\"eventSequence\":\"1\""));
        assertTrue(response.body().contains("模拟实时字幕"));
        assertTrue(response.body().contains("\"hasMore\":true"));
        assertTrue(response.body().contains("\"lastSequence\":1"));

        events.request = null;
        assertEquals(401, adapter.handle(request, null).status());
        assertNull(events.request);
        for (Map<String, String> invalid : List.of(
                Map.of("afterSequence", "-1"), Map.of("afterSequence", "01"),
                Map.of("afterSequence", "9223372036854775808"),
                Map.of("limit", "0"), Map.of("limit", "1001"),
                Map.of("unexpected", "value"))) {
            assertEquals(400, adapter.handle(new HttpRequest("GET",
                    "/v1/meetings/mtg_events/events", Map.of(), invalid, ""),
                    context()).status());
            assertNull(events.request);
        }
        assertEquals(400, adapter.handle(new HttpRequest("GET",
                "/v1/meetings/mtg_events/events", Map.of(), "body"), context()).status());
        assertNull(events.request);
    }

    @Test
    void eventsRouteMapsOwnershipDeletionCursorAndBadFactsWithoutFalseGone() {
        RecordingEvents events = new RecordingEvents();
        adapter = withEvents(events);
        HttpRequest request = new HttpRequest("GET", "/v1/meetings/mtg_events/events",
                Map.of(), "");
        events.result = new MeetingEventLogPort.AccessDenied();
        assertEquals(403, adapter.handle(request, context()).status());
        events.result = new MeetingEventLogPort.Conflict(
                MeetingEventLogPort.ConflictReason.INVALID_CURSOR);
        HttpResponse ahead = adapter.handle(request, context());
        assertEquals(409, ahead.status());
        assertTrue(ahead.body().contains("INVALID_CURSOR"));
        events.result = new MeetingEventLogPort.Unavailable(
                MeetingEventLogPort.UnavailableReason.STORAGE_FAILURE, false);
        assertEquals(500, adapter.handle(request, context()).status());
        events.result = new MeetingEventLogPort.Unavailable(
                MeetingEventLogPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE, true);
        assertEquals(503, adapter.handle(request, context()).status());
        events.result = new MeetingEventLogPort.NotFound();
        assertEquals(404, adapter.handle(request, context()).status());

        EventLog good = transcriptEvent();
        EventLog bad = new EventLog(good.specVersion(), good.meetingId(), good.streamId(),
                good.eventId(), 2, good.emittedAt(), good.eventType(),
                good.canonicalPayloadJson());
        events.result = new MeetingEventLogPort.Page(
                new MeetingEventPage(List.of(bad), 2, false));
        assertEquals(500, adapter.handle(request, context()).status());
        assertFalse(adapter.handle(request, context()).body().contains("410"));

        adapter = withEvents(ignored -> { throw new IllegalArgumentException("bad stored fact"); });
        assertEquals(500, adapter.handle(request, context()).status());
    }

    private MeetingHttpAdapter withEvents(MeetingEventLogPort events) {
        return new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "event-diagnostic"), stopService(),
                null, null, false, null, null, null, events);
    }

    private static EventLog transcriptEvent() {
        return RealtimeTranscriptEventMapper.event("mtg_events", "evt_events_1", 1, NOW,
                new CanonicalTranscriptEvent("segment-1", 1,
                        CanonicalTranscriptEvent.Operation.UPSERT, 0, 100, "zh-CN",
                        "模拟实时字幕", false, null, 0.5, List.of(), List.of()));
    }

    private static final class RecordingEvents implements MeetingEventLogPort {
        private Request request;
        private Result result = new NotFound();

        @Override public Result read(Request request) {
            this.request = request;
            return result;
        }
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
    void createRouteFailsClosedWithoutCallingLegacyCommandService() {
        HttpResponse response = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings",
                Map.of("authorization", "Bearer private", "idempotency-key", "create-key-123456"),
                "{}"), null);

        assertEquals(501, response.status());
        assertEquals("ROUTE_NOT_IMPLEMENTED", response.body().contains("ROUTE_NOT_IMPLEMENTED")
                ? "ROUTE_NOT_IMPLEMENTED" : "");
        assertFalse(response.body().contains("Bearer private"));
        assertEquals(0, decoder.deleteCalls);
    }

    @Test
    void stopRouteFailsClosedWithoutLegacyCommandService() {
        HttpResponse response = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567"), "{}"), null);

        assertEquals(501, response.status());
        assertEquals("ROUTE_NOT_IMPLEMENTED", response.body().contains("ROUTE_NOT_IMPLEMENTED")
                ? "ROUTE_NOT_IMPLEMENTED" : "");
        assertEquals(0, decoder.deleteCalls);
    }

    @Test
    void configuredStopRouteDecodesIdentityAndMapsPendingOrAccepted() {
        AuthenticatedMeetingStopApplicationService service = stopService();
        adapter = new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"), service);
        decoder.stop = new MeetingHttpAdapter.StopRequest(
                requestCredential(), new StopMeetingIntent("mtg_stop", "stop-key-1234567"));
        stop.result = new OwnedMeetingStopUnitOfWork.Success(
                OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, stopResult(MeetingState.STOPPING));
        query.getResult = new MeetingQueryPort.Found(status(
                MeetingState.STOPPING,
                FinalizationFact.pending(InputState.INPUT_COMPLETE_PENDING)));
        HttpResponse pending = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567",
                        "content-type", "application/json"), "{}"), null);
        assertEquals(202, pending.status());
        assertFalse(pending.body().contains("updatedAt"));
        assertTrue(pending.body().contains("INPUT_COMPLETE_PENDING"));

        decoder.stop = new MeetingHttpAdapter.StopRequest(
                requestCredential(), new StopMeetingIntent("mtg_stop", "stop-key-1234567"));
        query.getResult = new MeetingQueryPort.Found(status(
                MeetingState.FINALIZING, FinalizationFact.accepted("receipt")));
        HttpResponse accepted = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567"), ""), null);
        assertEquals(200, accepted.status());
        assertTrue(accepted.body().contains("receipt"));
        assertFalse(accepted.body().contains("updatedAt"));
        assertEquals("mtg_stop", stop.request.meetingId());
    }

    @Test
    void configuredStopRouteRejectsUnsupportedBodyAndMismatchesBeforeService() {
        adapter = new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"), stopService());
        HttpResponse unsupported = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567",
                        "content-type", "application/json"),
                "{\"lastStreamSequence\":1}"), null);
        assertEquals(400, unsupported.status());
        assertNull(stop.request);

        HttpResponse malformed = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567",
                        "content-type", "application/json"), "{} trailing"), null);
        assertEquals(400, malformed.status());
        assertNull(stop.request);

        IdentityVerificationRequest mismatchedRequest = requestCredential();
        decoder.stop = new MeetingHttpAdapter.StopRequest(
                mismatchedRequest, new StopMeetingIntent("mtg_other", "stop-key-1234567"));
        HttpResponse mismatch = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567",
                        "content-type", "application/json"), "{}"), null);
        assertEquals(400, mismatch.status());
        assertNull(stop.request);
        assertTrue(mismatchedRequest.credential().isDestroyed());
    }

    @Test
    void stopBodyRequiresActualJsonEmptyObjectBeforeDecoderAndPort() {
        adapter = new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"), stopService());
        Map<String, String> json = Map.of("idempotency-key", "stop-key-1234567",
                "content-type", "application/json; charset=UTF-8");
        Map<String, String> plain = Map.of("idempotency-key", "stop-key-1234567",
                "content-type", "text/plain");
        for (HttpRequest invalid : List.of(
                new HttpRequest("POST", "/v1/meetings/mtg_stop:stop", json, "  \t"),
                new HttpRequest("POST", "/v1/meetings/mtg_stop:stop", plain, "{}"),
                new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                        Map.of("idempotency-key", "stop-key-1234567"), "{}"),
                new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                        Map.of("idempotency-key", "stop-key-1234567",
                                "content-type", "application/json; charset=iso-8859-1"), "{}"),
                new HttpRequest("POST", "/v1/meetings/mtg_stop:stop", json,
                        "{\"recordingChecksum\":\"SENSITIVE_BODY\"}"),
                new HttpRequest("POST", "/v1/meetings/mtg_stop:stop", json, "{} trailing"),
                new HttpRequest("POST", "/v1/meetings/mtg_stop:stop", json, "[]"))) {
            HttpResponse response = adapter.handle(invalid, null);
            assertEquals(400, response.status());
            assertFalse(response.body().contains("SENSITIVE_BODY"));
            assertNull(stop.request);
            assertEquals(0, decoder.stopCalls);
        }

        decoder.stop = new MeetingHttpAdapter.StopRequest(
                requestCredential(), new StopMeetingIntent("mtg_stop", "stop-key-1234567"));
        assertEquals(404, adapter.handle(new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                json, " \n{}\t "), null).status());
        assertEquals(1, decoder.stopCalls);
    }

    @Test
    void rejectedStopBodyDoesNotExposeContentInLogs() {
        adapter = new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"), stopService());
        String secret = "SENSITIVE_STOP_BODY_MUST_NOT_LOG";
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                messages.add(formatter.formatMessage(record));
            }
            @Override public void flush() {
            }
            @Override public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try {
            HttpResponse response = adapter.handle(new HttpRequest(
                    "POST", "/v1/meetings/mtg_stop:stop",
                    Map.of("idempotency-key", "stop-key-1234567",
                            "content-type", "application/json"),
                    "{\"unexpected\":\"" + secret + "\"}"), null);
            assertEquals(400, response.status());
            assertFalse(response.body().contains(secret));
        } finally {
            root.removeHandler(capture);
        }
        assertFalse(messages.isEmpty());
        assertFalse(String.join("\n", messages).contains(secret));
        assertEquals(0, decoder.stopCalls);
    }

    @Test
    void configuredStopQueryFailureDoesNotProduceSuccessBody() {
        adapter = new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"), stopService());
        decoder.stop = new MeetingHttpAdapter.StopRequest(
                requestCredential(), new StopMeetingIntent("mtg_stop", "stop-key-1234567"));
        stop.result = new OwnedMeetingStopUnitOfWork.Failure(
                OwnedMeetingStopUnitOfWork.ErrorCode.MEETING_NOT_FOUND);
        HttpResponse response = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings/mtg_stop:stop",
                Map.of("idempotency-key", "stop-key-1234567",
                        "content-type", "application/json"), "{}"), null);
        assertEquals(404, response.status());
        assertFalse(response.body().contains("meetingId"));
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

        HttpResponse oversizedRequestId = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings",
                Map.of("idempotency-key", "request-id-key-123456",
                        "x-request-id", "x".repeat(129)), "{}"), null);
        assertEquals(501, oversizedRequestId.status());
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
    void disabledCommandRouteStaysClosedEvenWhenHeadersAreMissing() {
        HttpResponse create = adapter.handle(new HttpRequest(
                "POST", "/v1/meetings", Map.of(), "{}"), null);
        HttpResponse delete = adapter.handle(new HttpRequest(
                "DELETE", "/v1/meetings/mtg_missing_key", Map.of(), ""), context());

        assertEquals(501, create.status());
        assertEquals(400, delete.status());
    }

    @Test
    void anonymousArtifactRouteUsesExactGenTimeAndMapsVerifiedBytes() {
        List<MeetingArtifactContentPort.Request> calls = new java.util.ArrayList<>();
        MeetingArtifactContentPort content = request -> {
            calls.add(request);
            return new MeetingArtifactContentPort.Found(
                    "{\"text\":\"【模拟】\"}\n".getBytes(StandardCharsets.UTF_8));
        };
        adapter = new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"), stopService(),
                null, null, false, null, null, content);

        HttpResponse response = adapter.handle(new HttpRequest(
                "GET", "/v1/artifacts/art_transcript", Map.of(),
                Map.of("genTime", "-1"), ""), null);
        assertEquals(200, response.status());
        assertEquals("{\"text\":\"【模拟】\"}\n", response.body());
        assertEquals("application/json", response.headers().get("content-type"));
        assertEquals(List.of(new MeetingArtifactContentPort.Request("art_transcript", -1)),
                calls);

        for (String invalid : List.of("", "-2", "01", "9223372036854775808")) {
            assertEquals(400, adapter.handle(new HttpRequest(
                    "GET", "/v1/artifacts/art_transcript", Map.of(),
                    Map.of("genTime", invalid), ""), null).status());
        }
        assertEquals(1, calls.size(), "非法 genTime 不进入内容端口");
        assertEquals(501, new MeetingHttpAdapter(query, deletion, decoder).handle(
                new HttpRequest("GET", "/v1/artifacts/art_transcript", Map.of(),
                        Map.of("genTime", "invalid"), ""), null).status());
    }

    @Test
    void enabledCreateAssemblyPreservesExistingListDeleteAndStopRoutes() {
        adapter = new MeetingHttpAdapter(query, deletion, decoder,
                new HttpErrorMapper(() -> "adapter-diagnostic"), stopService(),
                unusedCreateService(), new CreateMeetingHttpRequestDecoder("audience", "client"),
                true);
        query.listResult = new MeetingQueryPort.ListPage(List.of(summary()), null);
        assertEquals(200, adapter.handle(new HttpRequest(
                "GET", "/v1/meetings", Map.of(), ""), context()).status());

        decoder.delete = new DeleteRequest(context(), "mtg_delete", "delete-key-123456",
                "digest");
        deletion.result = new DeleteResult.NotFound();
        assertEquals(404, adapter.handle(new HttpRequest("DELETE", "/v1/meetings/mtg_delete",
                Map.of("Idempotency-Key", "delete-key-123456"), ""), context()).status());
        assertNotNull(deletion.request);

        decoder.stop = new MeetingHttpAdapter.StopRequest(requestCredential(),
                new StopMeetingIntent("mtg_stop", "stop-key-1234567"));
        stop.result = new OwnedMeetingStopUnitOfWork.Failure(
                OwnedMeetingStopUnitOfWork.ErrorCode.MEETING_NOT_FOUND);
        assertEquals(404, adapter.handle(new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                Map.of("Idempotency-Key", "stop-key-1234567",
                        "Content-Type", "application/json"), "{}"), null).status());
        assertNotNull(stop.request);
    }

    private static AuthenticatedCompleteMeetingApplicationService unusedCreateService() {
        EntitlementProvider unusedEntitlement = new EntitlementProvider() {
            @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(
                    AuthorizeStartRequest request) { throw new AssertionError("no create"); }
            @Override public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                    ReserveUsageRequest request) { throw new AssertionError("no create"); }
            @Override public EntitlementProviderResult<UsageOperationResult> commitUsage(
                    CommitUsageRequest request) { throw new AssertionError("no create"); }
            @Override public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                    CancelReservationRequest request) { throw new AssertionError("no create"); }
        };
        return new AuthenticatedCompleteMeetingApplicationService("deployment",
                request -> { throw new AssertionError("no create"); },
                request -> { throw new AssertionError("no create"); },
                request -> { throw new AssertionError("no create"); },
                unusedEntitlement, () -> "mtg_unused",
                new CompleteMeetingResponseAssembler(new CapabilityPlanner(),
                        request -> { throw new AssertionError("no create"); },
                        new TrustedCreateRuntimeLimits(120_000, 3_200, 128, null)),
                request -> { throw new AssertionError("no create"); },
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                (id, revision) -> { throw new AssertionError("no create"); },
                new CapabilityDescriptor(Set.of("transcript.realtime")), DeploymentLimits.NONE);
    }

    private static MeetingCommandContext context() {
        return new MeetingCommandContext(
                "deployment",
                new VerifiedIdentity(
                        new Subject("subject"), Set.of("ROLE_USER"),
                        Set.of("MEETING_READ", "MEETING_DELETE", "meeting:stop"),
                        NOW.minusSeconds(60), NOW.plusSeconds(600), "credential-reference"));
    }

    private static MeetingStatus status(MeetingState state) {
        return new MeetingStatus(
                "mtg_detail", state, FinalizationFact.accepted("receipt"),
                ManifestStatus.READY, NOW);
    }

    private static MeetingStatus status(MeetingState state, FinalizationFact fact) {
        return new MeetingStatus("mtg_stop", state, fact, ManifestStatus.NOT_STARTED, NOW);
    }

    private static IdentityVerificationRequest requestCredential() {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                SecretCredential.copyOf(new byte[]{1}), "audience", "client");
    }

    private AuthenticatedMeetingStopApplicationService stopService() {
        return new AuthenticatedMeetingStopApplicationService(
                "deployment",
                request -> IdentityProviderResult.success(context().identity()),
                stop,
                query);
    }

    private static MeetingCommandResult stopResult(MeetingState state) {
        return new MeetingCommandResult(MeetingCommandResult.Operation.STOP_ACCEPTED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "mtg_stop", "deployment", "session", "subject", "grant",
                        NOW.plusSeconds(600), "reservation", "profile", 1,
                        state, NOW, NOW.plusSeconds(1)));
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
        private DeleteRequest delete;
        private MeetingHttpAdapter.StopRequest stop;
        private int deleteCalls;
        private int stopCalls;

        @Override
        public DeleteRequest decodeDelete(HttpRequest request, MeetingCommandContext context) {
            deleteCalls++;
            return delete;
        }

        @Override
        public MeetingHttpAdapter.StopRequest decodeStop(
                HttpRequest request, String pathMeetingId, String idempotencyKey) {
            stopCalls++;
            return stop;
        }
    }

    private static final class RecordingStop implements OwnedMeetingStopUnitOfWork {
        private StopRequest request;
        private Result result = new Failure(ErrorCode.MEETING_NOT_FOUND);

        @Override
        public Result stop(StopRequest request) {
            this.request = request;
            return result;
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
