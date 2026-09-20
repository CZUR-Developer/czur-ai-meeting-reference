package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T13 纯 Java 查询、事件、Manifest、receipt 和删除契约测试。
 *
 * <p>本测试不启动 HTTP、数据库或 Worker，重点锁定状态集合、分页边界、
 * 删除的 DELETION_PENDING 语义，以及公开诊断文本不会泄露主体、幂等材料、
 * receipt 或会议正文。</p>
 */
class MeetingQueryAndDeletionContractTest {
    private static final Instant CREATED = Instant.parse("2026-09-20T01:00:00Z");
    private static final String MEETING = "meeting-t13-secret";
    private static final String SUBJECT = "subject-t13-secret";
    private static final String IDEMPOTENCY = "idempotency-t13-secret";
    private static final String DIGEST = "digest-t13-secret";
    private static final String RECEIPT = "receipt-t13-secret";
    private static final String BODY = "敏感会议正文-secret";

    @Test
    void finalizationReceiptIsPresentOnlyAfterInputAccepted() {
        for (InputState state : Set.of(
                InputState.INPUT_OPEN,
                InputState.INPUT_COMPLETE_PENDING,
                InputState.INPUT_CANCELLED)) {
            FinalizationFact fact = FinalizationFact.pending(state);
            assertFalse(fact.isAccepted());
            assertEquals(null, fact.finalizationReceipt());
        }

        FinalizationFact accepted = FinalizationFact.accepted(RECEIPT);
        assertTrue(accepted.isAccepted());
        assertEquals(RECEIPT, accepted.finalizationReceipt());
        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationFact(InputState.INPUT_ACCEPTED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationFact(InputState.INPUT_OPEN, RECEIPT));
        assertThrows(IllegalArgumentException.class,
                () -> FinalizationFact.accepted(" "));
    }

    @Test
    void meetingStatusUsesExistingMeetingStateAndProtocolStates() {
        MeetingStatus status = new MeetingStatus(
                MEETING,
                MeetingState.FINALIZING,
                FinalizationFact.accepted(RECEIPT),
                ManifestStatus.GENERATING,
                CREATED);
        assertEquals(MeetingState.FINALIZING, status.state());
        assertEquals(InputState.INPUT_ACCEPTED, status.inputState());
        assertEquals(ManifestStatus.GENERATING, status.manifestState());
        assertEquals(RECEIPT, status.finalizationReceipt());
        assertEquals(MeetingState.FINALIZING, status.summary(CREATED).state());
        assertThrows(NullPointerException.class,
                () -> new MeetingStatus(MEETING, null,
                        FinalizationFact.pending(InputState.INPUT_OPEN),
                        ManifestStatus.NOT_STARTED, CREATED));
    }

