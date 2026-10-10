package com.czur.reference.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 可恢复工作流步骤的连接无关持久化端口。
 *
 * <p>本端口只管理 checkpoint、租约栅栏、重试和终态，不执行模型、对象存储、
 * Artifact、权益或 HTTP 操作。数据库实现必须用原子条件更新实现相同语义；内存
 * 实现仅用于参考编排和契约测试。</p>
 */
public interface WorkflowStepStore {
    /**
     * 注册一个初始 PENDING checkpoint。
     *
     * <p>同一 Key 首次注册返回 APPLIED；checkpointType、canonical payload、digest
     * 与首次 nextAttemptAt 全部相同的重试返回 REPLAYED；Definition 任一字段不同
     * 返回 CONFLICT，且绝不覆盖赢家。候选必须是 attempt/version 均为零、无
     * lease/错误的 PENDING 事实。</p>
     */
    RegisterResult register(WorkflowStep candidate);

    /** 按稳定 Key 读取当前快照；返回值不能被调用方修改。 */
    Optional<WorkflowStep> find(WorkflowStep.Key key);

    /**
     * 原子领取当前可执行步骤。
     *
     * <p>只有 stepType 位于显式白名单、nextAttemptAt 不晚于 now，且状态为 PENDING
     * 或 lease 已到期的 LEASED 步骤才能进入候选。实现必须先过滤，再按可领取时间
     * （PENDING 使用 nextAttemptAt，过期 LEASED 使用 leaseUntil）、Key 自然顺序
     * 排序并应用 limit。空白名单固定返回空结果。</p>
     *
     * <p>领取成功旋转不透明 token、leaseVersion 加一。过期 lease 可由另一个 owner
     * 重领；旧 owner 的后续操作必须被 fencing 拒绝。</p>
     */
    List<ClaimedStep> claim(ClaimRequest request);

