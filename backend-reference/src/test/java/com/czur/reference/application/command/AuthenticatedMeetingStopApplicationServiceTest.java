package com.czur.reference.application.command;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.finalization.MeetingInputReconciler;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingInputClosureContextPort;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stop 编排只经 fake ports 验证身份、摘要、事务后查询及凭据生命周期。 */
class AuthenticatedMeetingStopApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");
    private static final StopMeetingIntent INTENT = new StopMeetingIntent(
            "mtg_stop", "stop-key-1234567");

    @Test
    void identityFailureAndMissingPermissionNeverCallStop() {
        RecordingStop stop = new RecordingStop();
        RecordingQuery query = new RecordingQuery();
        SecretCredential credential = credential();
        var failed = service(request -> IdentityProviderResult.failure(
                new IdentityProviderError(IdentityProviderError.Code.INVALID_CREDENTIAL, false)),
                stop, query).stop(request(credential), INTENT);
        assertEquals(IdentityProviderError.Code.INVALID_CREDENTIAL,
                assertInstanceOf(AuthenticatedMeetingStopResult.IdentityFailure.class, failed)
                        .error().code());
        assertTrue(credential.isDestroyed());
        assertNull(stop.request);

        credential = credential();
        var denied = service(request -> IdentityProviderResult.success(identity(Set.of())),
                stop, query).stop(request(credential), INTENT);
        assertEquals(IdentityProviderError.Code.PERMISSION_DENIED,
                assertInstanceOf(AuthenticatedMeetingStopResult.IdentityFailure.class, denied)
                        .error().code());
        assertTrue(credential.isDestroyed());
        assertNull(stop.request);
        assertNull(query.request);
    }

    @Test
    void providerExceptionClosesCredentialWithoutLeakingException() {
        SecretCredential credential = credential();
        var result = service(request -> { throw new IllegalStateException("private token"); },
                new RecordingStop(), new RecordingQuery()).stop(request(credential), INTENT);
        assertEquals(AuthenticatedMeetingStopResult.Stage.IDENTITY,
                assertInstanceOf(AuthenticatedMeetingStopResult.InternalFailure.class, result)
                        .stage());
        assertTrue(credential.isDestroyed());
    }

    @Test
    void stopAndQueryExceptionsBecomeStableInternalFailures() {
        SecretCredential credential = credential();
        var stopFailure = new AuthenticatedMeetingStopApplicationService(
                "deployment", authorized(), request -> {
                    throw new IllegalStateException("private database detail");
                }, new RecordingQuery()).stop(request(credential), INTENT);
        assertEquals(AuthenticatedMeetingStopResult.Stage.STOP,
                assertInstanceOf(AuthenticatedMeetingStopResult.InternalFailure.class, stopFailure)
                        .stage());
        assertTrue(credential.isDestroyed());

        RecordingStop stop = new RecordingStop();
        stop.result = success(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED);
        credential = credential();
        var queryFailure = new AuthenticatedMeetingStopApplicationService(
                "deployment", authorized(), stop, new MeetingQueryPort() {
                    @Override
                    public Result get(GetRequest request) {
                        throw new IllegalStateException("private receipt");
                    }

                    @Override
                    public ListResult list(ListRequest request) {
                        throw new AssertionError("stop must not list meetings");
                    }
                }).stop(request(credential), INTENT);
        assertEquals(AuthenticatedMeetingStopResult.Stage.QUERY,
                assertInstanceOf(AuthenticatedMeetingStopResult.InternalFailure.class, queryFailure)
                        .stage());
        assertTrue(credential.isDestroyed());
    }

    @Test
    void stopFailuresDoNotQueryAndKeepStableCodes() {
        for (OwnedMeetingStopUnitOfWork.ErrorCode code
                : OwnedMeetingStopUnitOfWork.ErrorCode.values()) {
            RecordingStop stop = new RecordingStop();
            RecordingQuery query = new RecordingQuery();
            stop.result = new OwnedMeetingStopUnitOfWork.Failure(code);
            SecretCredential credential = credential();
            var result = service(authorized(), stop, query).stop(request(credential), INTENT);
            assertEquals(code, assertInstanceOf(
                    AuthenticatedMeetingStopResult.StopFailure.class, result).code());
            assertNull(query.request);
            assertTrue(credential.isDestroyed());
        }
    }

    @Test
    void pendingAndAcceptedReadCurrentFactAfterCommit() {
        for (boolean accepted : List.of(false, true)) {
            RecordingStop stop = new RecordingStop();
            RecordingQuery query = new RecordingQuery();
            stop.result = success(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED);
            query.result = new MeetingQueryPort.Found(accepted
                    ? status(MeetingState.READY, FinalizationFact.accepted("receipt-current"))
                    : status(MeetingState.STOPPING,
                            FinalizationFact.pending(InputState.INPUT_COMPLETE_PENDING)));
            SecretCredential credential = credential();
            var result = assertInstanceOf(AuthenticatedMeetingStopResult.Success.class,
                    service(authorized(), stop, query).stop(request(credential), INTENT));
            assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED, result.code());
            assertEquals(accepted ? InputState.INPUT_ACCEPTED : InputState.INPUT_COMPLETE_PENDING,
                    result.meeting().inputState());
            assertEquals(accepted ? "receipt-current" : null,
                    result.meeting().finalizationReceipt());
            assertEquals("deployment", stop.request.context().deploymentScope());
            assertEquals("subject", stop.request.context().identity().subject().subjectScope());
            assertEquals(INTENT.meetingId(), stop.request.meetingId());
            assertEquals(INTENT.idempotencyKey(), stop.request.idempotencyKey());
            assertEquals(MeetingRequestCanonicalizer.digest(INTENT),
                    stop.request.canonicalRequestDigest());
            assertEquals(stop.request.context(), query.request.context());
            assertTrue(credential.isDestroyed());
        }
    }

    @Test
    void committedStopTriggersClosureBeforeQueryButFailureCannotReplaceDatabaseReceipt() {
        AtomicBoolean committed = new AtomicBoolean();
        AtomicBoolean triggered = new AtomicBoolean();
        RecordingQuery query = new RecordingQuery();
        query.result = new MeetingQueryPort.Found(status(MeetingState.STOPPING,
                FinalizationFact.pending(InputState.INPUT_COMPLETE_PENDING)));
        OwnedMeetingStopUnitOfWork stop = request -> {
            committed.set(true);
            return success(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED);
        };
        MeetingInputReconciler reconciler = unavailableReconciler(() -> {
            assertTrue(committed.get(), "closure must run after Stop commit");
            assertNull(query.request, "query must run after closure trigger");
            triggered.set(true);
            // 另一收口调用已持久提交时，响应必须取查询事实，不取本次触发失败。
            query.result = new MeetingQueryPort.Found(status(MeetingState.READY,
                    FinalizationFact.accepted("database-receipt")));
        });
        var result = assertInstanceOf(AuthenticatedMeetingStopResult.Success.class,
                new AuthenticatedMeetingStopApplicationService(
                        "deployment", authorized(), stop, query, reconciler)
                        .stop(request(credential()), INTENT));
        assertTrue(triggered.get());
        assertEquals(InputState.INPUT_ACCEPTED, result.meeting().inputState());
        assertEquals("database-receipt", result.meeting().finalizationReceipt());
    }

    @Test
    void rejectedStopDoesNotTriggerClosure() {
        AtomicBoolean triggered = new AtomicBoolean();
        RecordingStop stop = new RecordingStop();
        RecordingQuery query = new RecordingQuery();
        var result = new AuthenticatedMeetingStopApplicationService(
                "deployment", authorized(), stop, query,
                unavailableReconciler(() -> triggered.set(true)))
                .stop(request(credential()), INTENT);
        assertInstanceOf(AuthenticatedMeetingStopResult.StopFailure.class, result);
        assertTrue(!triggered.get());
        assertNull(query.request);
    }

    @Test
    void unavailableClosureLeavesPendingStopAndNullReceipt() {
        RecordingStop stop = new RecordingStop();
        stop.result = success(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED);
        RecordingQuery query = new RecordingQuery();
        query.result = new MeetingQueryPort.Found(status(MeetingState.STOPPING,
                FinalizationFact.pending(InputState.INPUT_COMPLETE_PENDING)));
        var result = assertInstanceOf(AuthenticatedMeetingStopResult.Success.class,
                new AuthenticatedMeetingStopApplicationService(
                        "deployment", authorized(), stop, query,
                        unavailableReconciler(() -> { }))
                        .stop(request(credential()), INTENT));
        assertEquals(InputState.INPUT_COMPLETE_PENDING, result.meeting().inputState());
        assertNull(result.meeting().finalizationReceipt());
        assertEquals(202, new com.czur.reference.application.http.HttpErrorMapper()
                .stop(result).status());
    }

    /** 只让上下文端口返回固定失败，证明 Stop 对触发结果与数据库查询的边界。 */
    private static MeetingInputReconciler unavailableReconciler(Runnable onTrigger) {
        return new MeetingInputReconciler(meetingId -> {
            onTrigger.run();
            return new MeetingInputClosureContextPort.Unavailable();
        }, meetingId -> {
            throw new AssertionError("winner lookup must not run");
        }, meetingId -> {
            throw new AssertionError("audio lookup must not run");
        }, new FinalizationAdmissionUnitOfWork() {
            @Override public Result admit(AdmissionRequest request) {
                throw new AssertionError("Admission must not run");
            }
            @Override public Result recover(AdmissionRequest request) {
                throw new AssertionError("Admission recovery must not run");
            }
        }, Clock.systemUTC());
    }

    @Test
    void queryFailuresAndInconsistentFactNeverBecomeSuccess() {
        for (MeetingQueryPort.Result queryResult : List.of(
                new MeetingQueryPort.NotFound(),
                new MeetingQueryPort.AccessDenied(),
                new MeetingQueryPort.Conflict(MeetingQueryPort.ConflictReason.INVALID_PAGE_TOKEN),
                new MeetingQueryPort.Unavailable(
                        MeetingQueryPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE, true))) {
            RecordingStop stop = new RecordingStop();
            stop.result = success(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED);
            RecordingQuery query = new RecordingQuery();
            query.result = queryResult;
            var result = service(authorized(), stop, query).stop(request(credential()), INTENT);
            assertInstanceOf(AuthenticatedMeetingStopResult.QueryFailure.class, result);
        }
        RecordingStop stop = new RecordingStop();
        stop.result = success(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED);
        RecordingQuery query = new RecordingQuery();
        query.result = new MeetingQueryPort.Found(status(MeetingState.RUNNING,
                FinalizationFact.pending(InputState.INPUT_OPEN)));
        var result = service(authorized(), stop, query).stop(request(credential()), INTENT);
        assertEquals(AuthenticatedMeetingStopResult.Stage.QUERY_INCONSISTENT,
                assertInstanceOf(AuthenticatedMeetingStopResult.InternalFailure.class, result)
                        .stage());
    }

    private static AuthenticatedMeetingStopApplicationService service(
            IdentityProvider identity, RecordingStop stop, RecordingQuery query) {
        return new AuthenticatedMeetingStopApplicationService(
                "deployment", identity, stop, query);
    }

    private static IdentityProvider authorized() {
        return request -> IdentityProviderResult.success(identity(Set.of(MeetingPermissions.STOP)));
    }

    private static VerifiedIdentity identity(Set<String> permissions) {
        return new VerifiedIdentity(new Subject("subject"), Set.of(), permissions,
                NOW.minusSeconds(60), NOW.plusSeconds(600), "credential-ref");
    }

    private static SecretCredential credential() {
        return SecretCredential.copyOf(new byte[]{1, 2, 3});
    }

    private static IdentityVerificationRequest request(SecretCredential credential) {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                credential, "audience", "client");
    }

    private static OwnedMeetingStopUnitOfWork.Success success(
            OwnedMeetingStopUnitOfWork.SuccessCode code) {
        return new OwnedMeetingStopUnitOfWork.Success(code, new MeetingCommandResult(
                MeetingCommandResult.Operation.STOP_ACCEPTED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "mtg_stop", "deployment", "session", "subject",
                        "grant", NOW.plusSeconds(600), "reservation", "profile", 1,
                        MeetingState.STOPPING, NOW, NOW.plusSeconds(1))));
    }

    private static MeetingStatus status(MeetingState state, FinalizationFact fact) {
        return new MeetingStatus("mtg_stop", state, fact, ManifestStatus.NOT_STARTED, NOW);
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
        private GetRequest request;
        private Result result = new NotFound();

        @Override
        public Result get(GetRequest request) {
            this.request = request;
            return result;
        }

        @Override
        public ListResult list(ListRequest request) {
            throw new AssertionError("stop must not list meetings");
        }
    }
}
