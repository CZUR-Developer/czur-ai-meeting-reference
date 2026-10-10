package com.czur.reference.application.port.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次 Driver 调用的诊断、截止时间和取消上下文。
 *
 * <p>Driver 必须把 deadline 和 cancellation 传递给底层 SDK/HTTP 调用；本
 * 参考实现会在进入每个端口方法时检查它们。operationId 只用于诊断，不能
 * 代替 complete/abort 的幂等键。上下文不保存任何凭据。</p>
 */
public record ModelOperationContext(
        String operationId,
        Instant deadline,
        CancellationSignal cancellation) {

    public ModelOperationContext {
        requireText("operationId", operationId);
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    /** 创建不会立即超时且未取消的测试上下文。生产调用方应传入真实 deadline。 */
    public static ModelOperationContext withoutCancellation(String operationId, Instant deadline) {
        return new ModelOperationContext(operationId, deadline, () -> false);
    }

    /** 当前调用是否已超过截止时间。 */
    public boolean expired(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(deadline);
    }

    /** 当前调用是否收到取消信号。 */
    public boolean cancelled() {
        return cancellation.isCancelled();
    }

    /** 可被应用层或测试实现的取消信号。 */
    @FunctionalInterface
    public interface CancellationSignal {
        boolean isCancelled();
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
