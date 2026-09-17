package com.czur.reference.application.port.identity;

import java.util.Objects;

/**
 * 身份端口可跨实现稳定映射的脱敏错误。
 *
 * <p>错误只保留枚举码和是否可重试；异常正文、Token、远端响应和完整地址
 * 不得通过该对象返回。HTTP 适配器应按 code 映射状态码和固定文案。</p>
 */
public record IdentityProviderError(Code code, boolean retryable) {
    public IdentityProviderError {
        Objects.requireNonNull(code, "code");
    }

    /** 身份验证阶段的最小稳定错误码。 */
    public enum Code {
        INVALID_REQUEST,
        INVALID_CREDENTIAL,
        CREDENTIAL_EXPIRED,
        IDENTITY_DISABLED,
        PERMISSION_DENIED,
        PROVIDER_UNAVAILABLE,
        INTERNAL
    }

    /** 返回可安全暴露的国际化键，不拼接 Provider 私有错误正文。 */
    public String messageKey() {
        return "ai_meeting.identity." + code.name().toLowerCase();
    }
}
