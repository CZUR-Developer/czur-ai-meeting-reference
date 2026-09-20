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
            ErrorCode.OUTCOME_UNKNOWN,
            ErrorCode.INTERNAL);

    @Test
    void failureAcceptsOnlyNonRetryableDeterministicCodes() {
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
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingCreateFailure(ErrorCode.INTERNAL, false),
                "INTERNAL 没有稳定业务语义，不得成为持久化创建失败");
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
    void successRequiresUnchangedCreatedSnapshotForOwnedAndHistoricalResults() {
        Instant createdAt = Instant.parse("2026-09-17T11:00:00Z");
        Instant updatedLater = createdAt.plusSeconds(1);
        for (MeetingState invalidState : new MeetingState[]{
                MeetingState.RUNNING, MeetingState.STOPPING
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    MeetingCreateOutcome.success(ownedResult(
                            invalidState, createdAt, createdAt)));
            assertThrows(IllegalArgumentException.class, () ->
                    MeetingCreateOutcome.restoreHistoricalSuccess(legacyResult(
                            invalidState, createdAt, createdAt)));
        }
        assertThrows(IllegalArgumentException.class, () ->
                MeetingCreateOutcome.success(ownedResult(
                        MeetingState.CREATED, createdAt, updatedLater)));
        assertThrows(IllegalArgumentException.class, () ->
                MeetingCreateOutcome.restoreHistoricalSuccess(legacyResult(
                        MeetingState.CREATED, createdAt, updatedLater)));

        assertEquals(MeetingState.CREATED,
                MeetingCreateOutcome.success(ownedResult(
                        MeetingState.CREATED, createdAt, createdAt))
                        .result().meeting().state());
        assertEquals(MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                MeetingCreateOutcome.restoreHistoricalSuccess(legacyResult(
                        MeetingState.CREATED, createdAt, createdAt))
                        .result().meeting().subjectScope());
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
        return ownedResult(MeetingState.CREATED, now, now);
    }

    private static MeetingCommandResult ownedResult(
            MeetingState state, Instant createdAt, Instant updatedAt) {
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "meeting-secret", "deployment-secret", "session-secret",
                        "subject-secret", "grant-secret", createdAt.plusSeconds(600),
                        "reservation-secret", "profile", 1,
                        state, createdAt, updatedAt));
    }

    private static MeetingCommandResult legacyResult(
            MeetingState state, Instant createdAt, Instant updatedAt) {
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "legacy", "deployment", "session", "profile", 1,
                        state, createdAt, updatedAt));
    }
}
