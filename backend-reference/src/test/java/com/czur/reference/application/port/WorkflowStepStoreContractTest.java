package com.czur.reference.application.port;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结工作流事实、请求值对象和诊断脱敏的纯 Java 契约。 */
class WorkflowStepStoreContractTest {
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    private static final String MEETING = "meeting-workflow-sensitive";
    private static final String PAYLOAD = "canonical-payload-sensitive";
    private static final String DIGEST = "digest-sensitive";

    @Test
    void pendingFactoryFreezesInitialCheckpointAndDefensiveWhitelist() {
        WorkflowStep step = pending("summary", "main", NOW);
        assertEquals(WorkflowStep.Status.PENDING, step.status());
        assertEquals(0, step.attempt());
        assertEquals(0, step.leaseVersion());

        Set<String> mutable = new HashSet<>(Set.of("summary"));
        WorkflowStepStore.ClaimRequest request = new WorkflowStepStore.ClaimRequest(
                mutable, "worker", NOW, Duration.ofSeconds(10), 2);
        mutable.add("translate");
        assertEquals(Set.of("summary"), request.allowedStepTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> request.allowedStepTypes().add("other"));
    }

    @Test
    void invalidFactsRequestsAndLeaseTokensFailClosed() {
        WorkflowStep.Key key = new WorkflowStep.Key(MEETING, "summary", "main");
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStep.Key(" ", "summary", "main"));
        assertThrows(IllegalArgumentException.class,
                    () -> WorkflowStep.pending(key, "checkpoint-json", " ", DIGEST, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStep(
                        key, "checkpoint-json", PAYLOAD, DIGEST, WorkflowStep.Status.LEASED, 0,
                        NOW, null, null, 1, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStep(
                        key, "checkpoint-json", PAYLOAD, DIGEST, WorkflowStep.Status.LEASED, 0,
                        NOW, "owner", NOW.plusSeconds(1), 0, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStep(
                        key, "checkpoint-json", PAYLOAD, DIGEST,
                        WorkflowStep.Status.COMPLETED, 1,
                        null, null, null, 1, "ERROR", "summary", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStep(
                        key, "checkpoint-json", PAYLOAD, DIGEST, WorkflowStep.Status.DEAD, 1,
                        null, null, null, 1, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.ClaimRequest(
                        Set.of("summary"), "worker", NOW, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.LeaseToken(
                        key, "worker", "token", NOW, NOW, 1));
    }

    @Test
    void diagnosticTextRedactsMeetingPayloadDigestOwnerAndToken() {
        WorkflowStep step = pending("summary", "main", NOW);
        WorkflowStepStore.LeaseToken token = new WorkflowStepStore.LeaseToken(
                step.key(), "owner-sensitive", "token-sensitive",
                NOW.plusSeconds(10), NOW, 1);
        String text = step + " " + step.key() + " " + token + " "
                + new WorkflowStepStore.RegisterResult(
                WorkflowStepStore.RegisterStatus.APPLIED, step);

        for (String secret : new String[]{
                MEETING, PAYLOAD, DIGEST, "owner-sensitive", "token-sensitive"}) {
            assertFalse(text.contains(secret));
        }
        assertTrue(text.contains("redacted"));
    }

    @Test
    void resultObjectsRejectContradictorySnapshotsAndLeaseFences() {
        WorkflowStep pending = pending("summary", "main", NOW);
        WorkflowStep leased = new WorkflowStep(
                pending.key(), pending.checkpointType(), PAYLOAD, DIGEST,
                WorkflowStep.Status.LEASED, 0, null,
                "owner", NOW.plusSeconds(10), 1, null, null, NOW);
        WorkflowStep completed = new WorkflowStep(
                pending.key(), pending.checkpointType(), PAYLOAD, DIGEST,
                WorkflowStep.Status.COMPLETED, 0, null,
                null, null, 1, null, null, NOW.plusSeconds(1));
        WorkflowStepStore.LeaseToken lease = new WorkflowStepStore.LeaseToken(
                pending.key(), "owner", "token", NOW.plusSeconds(10), NOW, 1);

        new WorkflowStepStore.ClaimedStep(leased, lease);
        new WorkflowStepStore.RenewResult(
                WorkflowStepStore.RenewStatus.RENEWED, leased, lease);
        new WorkflowStepStore.CompleteResult(
                WorkflowStepStore.CompleteStatus.COMPLETED, completed);
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.ClaimedStep(pending, lease));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.RenewResult(
                        WorkflowStepStore.RenewStatus.NOT_FOUND, pending, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.RenewResult(
                        WorkflowStepStore.RenewStatus.NOT_OWNER, leased, lease));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.CompleteResult(
                        WorkflowStepStore.CompleteStatus.COMPLETED, pending));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.RenewResult(
                        WorkflowStepStore.RenewStatus.LEASE_EXPIRED, pending, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.CompleteResult(
                        WorkflowStepStore.CompleteStatus.LEASE_EXPIRED, pending));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.FailResult(
                        WorkflowStepStore.FailStatus.RETRY_SCHEDULED, leased));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStepStore.FailResult(
                        WorkflowStepStore.FailStatus.LEASE_EXPIRED, pending));
        assertThrows(NullPointerException.class,
                () -> new WorkflowStepStore.RegisterResult(
                        WorkflowStepStore.RegisterStatus.APPLIED, null));
    }

    private static WorkflowStep pending(String type, String stepKey, Instant nextAttemptAt) {
        return WorkflowStep.pending(
                new WorkflowStep.Key(MEETING, type, stepKey),
                "checkpoint-json", PAYLOAD, DIGEST, nextAttemptAt, NOW);
    }
}
