package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/**
 * 权益端口可稳定映射、可安全返回的错误。
 *
 * <p>不得把价格公式、订单详情、动态异常正文或完整 Provider 响应放入错误。
 * 是否允许开会只通过稳定 code 表达。</p>
 */
public record EntitlementProviderError(Code code, boolean retryable) {
    public EntitlementProviderError {
        Objects.requireNonNull(code, "code");
    }

    public enum Code {
        INVALID_REQUEST,
        PERMISSION_DENIED,
        ENTITLEMENT_DENIED,
        CAPABILITY_NOT_GRANTED,
        GRANT_EXPIRED,
        RESERVATION_EXPIRED,
        RESERVATION_NOT_FOUND,
        RESERVATION_SCOPE_MISMATCH,
        RESERVATION_STATE_CONFLICT,
        VERSION_CONFLICT,
        IDEMPOTENCY_CONFLICT,
        PROVIDER_UNAVAILABLE,
        INTERNAL
    }

    public String messageKey() {
        return "ai_meeting.entitlement." + code.name().toLowerCase();
    }
}
