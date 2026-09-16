package com.czur.reference.application.port.model;

import java.util.Objects;

/**
 * Model Driver 调用的显式结果。
 *
 * <p>模型端口不把供应商异常直接抛给会议编排器。Driver 应把异常映射为
 * {@link ModelDriverError}，调用方通过 {@link #isSuccess()} 选择重试、降级或
 * 结束会议。这样 HTTP、WebSocket 和本地实现可以共享同一错误语义。</p>
 *
 * @param <T> 成功时的返回值类型
 */
public sealed interface ModelDriverResult<T>
        permits ModelDriverResult.Success, ModelDriverResult.VoidSuccess, ModelDriverResult.Failure {

    /** 创建携带非空值的成功结果。 */
    static <T> ModelDriverResult<T> success(T value) {
        return new Success<>(Objects.requireNonNull(value, "value"));
    }

    /** 创建无返回值操作的成功结果，避免其它泛型意外接受 null。 */
    static ModelDriverResult<Void> success() {
        return new VoidSuccess();
    }

    /** 创建失败结果。错误不能为 null。 */
    static <T> ModelDriverResult<T> failure(ModelDriverError error) {
        return new Failure<>(Objects.requireNonNull(error, "error"));
    }

    /** 返回本次调用是否成功。 */
    default boolean isSuccess() {
        return this instanceof Success<?> || this instanceof VoidSuccess;
    }

    /** 返回成功值；失败结果调用该方法会抛出 IllegalStateException。 */
    default T valueOrThrow() {
        if (this instanceof Success<T> success) {
            return success.value();
        }
        if (this instanceof VoidSuccess) {
            return null;
        }
        throw new IllegalStateException("model driver operation failed: " + errorOrThrow().code());
    }

    /** 返回错误；成功结果调用该方法会抛出 IllegalStateException。 */
    default ModelDriverError errorOrThrow() {
        if (this instanceof Failure<T> failure) {
            return failure.error();
        }
        throw new IllegalStateException("model driver operation succeeded");
    }

    /** 成功分支。 */
    record Success<T>(T value) implements ModelDriverResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    /** 无返回值操作的成功分支；只能通过 {@link #success()} 创建。 */
    record VoidSuccess() implements ModelDriverResult<Void> {
    }

    /** 失败分支。 */
    record Failure<T>(ModelDriverError error) implements ModelDriverResult<T> {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }
}
