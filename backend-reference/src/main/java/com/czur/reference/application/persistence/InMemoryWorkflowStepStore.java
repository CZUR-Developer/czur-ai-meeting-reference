package com.czur.reference.application.persistence;

import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link WorkflowStepStore} 的线程安全单进程参考实现。
 *
 * <p>所有读写在同一互斥区线性化，模拟数据库行锁和条件更新。实现不读取系统墙钟；
 * claim、renew、complete 和 fail 的判定时间全部来自请求。进程退出后事实会丢失，
 * 因而本类不能替代生产 JDBC Store。</p>
 */
public final class InMemoryWorkflowStepStore implements WorkflowStepStore {
    private static final Logger LOGGER =
            Logger.getLogger(InMemoryWorkflowStepStore.class.getName());
    private static final Comparator<Entry> CLAIM_ORDER = Comparator
            .comparing(InMemoryWorkflowStepStore::eligibleAt)
            .thenComparing(entry -> entry.step.key());

    private final Object mutex = new Object();
    private final Map<WorkflowStep.Key, Entry> entries = new HashMap<>();
    private final Supplier<String> tokenGenerator;

    /** 使用随机不透明 token；不会读取时间。 */
    public InMemoryWorkflowStepStore() {
        this(() -> UUID.randomUUID().toString());
    }

    /** 注入 token 生成器，便于确定性测试；返回值必须非空且每次旋转不同。 */
    public InMemoryWorkflowStepStore(Supplier<String> tokenGenerator) {
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
    }

    @Override
    public RegisterResult register(WorkflowStep candidate) {
        requireInitial(candidate);
        synchronized (mutex) {
            Entry existing = entries.get(candidate.key());
            if (existing == null) {
                entries.put(candidate.key(), new Entry(
                        candidate,
                        null,
                        new Definition(
                                candidate.checkpointType(),
                                candidate.canonicalPayload(),
                                candidate.contentDigest(),
                                candidate.nextAttemptAt())));
                LOGGER.log(Level.FINE, "内存工作流步骤已注册 status=APPLIED");
                return new RegisterResult(RegisterStatus.APPLIED, candidate);
            }
            boolean sameDefinition = existing.definition.equals(new Definition(
                    candidate.checkpointType(),
                    candidate.canonicalPayload(),
                    candidate.contentDigest(),
                    candidate.nextAttemptAt()));
            RegisterStatus status = sameDefinition
                    ? RegisterStatus.REPLAYED : RegisterStatus.CONFLICT;
            LOGGER.log(sameDefinition ? Level.FINE : Level.WARNING,
                    "内存工作流步骤注册裁决 status={0}", status);
            return new RegisterResult(status, existing.step);
        }
    }

    @Override
    public Optional<WorkflowStep> find(WorkflowStep.Key key) {
        Objects.requireNonNull(key, "key");
        synchronized (mutex) {
            Entry entry = entries.get(key);
            return entry == null ? Optional.empty() : Optional.of(entry.step);
        }
    }

