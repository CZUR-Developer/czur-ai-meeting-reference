package com.czur.reference.application.entitlement;

import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.LeaseAwareCancelReservationRequest;
import com.czur.reference.application.port.entitlement.LeaseAwareReservationCanceller;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 到期未绑定 Reservation 的一次性清理 Worker。
 *
 * <p>Worker 只提供同步的 {@link #runOnce()} 入口，不创建线程、定时器或进程内
 * 队列。外部调度器可以按自己的生命周期调用该入口；进程退出后，下一次调用
 * 仍从 {@link ExpiredReservationStore} 的持久事实重新领取。</p>
 *
 * <p>清理分为两个明确的持久化边界：先由 Store 原子领取 lease，再通过独立的
 * {@link LeaseAwareReservationCanceller} 提交取消。取消请求携带稳定的业务幂等
 * 身份，但不把 owner、token 或 lease 截止时间写进幂等摘要。这样相同业务结果
 * 可以安全重放，而旧 Worker 的迟到取消仍会被 lease 栅栏拒绝。</p>
 *
 * <p>只有权益 Provider 明确返回可重试的暂时失败，且当前时间仍在本次 lease
 * 有效窗口内时，Worker 才会调用 {@link ExpiredReservationStore#release} 释放
 * lease。取消端口一旦返回成功（首次提交或幂等重放），Worker 绝不再 release；
 * release 失败、claim 失败等持久化异常直接向调用方传播，不会被转换成业务成功。</p>
 */
public final class ExpiredReservationCleanupWorker {
    private static final Logger LOGGER = Logger.getLogger(
            ExpiredReservationCleanupWorker.class.getName());
    private static final String DEFAULT_OPERATION_KEY_PREFIX = "reservation-cleanup-v1:";
    private static final String DEFAULT_REASON_CODE = "EXPIRED_UNBOUND";

    private final ExpiredReservationStore store;
    private final LeaseAwareReservationCanceller canceller;
    private final Config config;

    /**
     * 创建 Reservation 清理 Worker。
     *
     * @param store 负责领取和释放 lease 的持久端口
     * @param canceller 负责在同一持久化边界内执行 lease-aware 取消的端口
     * @param config 本轮领取和稳定取消身份配置
     */
    public ExpiredReservationCleanupWorker(
            ExpiredReservationStore store,
            LeaseAwareReservationCanceller canceller,
            Config config) {
        this.store = Objects.requireNonNull(store, "store");
        this.canceller = Objects.requireNonNull(canceller, "canceller");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 执行一轮领取、取消和必要的 lease 释放。
     *
     * <p>claim 或 release 的持久化异常会原样抛出；本方法不会为了继续处理其它
     * Reservation 而吞掉这类异常。权益 Provider 返回的 {@code PROVIDER_UNAVAILABLE}
     * 等可重试业务结果则按 {@link ItemStatus#RELEASED_FOR_RETRY} 分类，并在 lease
     * 仍有效时释放后交给下一轮调度。</p>
     *
     * @return 本轮领取数量及每条记录的稳定结果分类
     */
    public RunResult runOnce() {
        Instant claimAt = config.clock().instant();
        ExpiredReservationStore.ClaimBatch batch = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        config.deploymentScope(), config.leaseOwner(), claimAt,
                        config.leaseDuration(), config.batchLimit()));
        if (batch.claims().isEmpty()) {
            LOGGER.log(Level.FINE, "Reservation 清理 Worker 本轮没有可领取记录 owner={0}",
                    config.leaseOwner());
            return new RunResult(0, List.of());
        }

        List<ItemResult> results = new ArrayList<>(batch.claims().size());
        for (ExpiredReservationStore.ClaimedReservation claim : batch.claims()) {
            results.add(process(claim));
        }
        return new RunResult(batch.claims().size(), results);
    }

    private ItemResult process(ExpiredReservationStore.ClaimedReservation claim) {
        UsageReservation reservation = claim.reservation();
        ExpiredReservationStore.LeaseToken lease = claim.lease();
        if (reservation.state() != UsageReservation.State.RESERVED || reservation.isBound()) {
            // 合规 Store 不会返回此类 claim；该防御分支避免不合规适配器误取消终态。
            LOGGER.log(Level.WARNING, "Reservation 清理跳过终态或已绑定记录");
            return new ItemResult(
                    reservation.reservationId(), ItemStatus.SKIPPED_TERMINAL, "TERMINAL_OR_BOUND");
        }

        CancelReservationRequest cancel = new CancelReservationRequest(
                reservation.deploymentScope(),
                reservation.subjectScope(),
                UsageOperationScopes.CANCEL,
                operationKey(reservation),
                operationDigest(reservation, config.reasonCode()),
                reservation.reservationId(),
                reservation.expectedMeetingId(),
                reservation.version(),
                config.reasonCode());
        LeaseAwareCancelReservationRequest request = new LeaseAwareCancelReservationRequest(
                cancel, lease);

        // 不捕获 RuntimeException：适配器的连接、事务或损坏事实异常必须让调度器看到。
        EntitlementProviderResult<UsageOperationResult> result =
                canceller.cancelReservationWithLease(request);
        if (result.isSuccess()) {
            UsageReservation cancelled = result.valueOrThrow().reservation();
            if (cancelled.state() != UsageReservation.State.CANCELLED) {
                throw new IllegalStateException("lease-aware cancel returned non-cancelled result");
            }
            // 端口故意只暴露稳定业务结果；首次提交与幂等重放均视为清理成功，
            // 不再 release，避免在取消已经提交后清除后续事实的 lease。
            LOGGER.log(Level.INFO, "Reservation 清理取消完成或稳定重放");
            return new ItemResult(
                    reservation.reservationId(), ItemStatus.CANCELLED, "CANCELLED_OR_REPLAYED");
        }

        EntitlementProviderError error = result.errorOrThrow();
        Instant observedAt = config.clock().instant();
        if (isLeaseExpired(lease, observedAt)) {
            LOGGER.log(Level.WARNING, "Reservation 清理 lease 已过期");
            return new ItemResult(
                    reservation.reservationId(), ItemStatus.LEASE_EXPIRED, "LEASE_EXPIRED");
        }

        if (isLeaseFenceRejection(error)) {
            LOGGER.log(Level.WARNING, "Reservation 清理 lease 栅栏拒绝");
            return new ItemResult(
                    reservation.reservationId(), ItemStatus.LEASE_LOST, "LEASE_NOT_OWNER");
        }
        if (!error.retryable()) {
            LOGGER.log(Level.WARNING, "Reservation 清理业务拒绝 reason={0}", error.code());
            return new ItemResult(
                    reservation.reservationId(), ItemStatus.BUSINESS_REJECTED,
                    "PROVIDER_" + error.code().name());
        }

        // 取消尚未成功，且 lease 仍有效，才允许释放给下一轮；release 的持久化
        // 异常不在这里捕获，必须传播而不是伪装为暂时失败。
        ExpiredReservationStore.ReleaseResult released = store.release(lease, observedAt);
        return switch (released.status()) {
            case RELEASED -> {
                LOGGER.log(Level.INFO, "Reservation 清理暂时失败，lease 已释放重试");
                yield new ItemResult(
                        reservation.reservationId(), ItemStatus.RELEASED_FOR_RETRY,
                        "PROVIDER_UNAVAILABLE");
            }
            case LEASE_EXPIRED -> new ItemResult(
                    reservation.reservationId(), ItemStatus.LEASE_EXPIRED, "LEASE_EXPIRED");
            case NOT_OWNER -> new ItemResult(
                    reservation.reservationId(), ItemStatus.LEASE_LOST, "LEASE_NOT_OWNER");
            case TERMINAL_OR_BOUND -> new ItemResult(
                    reservation.reservationId(), ItemStatus.SKIPPED_TERMINAL, "TERMINAL_OR_BOUND");
            case NOT_FOUND -> new ItemResult(
                    reservation.reservationId(), ItemStatus.LEASE_LOST, "NOT_FOUND");
        };
    }

    private String operationKey(UsageReservation reservation) {
        return config.operationKeyPrefix() + reservation.reservationId();
    }

    private static String operationDigest(UsageReservation reservation, String reasonCode) {
        String canonical = String.join("\n",
                "reservation-cleanup-v1",
                reservation.deploymentScope(),
                reservation.subjectScope(),
                reservation.reservationId(),
                reservation.expectedMeetingId(),
                Long.toString(reservation.version()),
                reasonCode);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isLeaseExpired(
            ExpiredReservationStore.LeaseToken lease, Instant observedAt) {
        return !observedAt.isBefore(lease.expiresAt());
    }

    private static boolean isLeaseFenceRejection(EntitlementProviderError error) {
        return error.code() == EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT
                || error.code() == EntitlementProviderError.Code.RESERVATION_NOT_FOUND;
    }

    /** 一轮 Worker 的固定领取与幂等配置。 */
    public record Config(
            String deploymentScope,
            String leaseOwner,
            Clock clock,
            Duration leaseDuration,
            int batchLimit,
            String operationKeyPrefix,
            String reasonCode) {
        public Config {
            requireText("deploymentScope", deploymentScope);
            requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (batchLimit <= 0) {
                throw new IllegalArgumentException("batchLimit must be positive");
            }
            operationKeyPrefix = operationKeyPrefix == null || operationKeyPrefix.isBlank()
                    ? DEFAULT_OPERATION_KEY_PREFIX : operationKeyPrefix;
            requireText("operationKeyPrefix", operationKeyPrefix);
            requireReasonCode(reasonCode == null ? DEFAULT_REASON_CODE : reasonCode);
            reasonCode = reasonCode == null ? DEFAULT_REASON_CODE : reasonCode;
            try {
                clock.instant().plus(leaseDuration);
            } catch (RuntimeException invalidRange) {
                throw new IllegalArgumentException("leaseDuration exceeds Instant range", invalidRange);
            }
        }

        /** 参考 Worker 使用的保守默认配置。 */
        public static Config defaults(String deploymentScope, String leaseOwner) {
            return new Config(
                    deploymentScope, leaseOwner, Clock.systemUTC(), Duration.ofSeconds(30),
                    20, DEFAULT_OPERATION_KEY_PREFIX, DEFAULT_REASON_CODE);
        }
    }

    /** 一条已领取 Reservation 的稳定处理结果，不包含 lease token。 */
    public record ItemResult(String reservationId, ItemStatus status, String reasonCode) {
        public ItemResult {
            requireText("reservationId", reservationId);
            Objects.requireNonNull(status, "status");
            requireText("reasonCode", reasonCode);
        }
    }

    /** 一次无后台线程运行的汇总结果。 */
    public record RunResult(int claimedCount, List<ItemResult> items) {
        public RunResult {
            if (claimedCount < 0) {
                throw new IllegalArgumentException("claimedCount must not be negative");
            }
            Objects.requireNonNull(items, "items");
            if (items.size() > claimedCount) {
                throw new IllegalArgumentException("items cannot exceed claimedCount");
            }
            items = List.copyOf(items);
        }

        public boolean idle() {
            return claimedCount == 0;
        }
    }

    /** Worker 的稳定业务结果分类。 */
    public enum ItemStatus {
        CANCELLED,
        RELEASED_FOR_RETRY,
        LEASE_EXPIRED,
        LEASE_LOST,
        BUSINESS_REJECTED,
        SKIPPED_TERMINAL
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireReasonCode(String value) {
        requireText("reasonCode", value);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character == '_' || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9'))) {
                throw new IllegalArgumentException("reasonCode must be an uppercase stable code");
            }
        }
    }
}
