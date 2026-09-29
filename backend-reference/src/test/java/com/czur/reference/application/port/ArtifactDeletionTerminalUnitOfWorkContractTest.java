package com.czur.reference.application.port;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 删除终态端口的纯 Java 合同：时间精度、租约边界和结果分类必须关闭。 */
class ArtifactDeletionTerminalUnitOfWorkContractTest {
    private static final Instant AT = Instant.parse("2026-09-29T08:00:00.000001Z");

    @Test
    void requestRequiresDeletionWorkflowAndPostgresPrecision() {
        WorkflowStepStore.LeaseToken lease = new WorkflowStepStore.LeaseToken(
                new WorkflowStep.Key("mtg_contract", ArtifactDeletionPlan.STEP_TYPE, "k"),
                "owner", "token", AT.plusSeconds(10), AT, 1);
        ArtifactDeletionTerminalUnitOfWork.CompleteRequest request =
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(lease, AT.plusSeconds(1));
        assertEquals(AT.plusSeconds(1), request.completedAt());
        assertThrows(IllegalArgumentException.class, () ->
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                        new WorkflowStepStore.LeaseToken(
                                new WorkflowStep.Key("mtg_contract", "other", "k"),
                                "owner", "token", AT.plusSeconds(10), AT, 1), AT));
        assertThrows(IllegalArgumentException.class, () ->
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(lease, AT.plusNanos(1)));
    }

    @Test
    void retryabilityAndEmptyManifestRevisionAreExplicit() {
        ArtifactDeletionTerminalUnitOfWork.Success success =
                new ArtifactDeletionTerminalUnitOfWork.Success(
                        ArtifactDeletionTerminalUnitOfWork.SuccessCode.COMPLETED, null);
        assertEquals(null, success.deletedManifestRevision());
        for (ArtifactDeletionTerminalUnitOfWork.ErrorCode code :
                ArtifactDeletionTerminalUnitOfWork.ErrorCode.values()) {
            assertEquals(code.retryable(),
                    new ArtifactDeletionTerminalUnitOfWork.Failure(code, code.retryable())
                            .retryable());
        }
        assertTrue(new ArtifactDeletionTerminalUnitOfWork.Failure(
                ArtifactDeletionTerminalUnitOfWork.ErrorCode.ITEM_NOT_CONFIRMED, true)
                .retryable());
        assertTrue(new ArtifactDeletionTerminalUnitOfWork.Failure(
                ArtifactDeletionTerminalUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true)
                .retryable());
    }
}
