package com.czur.reference.application.finalization;

import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 最终用量提交的稳定 operation identity 和 canonical 请求摘要。
 *
 * <p>operation key 只绑定逻辑会议，不绑定 Reservation seed。这样 Admission
 * 若错误地为同一会议带来另一份 Reservation，仍会命中同一幂等键，再由包含
 * {@code reservationId}、锁定版本、最终用量和完整来源事实的 request digest
 * 产生冲突，而不是旁路为第二次提交。</p>
 *
 * <p>全部摘要字段使用四字节大端长度前缀后再写入 UTF-8 字节，domain separator
 * 也使用相同编码。实现不读取数据库、当前 Reservation、时钟或凭据。动态文本在
 * 摘要前必须通过严格 UTF-8 校验，未配对 surrogate 不会被替换字符归一化。</p>
 */
public final class FinalizationOperationIdentity {
    /** 权益端口冻结的提交 scope。 */
    public static final String OPERATION_SCOPE = UsageOperationScopes.COMMIT;
    /** operation key 前缀；后接 64 位小写 SHA-256。 */
    public static final String IDEMPOTENCY_PREFIX = "finalization-usage-v1:";

    private static final String OPERATION_DOMAIN = "finalization-usage-operation/v1";
    private static final String REQUEST_DOMAIN = "finalization-usage-request/v1";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^finalization-usage-v1:[0-9a-f]{64}$");
    private static final Pattern REQUEST_DIGEST = Pattern.compile(
            "^sha256:[0-9a-f]{64}$");

    private final String operationScope;
    private final String idempotencyKey;
    private final String requestDigest;

    private FinalizationOperationIdentity(
            String operationScope,
            String idempotencyKey,
            String requestDigest) {
        this.operationScope = operationScope;
        this.idempotencyKey = idempotencyKey;
        this.requestDigest = requestDigest;
    }

    /**
     * 从 Admission 已锁定的完整提交材料生成 identity。
     *
     * @param expectedVersion Admission 行锁下观察并冻结的 Reservation version
     * @param sourceAcceptedAt durable input 的 PostgreSQL 微秒精度接管时间
     */
    public static FinalizationOperationIdentity commit(
            String deploymentScope,
            String subjectScope,
            String meetingId,
            String reservationId,
            long expectedVersion,
            UsageActual actual,
            String sourceInputBinding,
            String sourceInputDigest,
            Instant sourceAcceptedAt) {
        MeetingIdPolicy.requireValid(meetingId);
        requireText(deploymentScope);
        requireText(subjectScope);
        requireText(reservationId);
        requireText(sourceInputBinding);
        requireText(sourceInputDigest);
        requirePersistentInstant(sourceAcceptedAt);
        if (expectedVersion <= 0) {
            throw new IllegalArgumentException("Finalization operation identity is invalid");
        }
        Objects.requireNonNull(actual, "actual");

        String idempotencyKey = IDEMPOTENCY_PREFIX
                + digest(OPERATION_DOMAIN, meetingId);
        String requestDigest = "sha256:" + digest(
                REQUEST_DOMAIN,
                deploymentScope,
                subjectScope,
                OPERATION_SCOPE,
                idempotencyKey,
                reservationId,
                Long.toString(expectedVersion),
                actual.unit().name(),
                Long.toString(actual.amount()),
                sourceInputBinding,
                sourceInputDigest,
                sourceAcceptedAt.toString());
        return new FinalizationOperationIdentity(
                OPERATION_SCOPE, idempotencyKey, requestDigest);
    }

    /**
     * 恢复时重新计算并核对持久值，拒绝 scope、key 或 digest 被单独篡改。
     */
    public static FinalizationOperationIdentity restoreCommit(
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            String meetingId,
            String reservationId,
            long expectedVersion,
            UsageActual actual,
            String sourceInputBinding,
            String sourceInputDigest,
            Instant sourceAcceptedAt) {
        try {
            if (!OPERATION_SCOPE.equals(operationScope)
                    || idempotencyKey == null
                    || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                    || requestDigest == null
                    || !REQUEST_DIGEST.matcher(requestDigest).matches()) {
                throw invalid();
            }
            FinalizationOperationIdentity expected = commit(
                    deploymentScope,
                    subjectScope,
                    meetingId,
                    reservationId,
                    expectedVersion,
                    actual,
                    sourceInputBinding,
                    sourceInputDigest,
                    sourceAcceptedAt);
            if (!expected.idempotencyKey.equals(idempotencyKey)
                    || !expected.requestDigest.equals(requestDigest)) {
                throw invalid();
            }
            return expected;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public String operationScope() {
        return operationScope;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestDigest() {
        return requestDigest;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FinalizationOperationIdentity that)) {
            return false;
        }
        return operationScope.equals(that.operationScope)
                && idempotencyKey.equals(that.idempotencyKey)
                && requestDigest.equals(that.requestDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationScope, idempotencyKey, requestDigest);
    }

    /** 诊断不输出幂等键或摘要。 */
    @Override
    public String toString() {
        return "FinalizationOperationIdentity[operationScope=redacted"
                + ", idempotencyKey=redacted, requestDigestPresent=true]";
    }

    static String digest(String domain, String... fields) {
        MessageDigest digest = sha256();
        update(digest, domain);
        for (String field : fields) {
            update(digest, Objects.requireNonNull(field, "digest field"));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String checksum(byte[] content) {
        Objects.requireNonNull(content, "content");
        return "sha256:" + HexFormat.of().formatHex(sha256().digest(content));
    }

    static void requirePersistentInstant(Instant value) {
        Objects.requireNonNull(value, "instant");
        if (value.getNano() % 1_000 != 0
                || value.isBefore(Instant.parse("0000-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw invalid();
        }
    }

    static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        MeetingIdPolicy.utf8Length(value);
    }

    private static void update(MessageDigest digest, String value) {
        requireText(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Finalization operation identity is invalid");
    }
}