    @Test
    void eventPageEnforcesMonotonicSequenceAndHidesPayload() {
        EventLog first = event(1, "transcriptUpsert", BODY);
        EventLog second = event(2, "finalization", "{" + BODY + "}");
        MeetingEventPage page = new MeetingEventPage(List.of(first, second), 2, false);
        assertEquals(2, page.lastSequence());
        assertEquals(List.of(first, second), page.items());
        assertFalse(page.toString().contains(BODY));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingEventPage(List.of(second, first), 2, false));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingEventPage(List.of(first), 2, false));
        assertEquals(0, MeetingEventPage.empty(0).lastSequence());
    }

    @Test
    void eventRequestFreezesAfterSequenceAndLimitContract() {
        MeetingEventLogPort.Request request = new MeetingEventLogPort.Request(
                context(), MEETING, 42, 200);
        assertEquals(42, request.afterSequence());
        assertEquals(200, request.limit());
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingEventLogPort.Request(context(), MEETING, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingEventLogPort.Request(context(), MEETING, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingEventLogPort.Request(context(), MEETING, 0, 1001));
    }

    @Test
    void queryAndEventAccessDeniedDoNotDistinguishLegacyFromOtherOwners() {
        MeetingQueryPort.Result queryDenied = new MeetingQueryPort.AccessDenied();
        MeetingEventLogPort.Result eventDenied = new MeetingEventLogPort.AccessDenied();
        MeetingManifestPort.Result manifestDenied = new MeetingManifestPort.AccessDenied();
        DeleteResult deleteDenied = new DeleteResult.AccessDenied();

        assertEquals(MeetingQueryPort.Status.ACCESS_DENIED, queryDenied.status());
        assertEquals(MeetingEventLogPort.Status.ACCESS_DENIED, eventDenied.status());
        assertEquals(MeetingManifestPort.Status.ACCESS_DENIED, manifestDenied.status());
        assertEquals(DeleteResult.Status.ACCESS_DENIED, deleteDenied.status());
        assertEquals("MeetingQueryPort.AccessDenied", queryDenied.toString());
        assertEquals("MeetingEventLogPort.AccessDenied", eventDenied.toString());
        assertEquals("MeetingManifestPort.AccessDenied", manifestDenied.toString());
        assertEquals("DeleteResult.AccessDenied", deleteDenied.toString());
    }

    @Test
    void deleteAcceptAndReplayRequirePendingInsteadOfPhysicalDeletion() {
        MeetingStatus pending = new MeetingStatus(
                MEETING,
                MeetingState.DELETION_PENDING,
                FinalizationFact.accepted(RECEIPT),
                ManifestStatus.GENERATING,
                CREATED);
        DeleteResult accepted = new DeleteResult.Accepted(pending);
        DeleteResult replayed = new DeleteResult.Replayed(pending);
        assertEquals(DeleteResult.Status.DELETION_PENDING, accepted.status());
        assertEquals(DeleteResult.Status.REPLAYED, replayed.status());
        assertThrows(IllegalArgumentException.class,
                () -> new DeleteResult.Accepted(new MeetingStatus(
                        MEETING,
                        MeetingState.DELETED,
                        FinalizationFact.pending(InputState.INPUT_CANCELLED),
                        ManifestStatus.DELETED,
                        CREATED)));
    }

    @Test
    void recordsAndResultsDoNotPrintSensitiveFacts() {
        DeleteRequest request = new DeleteRequest(context(), MEETING, IDEMPOTENCY, DIGEST);
        MeetingStatus status = new MeetingStatus(
                MEETING,
                MeetingState.DELETION_PENDING,
                FinalizationFact.accepted(RECEIPT),
                ManifestStatus.GENERATING,
                CREATED);
        EventLog event = event(1, "transcriptUpsert", BODY);
        String output = List.of(
                request,
                status,
                FinalizationFact.accepted(RECEIPT),
                event,
                new MeetingEventPage(List.of(event), 1, false),
                new MeetingQueryPort.Found(status),
                new MeetingEventLogPort.Page(new MeetingEventPage(List.of(event), 1, false)),
                new MeetingManifestPort.Found(ManifestStatus.GENERATING,
                        FinalizationFact.accepted(RECEIPT)),
                new DeleteResult.Accepted(status)).toString();

        for (String secret : Set.of(SUBJECT, IDEMPOTENCY, DIGEST, RECEIPT, BODY)) {
            assertFalse(output.contains(secret), "诊断文本泄露敏感字段: " + secret);
        }
    }

    @Test
    void unavailableResultsTieRetryabilityToStableReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingQueryPort.Unavailable(
                        MeetingQueryPort.UnavailableReason.STORAGE_FAILURE, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingEventLogPort.Unavailable(
                        MeetingEventLogPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE, false));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingManifestPort.Unavailable(
                        MeetingManifestPort.UnavailableReason.STORAGE_FAILURE, true));
        assertThrows(IllegalArgumentException.class,
                () -> new DeleteResult.Unavailable(
                        DeleteResult.UnavailableReason.TRANSIENT_STORAGE_FAILURE, false));
    }

    private static EventLog event(long sequence, String type, String payload) {
        return new EventLog("event-" + sequence, MEETING, sequence, CREATED,
                type, payload);
    }

    private static MeetingCommandContext context() {
        Instant issuedAt = CREATED.minusSeconds(60);
        return new MeetingCommandContext(
                "deployment-t13-secret",
                new VerifiedIdentity(
                        new Subject(SUBJECT),
                        Set.of(),
                        Set.of("meeting:read", "meeting:delete"),
                        issuedAt,
                        CREATED.plusSeconds(600),
                        "credential-t13-secret"));
    }
}
