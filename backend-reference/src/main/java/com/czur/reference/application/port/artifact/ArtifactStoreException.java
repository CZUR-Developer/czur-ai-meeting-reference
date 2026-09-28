package com.czur.reference.application.port.artifact;

import java.util.Objects;

/**
 * Artifact Store 边界的稳定、脱敏失败。
 *
 * <p>实现必须把文件系统、SDK 或对象存储异常映射为这里的关闭式错误码，不能把包含
 * Bucket、文件路径、对象 Key、正文或凭据的底层 message/cause 传播给编排器。每个
 * code 的 retryable 属性在枚举中固定，调用方不能按部署实现自行改变重试含义。</p>
 */
public final class ArtifactStoreException extends Exception {
    private final Code code;
    private final String diagnosticId;

    public ArtifactStoreException(Code code, String diagnosticId) {
        super("artifact store operation failed: " + Objects.requireNonNull(code, "code"));
        this.code = code;
        if (diagnosticId == null || diagnosticId.isBlank()) {
            throw new IllegalArgumentException("diagnosticId must not be blank");
        }
        this.diagnosticId = diagnosticId;
    }

    public Code code() {
        return code;
    }

    public boolean retryable() {
        return code.retryable();
    }

    public String diagnosticId() {
        return diagnosticId;
    }

    @Override
    public String toString() {
        return "ArtifactStoreException[code=" + code
                + ", retryable=" + retryable()
                + ", diagnosticIdPresent=true]";
    }

    /** Artifact Store 的关闭式错误码和固定重试属性。 */
    public enum Code {
        NOT_FOUND(false),
        NOT_READY(true),
        DELETED(false),
        CONTENT_TOO_LARGE(false),
        SIZE_MISMATCH(false),
        CHECKSUM_MISMATCH(false),
        INVALID_INTENT(false),
        STORAGE_CONFLICT(false),
        STORAGE_CORRUPT(false),
        ATOMIC_PUBLISH_UNSUPPORTED(false),
        UNSAFE_FILESYSTEM(false),
        STORAGE_UNAVAILABLE(true),
        COMMIT_UNKNOWN(true);

        private final boolean retryable;

        Code(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
