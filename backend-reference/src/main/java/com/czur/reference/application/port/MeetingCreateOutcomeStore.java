package com.czur.reference.application.port;

import java.util.Objects;
import java.util.Optional;

/**
 * authenticated-create 成功或确定失败终态的持久幂等端口。
 *
 * <p>记录仍复用 {@code idempotency_record} 的“opaque scope + key”唯一键，但不
 * 复用只支持成功 {@code MeetingCommandResult} 的旧 {@link IdempotencyStore}。
 * 实现必须原子争抢候选记录：只有 {@link ReservationStatus#ACQUIRED} 的调用方
 * 可以继续产生首次业务副作用；同摘要返回首次 Outcome，异摘要只返回冲突。</p>
 *
 * <p>该端口只保存终态。调用方不得提交可重试失败、存储暂时不可用或提交结果
 * 未知；这些限制由 {@link MeetingCreateFailure} 构造器再次强制执行。</p>
 */
public interface MeetingCreateOutcomeStore {
    /** 按完整 opaque scope 和幂等键读取已经提交的终态。 */
    Optional<OutcomeRecord> find(String scope, String key);

    /** 原子插入候选终态，或返回已经存在的赢家记录。 */
    Reservation reserve(OutcomeRecord candidate);

    /** 适配器对调用方暴露的稳定故障类别，不携带驱动动态异常正文。 */
    enum StoreErrorCode {
        STORAGE_UNAVAILABLE,
        OUTCOME_UNKNOWN,
        /** 已有持久事实损坏或 status/body 自相矛盾；重试不会自动恢复。 */
        CORRUPT_DATA
    }

    /**
     * Outcome Store 操作失败。
     *
     * <p>{@link StoreErrorCode#OUTCOME_UNKNOWN} 只允许在数据库 commit 调用已经开始、
     * 因而无法证明候选终态是否提交时使用；其它连接、语句和已确认回滚故障映射为
     * {@link StoreErrorCode#STORAGE_UNAVAILABLE}。持久事实违反冻结格式时返回不可
     * 重试的 {@link StoreErrorCode#CORRUPT_DATA}。异常不保留底层 cause。</p>
     */
    final class StoreException extends RuntimeException {
        private final StoreErrorCode code;

        public StoreException(StoreErrorCode code) {
            super("meeting create outcome store failure code="
                    + Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public StoreErrorCode code() {
            return code;
        }

        public boolean retryable() {
            return code != StoreErrorCode.CORRUPT_DATA;
        }

        @Override
        public String toString() {
            return "StoreException[code=" + code
                    + ", retryable=" + retryable() + ']';
        }
    }

    enum ReservationStatus {
        ACQUIRED,
        REPLAY,
        CONFLICT
    }

    /**
     * 与数据库唯一键绑定的完整稳定记录。
     *
     * <p>{@code scope} 可逆包含部署和主体，另外两个字符串也是敏感幂等材料；
     * 因此 {@link #toString()} 只显示 Outcome 类型。</p>
     */
    record OutcomeRecord(
            String scope,
            String key,
            String requestDigest,
            MeetingCreateOutcome outcome) {
        public OutcomeRecord {
            requireText("scope", scope);
            requireText("key", key);
            requireText("requestDigest", requestDigest);
            Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public String toString() {
            return "OutcomeRecord[outcomeType=" + outcomeType(outcome) + ']';
        }
    }

    /** 原子争抢结果；existing/candidate 记录本身不会通过默认输出泄露。 */
    record Reservation(ReservationStatus status, OutcomeRecord record) {
        public Reservation {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(record, "record");
        }

        @Override
        public String toString() {
            return "Reservation[status=" + status
                    + ", outcomeType=" + outcomeType(record.outcome()) + ']';
        }
    }

    private static String outcomeType(MeetingCreateOutcome outcome) {
        return outcome instanceof MeetingCreateOutcome.Success ? "SUCCESS" : "FAILURE";
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
