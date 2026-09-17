package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/**
 * 会议最终收口后形成的累积用量事实。
 *
 * <p>值为零是合法的，例如会议已建立但在收到有效音频前结束。调用方必须从
 * 已持久化的最终事实构造该对象，重试时不得重新按增量事件计算。</p>
 */
public record UsageActual(UsageUnit unit, long amount) {
    public UsageActual {
        Objects.requireNonNull(unit, "unit");
        if (amount < 0) {
            throw new IllegalArgumentException("actual amount must not be negative");
        }
    }
}
