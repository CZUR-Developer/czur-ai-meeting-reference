package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork.ErrorCode;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** authenticated-create Outcome 的终态、不变量和脱敏输出契约。 */
class MeetingCreateOutcomeContractTest {
    private static final Set<ErrorCode> FORBIDDEN = EnumSet.of(
            ErrorCode.IDEMPOTENCY_CONFLICT,
            ErrorCode.STORAGE_UNAVAILABLE,
            ErrorCode.OUTCOME_UNKNOWN);

    @Test
    void failureAcceptsOnlyNonRetryableDeterministicCodesIncludingInternal() {
        for (ErrorCode code : ErrorCode.values()) {
            if (FORBIDDEN.contains(code)) {
                assertThrows(IllegalArgumentException.class,
                        () -> new MeetingCreateFailure(code, false));
            } else {
                assertEquals(code, new MeetingCreateFailure(code, false).code());
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingCreateFailure(ErrorCode.INTERNAL, true));
        assertEquals(ErrorCode.INTERNAL,
                new MeetingCreateFailure(ErrorCode.INTERNAL, false).code(),
                "已确认回滚的 INTERNAL 属于允许持久化的确定失败");
    }

    @Test
    void successRequiresCreateResultAndFailureRequiresValidatedFailure() {
        MeetingCommandResult created = createdResult();
        assertEquals(created, MeetingCreateOutcome.success(created).result());
        assertThrows(IllegalArgumentException.class, () ->
                MeetingCreateOutcome.success(new MeetingCommandResult(
                        MeetingCommandResult.Operation.STOP_ACCEPTED, created.meeting())));
        var legacy = new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "legacy", "deployment", "session", "profile", 1,
                        MeetingState.CREATED, created.meeting().createdAt(),
                        created.meeting().createdAt()));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingCreateOutcome.success(legacy));
        assertEquals(MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                MeetingCreateOutcome.restoreHistoricalSuccess(legacy)
                        .result().meeting().subjectScope());
        assertEquals(ErrorCode.RESERVATION_EXPIRED,
                MeetingCreateOutcome.failure(new MeetingCreateFailure(
                        ErrorCode.RESERVATION_EXPIRED, false)).failure().code());
    }

    @Test
    void recordsNeverExposeScopeKeyDigestSubjectOrMeetingSnapshotInToString() {
        String secretScope = "opaque-scope-with-sensitive-subject";
        String secretKey = "sensitive-key";
        String secretDigest = "sensitive-digest";
        MeetingCreateOutcome success = MeetingCreateOutcome.success(createdResult());
        MeetingCreateOutcomeStore.OutcomeRecord record =
                new MeetingCreateOutcomeStore.OutcomeRecord(
                        secretScope, secretKey, secretDigest, success);
        MeetingCreateOutcomeStore.Reservation reservation =
                new MeetingCreateOutcomeStore.Reservation(
                        MeetingCreateOutcomeStore.ReservationStatus.REPLAY, record);

        String output = success + "\n" + record + "\n" + reservation;
        for (String forbidden : Set.of(
                secretScope, secretKey, secretDigest,
                "subject-secret", "grant-secret", "reservation-secret")) {
            assertFalse(output.contains(forbidden), "toString 泄露字段: " + forbidden);
        }
        assertTrue(output.contains("SUCCESS"));

        MeetingCreateOutcomeStore.StoreException corrupt =
                new MeetingCreateOutcomeStore.StoreException(
                        MeetingCreateOutcomeStore.StoreErrorCode.CORRUPT_DATA);
        assertFalse(corrupt.retryable());
        assertEquals(MeetingCreateOutcomeStore.StoreErrorCode.CORRUPT_DATA,
                corrupt.code());
        assertFalse(corrupt.toString().contains(secretScope));
        assertNull(corrupt.getCause());
    }

    private static MeetingCommandResult createdResult() {
        Instant now = Instant.parse("2026-09-17T11:00:00Z");
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "meeting-secret", "deployment-secret", "session-secret",
                        "subject-secret", "grant-secret", now.plusSeconds(600),
                        "reservation-secret", "profile", 1,
                        MeetingState.CREATED, now, now));
    }
}
