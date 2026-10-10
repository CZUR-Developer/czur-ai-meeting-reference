package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/** 创建会议前用于权益预占的标准用量估计。 */
public record UsageEstimate(UsageUnit unit, long amount) {
    public UsageEstimate {
        Objects.requireNonNull(unit, "unit");
        if (amount <= 0) {
            throw new IllegalArgumentException("estimated amount must be positive");
        }
    }
}
