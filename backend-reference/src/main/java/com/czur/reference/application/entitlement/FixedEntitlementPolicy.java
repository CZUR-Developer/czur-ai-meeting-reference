package com.czur.reference.application.entitlement;

import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementPolicy;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 固定配置 的纯 Java 权益策略。
 *
 * <p>{@link #authorizeStart(AuthorizeStartRequest)} 只读取固定策略并签发短期 Grant，
 * 不写数据库、不登记 usage operation，也不消耗额度。它只接受配置中完全一致的
 * deployment、Profile 及 revision，只允许 {@link UsageUnit#MEETING_DURATION_MS}，
 * 并把申请能力原样作为授权能力，绝不会借授权过程额外扩权。</p>
 *
 * <p>{@link #prepareReservation(ReserveUsageRequest)} 校验 Grant 和预占边界，
 * 并由服务端策略生成截止时间及初始快照，仍不持久化、不提供幂等。后续 JDBC
 * Provider 必须先处理持久幂等，只把首次请求交给该方法，并以数据库唯一约束
 * 保存返回值；重放不得再次调用策略计算截止时间。</p>
 *
 * <p>时钟和不透明 ID 端口均由装配层注入。日志只记录固定原因码和结果类别，
 * 不记录主体、幂等键、摘要、Grant/Reservation ID 或请求正文。</p>
 */
public final class FixedEntitlementPolicy implements EntitlementPolicy {
    private static final Logger LOGGER = Logger.getLogger(
            FixedEntitlementPolicy.class.getName());

    private final FixedEntitlementPolicyConfig config;
    private final Clock clock;
    private final EntitlementIdGenerator idGenerator;

    public FixedEntitlementPolicy(
            FixedEntitlementPolicyConfig config,
            Clock clock,
            EntitlementIdGenerator idGenerator) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        LOGGER.log(Level.INFO, "固定配置 权益策略已创建");
    }

    /**
     * 对本次开会请求执行无副作用授权。
     *
     * @return 成功时返回只包含本次申请能力的短期 Grant；失败时返回稳定错误码
     */
    @Override
    public EntitlementProviderResult<MeetingGrant> authorizeStart(
            AuthorizeStartRequest request) {
        if (request == null) {
            return reject(EntitlementProviderError.Code.INVALID_REQUEST, "REQUEST_MISSING");
        }
        if (!config.deploymentScope().equals(request.deploymentScope())) {
            return reject(EntitlementProviderError.Code.ENTITLEMENT_DENIED,
                    "DEPLOYMENT_NOT_ALLOWED");
        }
        if (!config.profileId().equals(request.profileId())
                || config.profileRevision() != request.profileRevision()) {
            return reject(EntitlementProviderError.Code.ENTITLEMENT_DENIED,
                    "PROFILE_NOT_ALLOWED");
        }
        if (request.requestedCapabilities().isEmpty()
                || !config.supportedCapabilities().containsAll(request.requestedCapabilities())) {
            return reject(EntitlementProviderError.Code.CAPABILITY_NOT_GRANTED,
                    "CAPABILITY_NOT_ALLOWED");
        }
        if (request.estimatedUsage().unit() != UsageUnit.MEETING_DURATION_MS) {
            return reject(EntitlementProviderError.Code.INVALID_REQUEST,
                    "USAGE_UNIT_NOT_ALLOWED");
        }
        if (request.estimatedUsage().amount() > config.maxDurationMs()) {
            return reject(EntitlementProviderError.Code.ENTITLEMENT_DENIED,
                    "DURATION_LIMIT_EXCEEDED");
        }

        Instant issuedAt = clock.instant();
        Instant expiresAt;
        try {
            expiresAt = issuedAt.plus(config.grantTtl());
        } catch (DateTimeException | ArithmeticException invalidClockRange) {
            return reject(EntitlementProviderError.Code.INTERNAL, "GRANT_TIME_OVERFLOW");
        }
        String grantId;
        try {
            grantId = requireGeneratedId("grantId", idGenerator.newGrantId());
        } catch (RuntimeException generatorFailure) {
            return reject(EntitlementProviderError.Code.INTERNAL, "GRANT_ID_UNAVAILABLE");
        }
        MeetingGrant grant = new MeetingGrant(
                grantId,
                config.deploymentScope(),
                request.subject().subjectScope(),
                config.profileId(),
                config.profileRevision(),
                request.requestedCapabilities(),
                request.requestedCapabilities(),
                config.maxDurationMs(),
                issuedAt,
                expiresAt);
        LOGGER.log(Level.FINE, "固定配置 权益授权成功 result=GRANT_ISSUED");
        return EntitlementProviderResult.success(grant);
    }

    /**
     * 校验首次预占并创建版本为零的 Reservation 快照。
     *
     * <p>本方法只读取一次注入时钟，并将截止时间确定为“当前时间加 reservation
     * TTL”与 Grant 截止时间中的较早者。截止时间完全属于 Provider 生成的服务端
     * 事实，调用方不能指定。调用方必须把返回快照和 usage operation 放入同一个
     * 数据库事务；本方法本身不产生持久副作用。</p>
     */
    @Override
    public EntitlementProviderResult<UsageReservation> prepareReservation(
            ReserveUsageRequest request) {
        if (request == null) {
            return reject(EntitlementProviderError.Code.INVALID_REQUEST, "REQUEST_MISSING");
        }
        MeetingGrant grant = request.grant();
        if (!matchesConfiguredGrant(grant, request)) {
            return reject(EntitlementProviderError.Code.ENTITLEMENT_DENIED,
                    "GRANT_POLICY_MISMATCH");
        }
        if (request.estimate().unit() != UsageUnit.MEETING_DURATION_MS) {
            return reject(EntitlementProviderError.Code.INVALID_REQUEST,
                    "USAGE_UNIT_NOT_ALLOWED");
        }
        if (request.estimate().amount() > config.maxDurationMs()
                || request.estimate().amount() > grant.maxDurationMs()) {
            return reject(EntitlementProviderError.Code.ENTITLEMENT_DENIED,
                    "DURATION_LIMIT_EXCEEDED");
        }

        Instant now = clock.instant();
        if (grant.issuedAt().isAfter(now)) {
            return reject(EntitlementProviderError.Code.ENTITLEMENT_DENIED,
                    "GRANT_NOT_ACTIVE");
        }
        if (!hasAllowedGrantTtl(grant)) {
            return reject(EntitlementProviderError.Code.ENTITLEMENT_DENIED,
                    "GRANT_TTL_INVALID");
        }
        if (grant.isExpiredAt(now)) {
            return reject(EntitlementProviderError.Code.GRANT_EXPIRED, "GRANT_EXPIRED");
        }
        Instant policyExpiry;
        try {
            policyExpiry = now.plus(config.reservationTtl());
        } catch (DateTimeException | ArithmeticException invalidClockRange) {
            return reject(EntitlementProviderError.Code.INTERNAL, "RESERVATION_TIME_OVERFLOW");
        }
        Instant reservationExpiresAt = policyExpiry.isBefore(grant.expiresAt())
                ? policyExpiry : grant.expiresAt();

        String reservationId;
        try {
            reservationId = requireGeneratedId(
                    "reservationId", idGenerator.newReservationId());
        } catch (RuntimeException generatorFailure) {
            return reject(EntitlementProviderError.Code.INTERNAL, "RESERVATION_ID_UNAVAILABLE");
        }
        UsageReservation reservation = UsageReservation.reserved(
                reservationId,
                grant,
                request.expectedMeetingId(),
                request.estimate(),
                now,
                reservationExpiresAt);
        LOGGER.log(Level.FINE, "固定配置 权益预占已准备 result=RESERVATION_PREPARED");
        return EntitlementProviderResult.success(reservation);
    }

    private boolean matchesConfiguredGrant(MeetingGrant grant, ReserveUsageRequest request) {
        return config.deploymentScope().equals(grant.deploymentScope())
                && request.deploymentScope().equals(grant.deploymentScope())
                && request.subjectScope().equals(grant.subjectScope())
                && config.profileId().equals(grant.profileId())
                && config.profileRevision() == grant.profileRevision()
                && !grant.requestedCapabilities().isEmpty()
                && config.supportedCapabilities().containsAll(grant.requestedCapabilities())
                && grant.requestedCapabilities().equals(grant.grantedCapabilities())
                && grant.maxDurationMs() <= config.maxDurationMs();
    }

    private boolean hasAllowedGrantTtl(MeetingGrant grant) {
        try {
            return !grant.expiresAt().isAfter(grant.issuedAt().plus(config.grantTtl()));
        } catch (DateTimeException | ArithmeticException invalidGrantRange) {
            return false;
        }
    }

    private static String requireGeneratedId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("entitlement ID generator returned blank " + name);
        }
        return value;
    }

    private static <T> EntitlementProviderResult<T> reject(
            EntitlementProviderError.Code code, String reasonCode) {
        LOGGER.log(Level.FINE, "固定配置 权益请求拒绝 reason={0}", reasonCode);
        return EntitlementProviderResult.failure(new EntitlementProviderError(code, false));
    }
}
