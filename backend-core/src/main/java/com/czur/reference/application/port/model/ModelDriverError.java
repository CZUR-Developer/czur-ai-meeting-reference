package com.czur.reference.application.port.model;

import java.util.Objects;

/**
 * 模型 Driver 对外暴露的稳定错误。
 *
 * <p>错误中只允许放可安全展示的 code、messageKey 和诊断 ID；供应商 HTTP
 * 响应、Task ID、Token、音频和完整模型文本必须留在 Driver 的脱敏诊断系统，
 * 不得填入本对象。</p>
 */
public record ModelDriverError(
        Code code,
        boolean retryable,
        String messageKey,
        String diagnosticId) {

    public ModelDriverError {
        Objects.requireNonNull(code, "code");
        requireText("messageKey", messageKey);
        requireText("diagnosticId", diagnosticId);
    }

    /** 模型端口的最小稳定错误码集合。 */
    public enum Code {
        INVALID_ARGUMENT,
        UNAUTHORIZED,
        UNSUPPORTED,
        CONFLICT,
        OUT_OF_ORDER,
        DUPLICATE,
        BACKPRESSURED,
        INPUT_SEQUENCE_MISMATCH,
        NOT_READY,
        ALREADY_TERMINAL,
        CANCELLED,
        TIMEOUT,
        PROVIDER_UNAVAILABLE,
        PROVIDER_ERROR,
        CALLBACK_FAILED,
        INTERNAL
    }

    /** 创建无需暴露供应商细节的固定诊断错误。 */
    public static ModelDriverError of(Code code, boolean retryable, String diagnosticId) {
        return new ModelDriverError(code, retryable, "ai_meeting.model_driver." + code.name().toLowerCase(),
                diagnosticId);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
