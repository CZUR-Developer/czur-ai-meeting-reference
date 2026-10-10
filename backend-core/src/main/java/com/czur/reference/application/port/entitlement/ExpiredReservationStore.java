package com.czur.reference.application.port.entitlement;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 到期且未绑定 Reservation 的领取与 lease 释放端口。
 *
 * <p>本端口只提供持久化原语，不启动线程、不轮询、也不直接取消 Reservation。
 * P2-T14 Worker 负责按周期调用 {@link #claimExpired(ClaimRequest)}，再使用稳定
 * 补偿幂等键请求权益 Provider 取消。处理暂时失败时可以调用
 * {@link #release(LeaseToken, Instant)} 提前释放本次 lease。</p>
 *
 * <p>可领取记录必须同时满足：V3 Reservation、状态 RESERVED、尚未绑定会议、
 * {@code expiresAt <= now}，并且没有 lease 或旧 lease 已满足
 * {@code leaseUntil <= now}。数据库实现必须使用行锁加 SKIP LOCKED、条件更新或
 * 等价原子机制；不得先查询后无条件写入。</p>
 *
 * <p>每次成功领取都生成新的不透明 token 并递增 {@code leaseVersion}。释放时
 * 必须同时校验部署、主体、Reservation、owner、token、version、当前状态和
 * lease 有效期；旧 owner 的迟到释放不得清除新 owner 的 lease。</p>
 */
public interface ExpiredReservationStore {

    /**
     * 原子领取一个部署范围内、当前已经到期且未绑定的 Reservation。
     * 返回顺序固定为 {@code expiresAt, reservationId} 升序，同一批次不得重复。
     */
    ClaimBatch claimExpired(ClaimRequest request);

    /**
     * 提前释放当前有效 lease。
     *
     * <p>重复调用、旧 token、到期 token、已绑定或已进入终态都返回稳定状态，
     * 不抛出携带数据库正文的异常，也不得修改新 owner 已经取得的 lease。
     * {@code releasedAt >= lease.expiresAt} 必须返回 {@link ReleaseStatus#LEASE_EXPIRED}，
     * 截止时刻本身不再属于旧 owner。</p>
     */
    ReleaseResult release(LeaseToken lease, Instant releasedAt);

    /** 一次批量领取的完整输入；{@code now} 的边界采用小于等于即到期。 */
    record ClaimRequest(
            String deploymentScope,
            String leaseOwner,
            Instant now,
            Duration leaseDuration,
            int limit) {
        public ClaimRequest {
            requireText("deploymentScope", deploymentScope);
            requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            try {
                now.plus(leaseDuration);
            } catch (RuntimeException invalidRange) {
                throw new IllegalArgumentException("leaseDuration exceeds Instant range");
            }
        }

        /** 返回本次 lease 的确定截止时间，供适配器统一写入所有领取行。 */
        public Instant leaseUntil() {
            return now.plus(leaseDuration);
        }
    }

    /**
     * 一次 claim 返回的不透明 fencing 凭据。
     *
     * <p>该类型故意不是 record，避免默认 {@code toString()} 输出 lease token。
     * Worker 只能原样传回 {@link #release(LeaseToken, Instant)}；日志中只能记录
     *稳定结果码，不能记录 token。</p>
     */
    final class LeaseToken {
        private final String deploymentScope;
        private final String subjectScope;
        private final String reservationId;
        private final String owner;
        private final String token;
        private final Instant claimedAt;
        private final Instant expiresAt;
        private final long leaseVersion;

        public LeaseToken(
                String deploymentScope,
                String subjectScope,
                String reservationId,
                String owner,
                String token,
                Instant claimedAt,
                Instant expiresAt,
                long leaseVersion) {
            this.deploymentScope = requireText("deploymentScope", deploymentScope);
            this.subjectScope = requireText("subjectScope", subjectScope);
            this.reservationId = requireText("reservationId", reservationId);
            this.owner = requireText("owner", owner);
            this.token = requireText("token", token);
            this.claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
            this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(claimedAt)) {
                throw new IllegalArgumentException("expiresAt must be after claimedAt");
            }
            if (leaseVersion <= 0) {
                throw new IllegalArgumentException("leaseVersion must be positive");
            }
            this.leaseVersion = leaseVersion;
        }

        public String deploymentScope() {
            return deploymentScope;
        }

        public String subjectScope() {
            return subjectScope;
        }

        public String reservationId() {
            return reservationId;
        }

        public String owner() {
            return owner;
        }

        public String token() {
            return token;
        }

        public Instant claimedAt() {
            return claimedAt;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        public long leaseVersion() {
            return leaseVersion;
        }

        /** 截止时刻本身视为过期，与 release 的数据库条件保持一致。 */
        public boolean isExpiredAt(Instant at) {
            Objects.requireNonNull(at, "at");
            return !at.isBefore(expiresAt);
        }

        /** 诊断输出固定脱敏，不暴露 token 或业务范围。 */
        @Override
        public String toString() {
            return "ExpiredReservationLeaseToken[REDACTED]";
        }
    }

    /** 已被当前 lease 原子领取的完整 Reservation 快照。 */
    record ClaimedReservation(UsageReservation reservation, LeaseToken lease) {
        public ClaimedReservation {
            Objects.requireNonNull(reservation, "reservation");
            Objects.requireNonNull(lease, "lease");
            if (!reservation.deploymentScope().equals(lease.deploymentScope())
                    || !reservation.subjectScope().equals(lease.subjectScope())
                    || !reservation.reservationId().equals(lease.reservationId())) {
                throw new IllegalArgumentException("reservation and lease scope must match");
            }
            if (reservation.state() != UsageReservation.State.RESERVED
                    || reservation.isBound()
                    || !reservation.isExpiredAt(lease.claimedAt())) {
                throw new IllegalArgumentException(
                        "claim requires an expired, unbound RESERVED reservation");
            }
        }
    }

    /**
     * 一次 claim 的不可变批量结果。
     *
     * <p>构造时对输入集合做防御复制，并校验所有元素属于本次请求；调用方不能
     * 通过修改原列表或返回列表改变已经领取的结果。</p>
     */
    record ClaimBatch(ClaimRequest request, List<ClaimedReservation> claims) {
        public ClaimBatch {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(claims, "claims");
            List<ClaimedReservation> copy = new ArrayList<>(claims.size());
            Set<List<String>> reservationScopes = new HashSet<>();
            ClaimedReservation previous = null;
            for (ClaimedReservation claim : claims) {
                Objects.requireNonNull(claim, "claim");
                if (!request.deploymentScope().equals(claim.lease().deploymentScope())
                        || !request.leaseOwner().equals(claim.lease().owner())
                        || !request.now().equals(claim.lease().claimedAt())
                        || !request.leaseUntil().equals(claim.lease().expiresAt())) {
                    throw new IllegalArgumentException("claim does not belong to request");
                }
                if (!reservationScopes.add(List.of(
                        claim.lease().subjectScope(), claim.lease().reservationId()))) {
                    throw new IllegalArgumentException("claim batch contains duplicate reservation");
                }
                if (previous != null) {
                    int expiryOrder = previous.reservation().expiresAt()
                            .compareTo(claim.reservation().expiresAt());
                    int idOrder = previous.reservation().reservationId()
                            .compareTo(claim.reservation().reservationId());
                    if (expiryOrder > 0 || (expiryOrder == 0 && idOrder > 0)) {
                        throw new IllegalArgumentException("claims must use deterministic order");
                    }
                }
                copy.add(claim);
                previous = claim;
            }
            if (copy.size() > request.limit()) {
                throw new IllegalArgumentException("claims exceed request limit");
            }
            claims = List.copyOf(copy);
        }
    }

    /**
     * release 的稳定业务结果；适配器不得返回数据库异常正文。
     *
     * <p>分类顺序固定为：范围内不存在返回 NOT_FOUND；记录已绑定或进入终态
     * 返回 TERMINAL_OR_BOUND；owner/token/version 与当前 lease 不一致返回
     * NOT_OWNER；凭据匹配但 {@code releasedAt >= leaseUntil} 返回 LEASE_EXPIRED；
     * 只有剩余情况可以清空 lease 并返回 RELEASED。</p>
     */
    enum ReleaseStatus {
        RELEASED,
        NOT_FOUND,
        NOT_OWNER,
        LEASE_EXPIRED,
        TERMINAL_OR_BOUND
    }

    /**
     * release 的稳定结果。
     *
     * <p>{@code reservation} 是释放判断后的当前业务快照；NOT_FOUND 时必须为
     * null，其它状态必须返回数据库中的当前快照。该对象永远不包含 lease
     * token。</p>
     */
    record ReleaseResult(ReleaseStatus status, UsageReservation reservation) {
        public ReleaseResult {
            Objects.requireNonNull(status, "status");
            if (status == ReleaseStatus.NOT_FOUND && reservation != null) {
                throw new IllegalArgumentException("NOT_FOUND must not include reservation");
            }
            if (status != ReleaseStatus.NOT_FOUND && reservation == null) {
                throw new IllegalArgumentException("release status requires reservation");
            }
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
