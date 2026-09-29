package com.czur.reference.application.port;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.port.ArtifactDeletionItemUnitOfWork.DeleteRequest;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Java boundary tests for the single-item deletion contract. */
class ArtifactDeletionItemUnitOfWorkContractTest {
    private static final Instant CLAIMED = Instant.parse("2026-09-29T08:00:00.000001Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-29T08:05:00.000001Z");

    @Test
    void requestRequiresDeletionLeaseAndTimestampInsideLease() {
        LeaseToken lease = lease(ArtifactDeletionPlan.STEP_TYPE);
        WriteIntentSpec spec = spec();
        DeleteRequest request = new DeleteRequest(lease, 0, spec,
                Instant.parse("2026-09-29T08:01:00.000001Z"));
        assertEquals(0, request.ordinal());
        assertThrows(IllegalArgumentException.class, () -> new DeleteRequest(
                lease, 0, spec, CLAIMED.plusNanos(1)));
        // A wall-clock value after expiry is accepted by the value object so the JDBC
        // fence can return the stable LEASE_EXPIRED result instead of throwing locally.
        assertEquals(EXPIRES, new DeleteRequest(lease, 0, spec, EXPIRES).deletedAt());
    }

    @Test
    void oldLeaseOutcomesAreNonSuccessfulAndRetryabilityIsClosed() {
        for (ArtifactDeletionItemUnitOfWork.ErrorCode code :
                new ArtifactDeletionItemUnitOfWork.ErrorCode[] {
                    ArtifactDeletionItemUnitOfWork.ErrorCode.NOT_OWNER,
                    ArtifactDeletionItemUnitOfWork.ErrorCode.ALREADY_COMPLETED,
                    ArtifactDeletionItemUnitOfWork.ErrorCode.ALREADY_DEAD,
                    ArtifactDeletionItemUnitOfWork.ErrorCode.SPEC_MISMATCH,
                    ArtifactDeletionItemUnitOfWork.ErrorCode.BINDING_MISMATCH}) {
            ArtifactDeletionItemUnitOfWork.Failure failure =
                    new ArtifactDeletionItemUnitOfWork.Failure(code, code.retryable());
            assertFalse(failure.retryable());
        }
        assertTrue(ArtifactDeletionItemUnitOfWork.ErrorCode.OUTCOME_UNKNOWN.retryable());
        assertTrue(ArtifactDeletionItemUnitOfWork.ErrorCode.LEASE_EXPIRED.retryable());
    }

    @Test
    void resultDiagnosticsNeverContainBinding() {
        String binding = "private-binding-value";
        assertFalse(new ArtifactDeletionItemUnitOfWork.Success(
                ArtifactDeletionItemUnitOfWork.SuccessCode.DELETED,
                new StorageBinding(binding)).toString().contains(binding));
    }

    private static LeaseToken lease(String stepType) {
        return new LeaseToken(
                new WorkflowStep.Key("meeting-contract", stepType, "delete-key"),
                "worker-contract", "opaque-token", EXPIRES, CLAIMED, 1);
    }

    private static WriteIntentSpec spec() {
        return new WriteIntentSpec(
                "artifact-contract", "meeting-contract", "transcript", "text/plain",
                "zh-CN", 1, 0, "sha256:" + "0".repeat(64));
    }
}
