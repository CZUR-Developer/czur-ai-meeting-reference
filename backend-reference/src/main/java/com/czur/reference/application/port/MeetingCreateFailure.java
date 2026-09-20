package com.czur.reference.application.port;

import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork.ErrorCode;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 可以作为 authenticated-create 幂等终态持久化的确定失败。
 *
 * <p>该值只保存稳定错误码和固定的 {@code retryable=false}，不保存动态异常、
 * 主体、幂等键、请求摘要、Grant、Reservation 或补偿状态。暂时存储故障和提交
 * 结果未知仍可能通过重试或事实回查恢复，因此绝不能被固化为失败终态；幂等摘要
 * 冲突是当前请求与既有终态的比较结果，也不是可供另一次请求重放的业务终态。
 * commit 之前抛出的运行时异常与已损坏的内部事实同样不具备稳定业务语义，
 * {@link ErrorCode#INTERNAL} 只能作为当次调用的 fail-closed 结果，不得永久冻结为
 * authenticated-create Outcome。</p>
 */
public record MeetingCreateFailure(ErrorCode code, boolean retryable) {
    private static final Set<ErrorCode> NON_PERSISTABLE_CODES = EnumSet.of(
            ErrorCode.IDEMPOTENCY_CONFLICT,
            ErrorCode.STORAGE_UNAVAILABLE,
            ErrorCode.OUTCOME_UNKNOWN,
            ErrorCode.INTERNAL);

    public MeetingCreateFailure {
        Objects.requireNonNull(code, "code");
        if (retryable) {
            throw new IllegalArgumentException(
                    "persisted meeting create failure must not be retryable");
        }
        if (NON_PERSISTABLE_CODES.contains(code)) {
            throw new IllegalArgumentException(
                    "error code cannot be persisted as meeting create failure");
        }
    }

    /** 结果不包含动态正文；显式实现用于冻结未来新增字段时的日志边界。 */
    @Override
    public String toString() {
        return "MeetingCreateFailure[code=" + code + ", retryable=false]";
    }
}