    @Override
    public List<ClaimedStep> claim(ClaimRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.allowedStepTypes().isEmpty()) {
            return List.of();
        }
        synchronized (mutex) {
            List<Entry> candidates = entries.values().stream()
                    .filter(entry -> request.allowedStepTypes().contains(
                            entry.step.key().stepType()))
                    .filter(entry -> entry.step.status() == WorkflowStep.Status.PENDING
                            || entry.step.status() == WorkflowStep.Status.LEASED)
                    .filter(entry -> !eligibleAt(entry).isAfter(request.now()))
                    .sorted(CLAIM_ORDER)
                    .limit(request.limit())
                    .toList();
            // 先完整计算整个批次。token 生成、版本递增或时间加法任一失败时，
            // entries 尚未发生变化，因此不会留下前 N-1 个半领取步骤。
            Instant expiresAt = request.now().plus(request.leaseDuration());
            if (!expiresAt.isAfter(request.now())) {
                throw new IllegalArgumentException("leaseDuration overflows timestamp");
            }
            List<PlannedClaim> planned = new ArrayList<>(candidates.size());
            for (Entry entry : candidates) {
                long version = Math.addExact(entry.step.leaseVersion(), 1L);
                String token = nextToken(entry.token);
                WorkflowStep leased = new WorkflowStep(
                        entry.step.key(), entry.step.checkpointType(), entry.step.canonicalPayload(),
                        entry.step.contentDigest(), WorkflowStep.Status.LEASED,
                        entry.step.attempt(), null,
                        request.leaseOwner(), expiresAt, version,
                        entry.step.errorCode(), entry.step.errorSummary(), request.now());
                LeaseToken lease = new LeaseToken(
                        leased.key(), request.leaseOwner(), token, expiresAt,
                        request.now(), version);
                planned.add(new PlannedClaim(entry, leased, token, lease));
            }
            List<ClaimedStep> claimed = new ArrayList<>(planned.size());
            for (PlannedClaim plan : planned) {
                plan.entry.step = plan.step;
                plan.entry.token = plan.token;
                claimed.add(new ClaimedStep(plan.step, plan.lease));
            }
            if (!claimed.isEmpty()) {
                LOGGER.log(Level.FINE, "内存工作流步骤领取完成 count={0}", claimed.size());
            }
            return List.copyOf(claimed);
        }
    }

    @Override
    public RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(renewedAt, "renewedAt");
        requirePositive(leaseDuration);
        Instant nextExpiry = renewedAt.plus(leaseDuration);
        if (!nextExpiry.isAfter(lease.expiresAt())) {
            throw new IllegalArgumentException("renew must extend lease expiry");
        }
        synchronized (mutex) {
            Entry entry = entries.get(lease.key());
            RenewStatus terminal = renewTerminal(entry);
            if (terminal != null) {
                return new RenewResult(terminal, entry == null ? null : entry.step, null);
            }
            if (!owns(entry, lease)) {
                return new RenewResult(RenewStatus.NOT_OWNER, entry.step, null);
            }
            if (renewedAt.isBefore(entry.step.updatedAt())
                    || !renewedAt.isBefore(entry.step.leaseUntil())) {
                return new RenewResult(RenewStatus.LEASE_EXPIRED, entry.step, null);
            }
            long version = Math.addExact(entry.step.leaseVersion(), 1L);
            String token = nextToken(entry.token);
            WorkflowStep renewed = new WorkflowStep(
                    entry.step.key(), entry.step.checkpointType(), entry.step.canonicalPayload(),
                    entry.step.contentDigest(), WorkflowStep.Status.LEASED,
                    entry.step.attempt(), null,
                    lease.owner(), nextExpiry, version, entry.step.errorCode(),
                    entry.step.errorSummary(), renewedAt);
            entry.step = renewed;
            entry.token = token;
            LeaseToken rotated = new LeaseToken(
                    lease.key(), lease.owner(), token, nextExpiry, renewedAt, version);
            LOGGER.log(Level.FINE, "内存工作流步骤续租完成 status=RENEWED");
            return new RenewResult(RenewStatus.RENEWED, renewed, rotated);
        }
    }

    @Override
    public PersistLeaseMarkerResult persistLeaseMarker(
            PersistLeaseMarkerRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (mutex) {
            Entry entry = entries.get(request.lease().key());
            PersistLeaseMarkerStatus terminal = markerTerminal(entry);
            if (terminal != null) {
                return new PersistLeaseMarkerResult(
                        terminal, entry == null ? null : entry.step);
            }
            if (!owns(entry, request.lease())) {
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.NOT_OWNER, entry.step);
            }
            if (request.markedAt().isBefore(entry.step.updatedAt())
                    || !request.markedAt().isBefore(entry.step.leaseUntil())) {
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.LEASE_EXPIRED, entry.step);
            }
            if (markerEquals(
                    entry.step, request.markerCode(), request.markerSummary())) {
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.REPLAYED, entry.step);
            }
            if (!markerEquals(
                    entry.step,
                    request.expectedErrorCode(),
                    request.expectedErrorSummary())) {
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.CONFLICT, entry.step);
            }
            WorkflowStep marked = new WorkflowStep(
                    entry.step.key(),
                    entry.step.checkpointType(),
                    entry.step.canonicalPayload(),
                    entry.step.contentDigest(),
                    entry.step.status(),
                    entry.step.attempt(),
                    entry.step.nextAttemptAt(),
                    entry.step.leaseOwner(),
                    entry.step.leaseUntil(),
                    entry.step.leaseVersion(),
                    request.markerCode(),
                    request.markerSummary(),
                    entry.step.updatedAt());
            entry.step = marked;
            LOGGER.log(Level.FINE,
                    "内存工作流终态操作 marker 已持久化 status=PERSISTED");
            return new PersistLeaseMarkerResult(
                    PersistLeaseMarkerStatus.PERSISTED, marked);
        }
    }

    @Override
    public PersistLeaseMarkerResult recoverLeaseMarker(
            PersistLeaseMarkerRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (mutex) {
            Entry entry = entries.get(request.lease().key());
            PersistLeaseMarkerStatus terminal = markerTerminal(entry);
            if (terminal != null) {
                return new PersistLeaseMarkerResult(
                        terminal, entry == null ? null : entry.step);
            }
            if (!owns(entry, request.lease())) {
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.NOT_OWNER, entry.step);
            }
            if (request.markedAt().isBefore(entry.step.updatedAt())
                    || !request.markedAt().isBefore(entry.step.leaseUntil())) {
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.LEASE_EXPIRED, entry.step);
            }
            PersistLeaseMarkerStatus status = markerEquals(
                    entry.step, request.markerCode(), request.markerSummary())
                    ? PersistLeaseMarkerStatus.REPLAYED
                    : PersistLeaseMarkerStatus.CONFLICT;
            return new PersistLeaseMarkerResult(status, entry.step);
        }
    }

    @Override
    public CompleteResult complete(LeaseToken lease, Instant completedAt) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(completedAt, "completedAt");
        synchronized (mutex) {
            Entry entry = entries.get(lease.key());
            CompleteStatus terminal = completeTerminal(entry);
            if (terminal != null) {
                return new CompleteResult(terminal, entry == null ? null : entry.step);
            }
            if (!owns(entry, lease)) {
                return new CompleteResult(CompleteStatus.NOT_OWNER, entry.step);
            }
            if (completedAt.isBefore(entry.step.updatedAt())
                    || !completedAt.isBefore(entry.step.leaseUntil())) {
                return new CompleteResult(CompleteStatus.LEASE_EXPIRED, entry.step);
            }
            WorkflowStep completed = new WorkflowStep(
                    entry.step.key(), entry.step.checkpointType(), entry.step.canonicalPayload(),
                    entry.step.contentDigest(),
                    WorkflowStep.Status.COMPLETED, entry.step.attempt(),
                    null, null, null, entry.step.leaseVersion(),
                    null, null, completedAt);
            entry.step = completed;
            entry.token = null;
            LOGGER.log(Level.INFO, "内存工作流步骤完成 status=COMPLETED");
            return new CompleteResult(CompleteStatus.COMPLETED, completed);
        }
    }

    @Override
    public FailResult fail(
            LeaseToken lease,
            Instant failedAt,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            int maxAttempts) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireText("errorCode", errorCode);
        requireText("errorSummary", errorSummary);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (nextAttemptAt.isBefore(failedAt)) {
            throw new IllegalArgumentException("nextAttemptAt must not be before failedAt");
        }
        synchronized (mutex) {
            Entry entry = entries.get(lease.key());
            FailStatus terminal = failTerminal(entry);
            if (terminal != null) {
                return new FailResult(terminal, entry == null ? null : entry.step);
            }
            if (!owns(entry, lease)) {
                return new FailResult(FailStatus.NOT_OWNER, entry.step);
            }
            if (failedAt.isBefore(entry.step.updatedAt())
                    || !failedAt.isBefore(entry.step.leaseUntil())) {
                return new FailResult(FailStatus.LEASE_EXPIRED, entry.step);
            }
            int attempt = Math.addExact(entry.step.attempt(), 1);
            boolean dead = attempt >= maxAttempts;
            WorkflowStep failed = new WorkflowStep(
                    entry.step.key(), entry.step.checkpointType(), entry.step.canonicalPayload(),
                    entry.step.contentDigest(),
                    dead ? WorkflowStep.Status.DEAD : WorkflowStep.Status.PENDING,
                    attempt, dead ? null : nextAttemptAt,
                    null, null, entry.step.leaseVersion(),
                    errorCode, errorSummary, failedAt);
            entry.step = failed;
            entry.token = null;
            FailStatus status = dead ? FailStatus.DEAD : FailStatus.RETRY_SCHEDULED;
            LOGGER.log(dead ? Level.WARNING : Level.FINE,
                    "内存工作流步骤失败裁决 status={0} attempt={1}",
                    new Object[]{status, attempt});
            return new FailResult(status, failed);
        }
    }

    private String nextToken(String previous) {
        String token = tokenGenerator.get();
        requireText("generated lease token", token);
        if (token.equals(previous)) {
            throw new IllegalStateException("lease token generator did not rotate token");
        }
        return token;
    }

    private static boolean owns(Entry entry, LeaseToken lease) {
        WorkflowStep step = entry.step;
        return step.status() == WorkflowStep.Status.LEASED
                && step.key().equals(lease.key())
                && step.leaseOwner().equals(lease.owner())
                && Objects.equals(entry.token, lease.token())
                && step.leaseVersion() == lease.leaseVersion()
                && step.leaseUntil().equals(lease.expiresAt())
                && step.updatedAt().equals(lease.claimedAt());
    }

    private static RenewStatus renewTerminal(Entry entry) {
        if (entry == null) return RenewStatus.NOT_FOUND;
        if (entry.step.status() == WorkflowStep.Status.COMPLETED) {
            return RenewStatus.ALREADY_COMPLETED;
        }
        if (entry.step.status() == WorkflowStep.Status.DEAD) return RenewStatus.ALREADY_DEAD;
        return null;
    }

    private static CompleteStatus completeTerminal(Entry entry) {
        if (entry == null) return CompleteStatus.NOT_FOUND;
        if (entry.step.status() == WorkflowStep.Status.COMPLETED) {
            return CompleteStatus.ALREADY_COMPLETED;
        }
        if (entry.step.status() == WorkflowStep.Status.DEAD) {
            return CompleteStatus.ALREADY_DEAD;
        }
        return null;
    }

    private static FailStatus failTerminal(Entry entry) {
        if (entry == null) return FailStatus.NOT_FOUND;
        if (entry.step.status() == WorkflowStep.Status.COMPLETED) {
            return FailStatus.ALREADY_COMPLETED;
        }
        if (entry.step.status() == WorkflowStep.Status.DEAD) return FailStatus.ALREADY_DEAD;
        return null;
    }

    private static PersistLeaseMarkerStatus markerTerminal(Entry entry) {
        if (entry == null) {
            return PersistLeaseMarkerStatus.NOT_FOUND;
        }
        if (entry.step.status() == WorkflowStep.Status.COMPLETED) {
            return PersistLeaseMarkerStatus.ALREADY_COMPLETED;
        }
        if (entry.step.status() == WorkflowStep.Status.DEAD) {
            return PersistLeaseMarkerStatus.ALREADY_DEAD;
        }
        return null;
    }

    private static boolean markerEquals(
            WorkflowStep step,
            String code,
            String summary) {
        return Objects.equals(step.errorCode(), code)
                && Objects.equals(step.errorSummary(), summary);
    }

    private static void requireInitial(WorkflowStep candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.status() != WorkflowStep.Status.PENDING
                || candidate.attempt() != 0
                || candidate.leaseVersion() != 0
                || candidate.leaseOwner() != null
                || candidate.leaseUntil() != null
                || candidate.errorCode() != null
                || candidate.errorSummary() != null) {
            throw new IllegalArgumentException("register requires initial PENDING checkpoint");
        }
    }

    private static Instant eligibleAt(Entry entry) {
        return entry.step.status() == WorkflowStep.Status.PENDING
                ? entry.step.nextAttemptAt()
                : entry.step.leaseUntil();
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static final class Entry {
        private WorkflowStep step;
        private String token;
        private final Definition definition;

        private Entry(WorkflowStep step, String token, Definition definition) {
            this.step = step;
            this.token = token;
            this.definition = definition;
        }
    }

    private record Definition(
            String checkpointType,
            String canonicalPayload,
            String contentDigest,
            Instant initialNextAttemptAt) {
    }

    private record PlannedClaim(
            Entry entry,
            WorkflowStep step,
            String token,
            LeaseToken lease) {
    }
}