    /**
     * 续租当前未过期 lease。Key、owner、token、version、expiresAt 必须完整匹配，
     * 且 renewedAt 严格早于旧 expiresAt。{@code renewedAt + leaseDuration} 必须严格
     * 晚于传入 {@link LeaseToken#expiresAt()}；该调用参数规则必须在读取当前步骤状态前
     * 关闭式校验，因此 active、missing 和 terminal Key 对同一非法请求都抛出
     * {@link IllegalArgumentException}。成功后 token 旋转、version 加一，旧句柄立即失效。
     */
    RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration);

    /**
     * 在当前 lease 下原子持久化终态操作 marker，但不改变任何调度或栅栏事实。
     *
     * <p>该操作只允许更新 {@link WorkflowStep#errorCode()} 与
     * {@link WorkflowStep#errorSummary()}。状态必须继续为 LEASED，attempt、owner、
     * token、leaseVersion、claimedAt/updatedAt 和 expiresAt 必须逐字保持不变。
     * 这样 Worker 可以在调用跨资源 terminal 工作单元之前先冻结完整请求；进程崩溃
     * 或结果未知后，新 owner 领取时仍能恢复旧请求身份。</p>
     *
     * <p>当前 marker 已与候选相同时返回 REPLAYED；否则只有当前错误字段与请求中的
     * expected pair 相同才允许替换。不同值返回 CONFLICT，禁止静默覆盖一个可能已经
     * 发出的终态请求。</p>
     */
    PersistLeaseMarkerResult persistLeaseMarker(PersistLeaseMarkerRequest request);

    /**
     * marker 写入确认未知后，用新的只读存储会话核对同一 full-fence 请求。
     *
     * <p>只有数据库中的完整 lease 栅栏和 marker 都与请求逐字相同才返回 REPLAYED。
     * 本方法不写入、不续租，也不把“当前仍是原错误字段”猜测成 marker 已持久化。</p>
     */
    PersistLeaseMarkerResult recoverLeaseMarker(PersistLeaseMarkerRequest request);

    /**
     * 完成步骤。只有当前完整 lease 栅栏匹配且 completedAt 严格早于 expiresAt 才能
     * 进入 COMPLETED。终态不可逆；完成后任意迟到 owner 固定收到 ALREADY_COMPLETED。
     */
    CompleteResult complete(LeaseToken lease, Instant completedAt);

    /**
     * 报告失败。合法失败使 attempt 加一；达到 maxAttempts 时进入 DEAD，否则回到
     * PENDING 并使用 nextAttemptAt。{@code nextAttemptAt} 不得早于 {@code failedAt}；
     * 该参数规则必须在读取当前状态或进行 NOT_FOUND/终态裁决前关闭式校验，避免同一
     * 请求因 Store 实现或现有状态不同而得到不同结果。错误字段必须是调用方已脱敏的
     * 稳定码与摘要。过期或被重领的旧 lease 不能覆盖当前 owner 或终态。
     */
    FailResult fail(
            LeaseToken lease,
            Instant failedAt,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            int maxAttempts);

    enum RegisterStatus { APPLIED, REPLAYED, CONFLICT }

    record RegisterResult(RegisterStatus status, WorkflowStep step) {
        public RegisterResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(step, "step");
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.RegisterResult[status=" + status + ", step=redacted]";
        }
    }

    record ClaimRequest(
            Set<String> allowedStepTypes,
            String leaseOwner,
            Instant now,
            Duration leaseDuration,
            int limit) {
        public ClaimRequest {
            Objects.requireNonNull(allowedStepTypes, "allowedStepTypes");
            Set<String> ordered = new TreeSet<>();
            for (String stepType : allowedStepTypes) {
                WorkflowStep.requireText("allowedStepTypes element", stepType);
                ordered.add(stepType);
            }
            allowedStepTypes = Collections.unmodifiableSet(ordered);
            WorkflowStep.requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(now, "now");
            requirePositive(leaseDuration);
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.ClaimRequest[allowedTypeCount="
                    + allowedStepTypes.size() + ", leaseOwner=redacted, now=" + now
                    + ", leaseDuration=" + leaseDuration + ", limit=" + limit + ']';
        }
    }

    /** 只有本对象携带的完整栅栏可以续租、完成或失败；token 永不进入诊断输出。 */
    record LeaseToken(
            WorkflowStep.Key key,
            String owner,
            String token,
            Instant expiresAt,
            Instant claimedAt,
            long leaseVersion) {
        public LeaseToken {
            Objects.requireNonNull(key, "key");
            WorkflowStep.requireText("owner", owner);
            WorkflowStep.requireText("token", token);
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(claimedAt, "claimedAt");
            if (!expiresAt.isAfter(claimedAt)) {
                throw new IllegalArgumentException("expiresAt must be after claimedAt");
            }
            if (leaseVersion <= 0) {
                throw new IllegalArgumentException("leaseVersion must be positive");
            }
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.LeaseToken[key=redacted, owner=redacted, token=redacted"
                    + ", expiresAt=" + expiresAt + ", claimedAt=" + claimedAt
                    + ", leaseVersion=" + leaseVersion + ']';
        }
    }

    record ClaimedStep(WorkflowStep step, LeaseToken lease) {
        public ClaimedStep {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(lease, "lease");
            requireLeasedSnapshotMatches(step, lease, "claimed step");
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.ClaimedStep[step=redacted, lease=redacted]";
        }
    }

    enum RenewStatus {
        RENEWED, NOT_FOUND, NOT_OWNER, LEASE_EXPIRED, ALREADY_COMPLETED, ALREADY_DEAD
    }

    record RenewResult(RenewStatus status, WorkflowStep step, LeaseToken lease) {
        public RenewResult {
            Objects.requireNonNull(status, "status");
            if (status == RenewStatus.RENEWED) {
                Objects.requireNonNull(step, "step");
                Objects.requireNonNull(lease, "lease");
                requireLeasedSnapshotMatches(step, lease, "renewed step");
            } else {
                if (lease != null) {
                    throw new IllegalArgumentException("failed renew must not return lease");
                }
                requireFailureSnapshot(status, step);
            }
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.RenewResult[status=" + status
                    + ", stepPresent=" + (step != null) + ", lease=redacted]";
        }
    }

    enum CompleteStatus {
        COMPLETED, NOT_FOUND, NOT_OWNER, LEASE_EXPIRED, ALREADY_COMPLETED, ALREADY_DEAD
    }

    enum PersistLeaseMarkerStatus {
        PERSISTED,
        REPLAYED,
        CONFLICT,
        NOT_FOUND,
        NOT_OWNER,
        LEASE_EXPIRED,
        ALREADY_COMPLETED,
        ALREADY_DEAD,
        STORAGE_UNAVAILABLE,
        OUTCOME_UNKNOWN
    }

    record PersistLeaseMarkerRequest(
            LeaseToken lease,
            Instant markedAt,
            String expectedErrorCode,
            String expectedErrorSummary,
            String markerCode,
            String markerSummary) {
        public PersistLeaseMarkerRequest {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(markedAt, "markedAt");
            requireOptionalPair(expectedErrorCode, expectedErrorSummary);
            WorkflowStep.requireText("markerCode", markerCode);
            WorkflowStep.requireText("markerSummary", markerSummary);
            if (markedAt.isBefore(lease.claimedAt())
                    || !markedAt.isBefore(lease.expiresAt())) {
                throw new IllegalArgumentException(
                        "markedAt must be inside the lease interval");
            }
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.PersistLeaseMarkerRequest[lease=redacted"
                    + ", markedAt=" + markedAt
                    + ", expectedMarkerPresent=" + (expectedErrorCode != null)
                    + ", marker=redacted]";
        }
    }

    record PersistLeaseMarkerResult(
            PersistLeaseMarkerStatus status,
            WorkflowStep step) {
        public PersistLeaseMarkerResult {
            Objects.requireNonNull(status, "status");
            switch (status) {
                case PERSISTED, REPLAYED ->
                        requireStatus(step, WorkflowStep.Status.LEASED, status.name());
                case NOT_FOUND -> requireAbsent(step, status.name());
                case ALREADY_COMPLETED ->
                        requireStatus(step, WorkflowStep.Status.COMPLETED, status.name());
                case ALREADY_DEAD ->
                        requireStatus(step, WorkflowStep.Status.DEAD, status.name());
                case NOT_OWNER, CONFLICT -> requireNonTerminal(step, status.name());
                case LEASE_EXPIRED ->
                        requireStatus(step, WorkflowStep.Status.LEASED, status.name());
                case STORAGE_UNAVAILABLE, OUTCOME_UNKNOWN ->
                        requireAbsent(step, status.name());
            }
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.PersistLeaseMarkerResult[status=" + status
                    + ", stepPresent=" + (step != null) + ']';
        }
    }

    record CompleteResult(CompleteStatus status, WorkflowStep step) {
        public CompleteResult {
            Objects.requireNonNull(status, "status");
            switch (status) {
                case COMPLETED -> requireStatus(step, WorkflowStep.Status.COMPLETED, status.name());
                case NOT_FOUND -> requireAbsent(step, status.name());
                case ALREADY_COMPLETED ->
                        requireStatus(step, WorkflowStep.Status.COMPLETED, status.name());
                case ALREADY_DEAD -> requireStatus(step, WorkflowStep.Status.DEAD, status.name());
                case NOT_OWNER -> requireNonTerminal(step, status.name());
                case LEASE_EXPIRED -> requireStatus(step, WorkflowStep.Status.LEASED, status.name());
            }
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.CompleteResult[status=" + status
                    + ", stepPresent=" + (step != null) + ']';
        }
    }

    enum FailStatus {
        RETRY_SCHEDULED, DEAD, NOT_FOUND, NOT_OWNER, LEASE_EXPIRED,
        ALREADY_COMPLETED, ALREADY_DEAD
    }

    record FailResult(FailStatus status, WorkflowStep step) {
        public FailResult {
            Objects.requireNonNull(status, "status");
            switch (status) {
                case RETRY_SCHEDULED ->
                        requireStatus(step, WorkflowStep.Status.PENDING, status.name());
                case DEAD -> requireStatus(step, WorkflowStep.Status.DEAD, status.name());
                case NOT_FOUND -> requireAbsent(step, status.name());
                case ALREADY_COMPLETED ->
                        requireStatus(step, WorkflowStep.Status.COMPLETED, status.name());
                case ALREADY_DEAD -> requireStatus(step, WorkflowStep.Status.DEAD, status.name());
                case NOT_OWNER -> requireNonTerminal(step, status.name());
                case LEASE_EXPIRED -> requireStatus(step, WorkflowStep.Status.LEASED, status.name());
            }
        }

        @Override
        public String toString() {
            return "WorkflowStepStore.FailResult[status=" + status
                    + ", stepPresent=" + (step != null) + ']';
        }
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static void requireOptionalPair(String code, String summary) {
        if ((code == null) != (summary == null)) {
            throw new IllegalArgumentException(
                    "expectedErrorCode and expectedErrorSummary must appear together");
        }
        if (code != null) {
            WorkflowStep.requireText("expectedErrorCode", code);
            WorkflowStep.requireText("expectedErrorSummary", summary);
        }
    }

    private static void requireLeasedSnapshotMatches(
            WorkflowStep step,
            LeaseToken lease,
            String operation) {
        if (step.status() != WorkflowStep.Status.LEASED
                || !step.key().equals(lease.key())
                || !step.leaseOwner().equals(lease.owner())
                || !step.leaseUntil().equals(lease.expiresAt())
                || step.leaseVersion() != lease.leaseVersion()
                || !step.updatedAt().equals(lease.claimedAt())) {
            throw new IllegalArgumentException(operation + " and lease must match");
        }
    }

    private static void requireFailureSnapshot(RenewStatus status, WorkflowStep step) {
        switch (status) {
            case NOT_FOUND -> requireAbsent(step, status.name());
            case ALREADY_COMPLETED ->
                    requireStatus(step, WorkflowStep.Status.COMPLETED, status.name());
            case ALREADY_DEAD -> requireStatus(step, WorkflowStep.Status.DEAD, status.name());
            case NOT_OWNER -> requireNonTerminal(step, status.name());
            case LEASE_EXPIRED -> requireStatus(step, WorkflowStep.Status.LEASED, status.name());
            case RENEWED -> throw new IllegalArgumentException("RENEWED requires lease");
        }
    }

    private static void requireStatus(
            WorkflowStep step,
            WorkflowStep.Status expected,
            String operation) {
        Objects.requireNonNull(step, operation + " step");
        if (step.status() != expected) {
            throw new IllegalArgumentException(operation + " step status mismatch");
        }
    }

    private static void requireAbsent(WorkflowStep step, String operation) {
        if (step != null) {
            throw new IllegalArgumentException(operation + " must not return step");
        }
    }

    private static void requireNonTerminal(WorkflowStep step, String operation) {
        Objects.requireNonNull(step, operation + " step");
        if (step.status() == WorkflowStep.Status.COMPLETED
                || step.status() == WorkflowStep.Status.DEAD) {
            throw new IllegalArgumentException(operation + " must return non-terminal step");
        }
    }
}
