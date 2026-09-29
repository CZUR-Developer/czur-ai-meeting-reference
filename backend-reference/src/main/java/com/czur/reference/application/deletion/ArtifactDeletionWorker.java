package com.czur.reference.application.deletion;

import com.czur.reference.application.port.ArtifactDeletionItemUnitOfWork;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.ArtifactDeletionTerminalUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.WorkflowStepStore.ClaimedStep;
import com.czur.reference.application.port.WorkflowStepStore.FailResult;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronously driven worker for the artifact deletion workflow.
 *
 * <p>This class deliberately owns no thread, timer, queue, or process state.  A caller
 * invokes {@link #runOnce()} and may invoke it again after a restart.  The workflow
 * checkpoint is the only source for the frozen artifact definitions; the plan store is
 * read only to prove that the admission fact still exists.  Each item is handed to the
 * fenced item unit of work, and the terminal unit of work is called only after every
 * item has returned success (including an idempotent replay).</p>
 */
public final class ArtifactDeletionWorker {
    private static final Logger LOGGER = Logger.getLogger(ArtifactDeletionWorker.class.getName());
    private static final Set<String> ALLOWED_TYPES = Set.of(ArtifactDeletionPlan.STEP_TYPE);

    private final WorkflowStepStore workflowStore;
    private final ArtifactDeletionPlanStore planStore;
    private final ArtifactDeletionItemUnitOfWork itemUnit;
    private final ArtifactDeletionTerminalUnitOfWork terminalUnit;
    private final Config config;

    public ArtifactDeletionWorker(
            WorkflowStepStore workflowStore,
            ArtifactDeletionPlanStore planStore,
            ArtifactDeletionItemUnitOfWork itemUnit,
            ArtifactDeletionTerminalUnitOfWork terminalUnit,
            Config config) {
        this.workflowStore = Objects.requireNonNull(workflowStore, "workflowStore");
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.itemUnit = Objects.requireNonNull(itemUnit, "itemUnit");
        this.terminalUnit = Objects.requireNonNull(terminalUnit, "terminalUnit");
        this.config = Objects.requireNonNull(config, "config");
        LOGGER.log(Level.INFO, "Artifact 删除 Worker 已创建（无后台线程）");
    }

    /** Claim and process at most one persisted batch. */
    public RunResult runOnce() {
        Instant claimAt = now();
        List<ClaimedStep> claimed = workflowStore.claim(new WorkflowStepStore.ClaimRequest(
                ALLOWED_TYPES, config.leaseOwner(), claimAt, config.leaseDuration(),
                config.batchLimit()));
        if (claimed.isEmpty()) {
            LOGGER.log(Level.FINE, "Artifact 删除 Worker 本轮无可领取任务");
            return new RunResult(0, List.of());
        }
        List<ItemResult> results = new ArrayList<>(claimed.size());
        for (ClaimedStep step : claimed) {
            results.add(process(step));
        }
        return new RunResult(claimed.size(), results);
    }

    private ItemResult process(ClaimedStep claimed) {
        WorkflowStep step = claimed.step();
        LeaseContext lease = new LeaseContext(claimed.lease());
        ArtifactDeletionPlan plan;
        try {
            plan = decodePlan(step);
        } catch (IllegalArgumentException invalid) {
            LOGGER.log(Level.SEVERE, "Artifact 删除 checkpoint 损坏，关闭式停止 reason=CHECKPOINT_INVALID");
            return item(step, ItemStatus.INCONSISTENT, "CHECKPOINT_INVALID");
        }

        try {
            var persisted = planStore.find(plan.meetingId()).orElse(null);
            if (persisted == null || !persisted.plan().equals(plan)) {
                return recordFailure(step, lease, "PLAN_NOT_FOUND", "PLAN_NOT_FOUND", true);
            }
        } catch (ArtifactDeletionPlanStore.StoreException unavailable) {
            LOGGER.log(Level.WARNING, "Artifact 删除计划 Store 暂不可用 code={0}", unavailable.code());
            return recordFailure(step, lease, "PLAN_STORE_" + unavailable.code().name(),
                    "PLAN_STORE_FAILURE", true);
        }

        for (int ordinal = 0; ordinal < plan.targets().size(); ordinal++) {
            ItemResult stop = ensureLeaseOrStop(step, lease);
            if (stop != null) {
                return stop;
            }
            WriteIntentSpec spec = plan.targets().get(ordinal).spec();
            ArtifactDeletionItemUnitOfWork.Result result = itemUnit.delete(
                    new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                            lease.current(), ordinal, spec, now()));
            if (result instanceof ArtifactDeletionItemUnitOfWork.Success) {
                continue;
            }
            ArtifactDeletionItemUnitOfWork.Failure failure =
                    (ArtifactDeletionItemUnitOfWork.Failure) result;
            return handleItemFailure(step, lease, failure);
        }

        ItemResult stop = ensureLeaseOrStop(step, lease);
        if (stop != null) {
            return stop;
        }
        Instant completedAt = now();
        if (!insideLease(lease.current(), completedAt)) {
            return leaseFailure(step, "LEASE_EXPIRED");
        }
        ArtifactDeletionTerminalUnitOfWork.CompleteRequest request =
                new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                        lease.current(), completedAt);
        ArtifactDeletionTerminalUnitOfWork.Result result = terminalUnit.complete(request);
        if (result instanceof ArtifactDeletionTerminalUnitOfWork.Success success) {
            LOGGER.log(Level.INFO, "Artifact 删除终态已确认 code={0}", success.code());
            return item(step, ItemStatus.COMPLETED, success.code().name());
        }
        ArtifactDeletionTerminalUnitOfWork.Failure failure =
                (ArtifactDeletionTerminalUnitOfWork.Failure) result;
        if (failure.code() == ArtifactDeletionTerminalUnitOfWork.ErrorCode.OUTCOME_UNKNOWN) {
            ArtifactDeletionTerminalUnitOfWork.Result recovered = terminalUnit.recover(request);
            if (recovered instanceof ArtifactDeletionTerminalUnitOfWork.Success success) {
                LOGGER.log(Level.INFO, "Artifact 删除终态结果未知后已恢复 code={0}", success.code());
                return item(step, ItemStatus.COMPLETED, success.code().name());
            }
            failure = (ArtifactDeletionTerminalUnitOfWork.Failure) recovered;
        }
        return handleTerminalFailure(step, lease, failure);
    }

    private ArtifactDeletionPlan decodePlan(WorkflowStep step) {
        if (!ArtifactDeletionPlan.STEP_TYPE.equals(step.key().stepType())
                || !ArtifactDeletionPlan.CHECKPOINT_TYPE.equals(step.checkpointType())) {
            throw new IllegalArgumentException("checkpoint type is invalid");
        }
        ArtifactDeletionPlan plan = ArtifactDeletionPlanJson.decode(step.canonicalPayload());
        WorkflowStep expected = ArtifactDeletionPlanJson.workflowStep(plan);
        if (!expected.key().equals(step.key())
                || !expected.canonicalPayload().equals(step.canonicalPayload())
                || !expected.contentDigest().equals(step.contentDigest())) {
            throw new IllegalArgumentException("checkpoint definition is inconsistent");
        }
        return plan;
    }

    private ItemResult handleItemFailure(
            WorkflowStep step,
            LeaseContext lease,
            ArtifactDeletionItemUnitOfWork.Failure failure) {
        switch (failure.code()) {
            case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED ->
                    { return leaseFailure(step, failure.code().name()); }
            case ALREADY_COMPLETED, ALREADY_DEAD ->
                    { return item(step, ItemStatus.TERMINAL_CONFLICT, failure.code().name()); }
            default -> { }
        }
        return recordFailure(step, lease, failure.code().name(),
                "ARTIFACT_DELETION_" + failure.code().name(), failure.retryable());
    }

    private ItemResult handleTerminalFailure(
            WorkflowStep step,
            LeaseContext lease,
            ArtifactDeletionTerminalUnitOfWork.Failure failure) {
        switch (failure.code()) {
            case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED ->
                    { return leaseFailure(step, failure.code().name()); }
            case ALREADY_COMPLETED, ALREADY_DEAD ->
                    { return item(step, ItemStatus.TERMINAL_CONFLICT, failure.code().name()); }
            default -> { }
        }
        return recordFailure(step, lease, failure.code().name(),
                "ARTIFACT_DELETION_TERMINAL_" + failure.code().name(), failure.retryable());
    }

    private ItemResult recordFailure(
            WorkflowStep step,
            LeaseContext lease,
            String reasonCode,
            String errorSummary,
            boolean retryable) {
        ItemResult stop = ensureLeaseOrStop(step, lease);
        if (stop != null) {
            return stop;
        }
        Instant failedAt = now();
        Instant nextAttempt;
        try {
            nextAttempt = failedAt.plus(config.retryDelay());
        } catch (RuntimeException overflow) {
            return item(step, ItemStatus.INCONSISTENT, "RETRY_TIME_INVALID");
        }
        int maxAttempts = config.maxAttempts();
        if (!retryable) {
            try {
                maxAttempts = Math.addExact(step.attempt(), 1);
            } catch (ArithmeticException exhausted) {
                return item(step, ItemStatus.INCONSISTENT, "ATTEMPT_RANGE_EXHAUSTED");
            }
        }
        FailResult result = workflowStore.fail(
                lease.current(), failedAt, nextAttempt, reasonCode, errorSummary, maxAttempts);
        return switch (result.status()) {
            case RETRY_SCHEDULED -> {
                LOGGER.log(Level.INFO, "Artifact 删除已安排重试 reason={0}", reasonCode);
                yield item(step, ItemStatus.RETRY_SCHEDULED, reasonCode);
            }
            case DEAD -> {
                LOGGER.log(Level.WARNING, "Artifact 删除 Workflow 已进入 DEAD reason={0}", reasonCode);
                yield item(step, ItemStatus.DEAD, reasonCode);
            }
            case NOT_FOUND, NOT_OWNER -> leaseFailure(step, result.status().name());
            case LEASE_EXPIRED -> leaseFailure(step, "LEASE_EXPIRED");
            case ALREADY_COMPLETED, ALREADY_DEAD ->
                    item(step, ItemStatus.TERMINAL_CONFLICT, result.status().name());
        };
    }

    private ItemResult ensureLeaseOrStop(WorkflowStep step, LeaseContext lease) {
        LeaseGuard guard = lease.ensureUsable();
        return switch (guard.status()) {
            case ACTIVE -> null;
            case LOST -> leaseFailure(step, guard.reasonCode());
            case TERMINAL -> item(step, ItemStatus.TERMINAL_CONFLICT, guard.reasonCode());
            case BLOCKED -> item(step, ItemStatus.INCONSISTENT, guard.reasonCode());
        };
    }

    private ItemResult leaseFailure(WorkflowStep step, String reason) {
        LOGGER.log(Level.WARNING, "Artifact 删除 lease 栅栏停止后续写入 reason={0}", reason);
        return item(step, "LEASE_EXPIRED".equals(reason)
                ? ItemStatus.LEASE_EXPIRED : ItemStatus.LEASE_LOST, reason);
    }

    private static ItemResult item(WorkflowStep step, ItemStatus status, String reason) {
        return new ItemResult(step.key(), status, reason);
    }

    private Instant now() {
        return Objects.requireNonNull(config.clock().instant(), "clock.instant()")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean insideLease(LeaseToken lease, Instant at) {
        return !at.isBefore(lease.claimedAt()) && at.isBefore(lease.expiresAt());
    }

    private final class LeaseContext {
        private LeaseToken current;

        private LeaseContext(LeaseToken initial) {
            current = Objects.requireNonNull(initial, "initial");
        }

        private LeaseToken current() {
            return current;
        }

        private LeaseGuard ensureUsable() {
            Instant checkedAt = now();
            if (checkedAt.isBefore(current.claimedAt())) {
                return new LeaseGuard(LeaseGuardStatus.BLOCKED, "CLOCK_MOVED_BACKWARDS");
            }
            if (!checkedAt.isBefore(current.expiresAt())) {
                return new LeaseGuard(LeaseGuardStatus.LOST, "LEASE_EXPIRED");
            }
            if (Duration.between(checkedAt, current.expiresAt())
                    .compareTo(config.renewBefore()) > 0) {
                return new LeaseGuard(LeaseGuardStatus.ACTIVE, "ACTIVE");
            }
            WorkflowStepStore.RenewResult renewed = workflowStore.renew(
                    current, checkedAt, config.leaseDuration());
            return switch (renewed.status()) {
                case RENEWED -> {
                    current = renewed.lease();
                    yield new LeaseGuard(LeaseGuardStatus.ACTIVE, "RENEWED");
                }
                case ALREADY_COMPLETED, ALREADY_DEAD ->
                        new LeaseGuard(LeaseGuardStatus.TERMINAL, renewed.status().name());
                case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED ->
                        new LeaseGuard(LeaseGuardStatus.LOST, renewed.status().name());
            };
        }
    }

    private record LeaseGuard(LeaseGuardStatus status, String reasonCode) {
        private LeaseGuard {
            Objects.requireNonNull(status, "status");
            requireReason(reasonCode);
        }
    }

    private enum LeaseGuardStatus { ACTIVE, LOST, TERMINAL, BLOCKED }

    public record Config(
            String leaseOwner,
            Clock clock,
            Duration leaseDuration,
            Duration renewBefore,
            Duration retryDelay,
            int batchLimit,
            int maxAttempts) {
        public Config {
            requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(clock, "clock");
            requirePositive("leaseDuration", leaseDuration);
            Objects.requireNonNull(renewBefore, "renewBefore");
            Objects.requireNonNull(retryDelay, "retryDelay");
            requireMicros("leaseDuration", leaseDuration);
            requireMicros("renewBefore", renewBefore);
            requireMicros("retryDelay", retryDelay);
            if (renewBefore.isNegative() || renewBefore.compareTo(leaseDuration) >= 0) {
                throw new IllegalArgumentException("renewBefore must be shorter than leaseDuration");
            }
            if (retryDelay.isNegative() || batchLimit <= 0 || maxAttempts <= 0) {
                throw new IllegalArgumentException("retryDelay, batchLimit and maxAttempts are invalid");
            }
        }

        public static Config defaults(String leaseOwner) {
            return new Config(leaseOwner, Clock.systemUTC(), Duration.ofSeconds(30),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 20, 3);
        }
    }

    public record RunResult(int claimedCount, List<ItemResult> items) {
        public RunResult {
            if (claimedCount < 0) throw new IllegalArgumentException("claimedCount must not be negative");
            Objects.requireNonNull(items, "items");
            if (items.size() != claimedCount) throw new IllegalArgumentException("items size must equal claimedCount");
            items = List.copyOf(items);
        }

        public boolean idle() { return claimedCount == 0; }
    }

    public record ItemResult(WorkflowStep.Key key, ItemStatus status, String reasonCode) {
        public ItemResult {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(status, "status");
            requireReason(reasonCode);
        }

        @Override
        public String toString() {
            return "ArtifactDeletionWorker.ItemResult[key=redacted,status="
                    + status + ",reasonCode=redacted]";
        }
    }

    public enum ItemStatus {
        COMPLETED, RETRY_SCHEDULED, DEAD, LEASE_LOST, LEASE_EXPIRED,
        STORE_FAILURE, OUTCOME_UNRESOLVED, TERMINAL_CONFLICT, INCONSISTENT
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireMicros(String name, Duration value) {
        if (value.getNano() % 1_000 != 0) throw new IllegalArgumentException(name + " must use microsecond precision");
    }

    private static void requireReason(String reason) {
        requireText("reasonCode", reason);
        if (!reason.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
        }
    }
}
