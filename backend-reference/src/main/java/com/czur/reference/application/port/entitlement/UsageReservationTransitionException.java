package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/** Reservation 状态机拒绝变更时使用的稳定、无业务数据异常。 */
public final class UsageReservationTransitionException extends IllegalStateException {
    /** 调用方可稳定映射为冲突、过期或补偿失败的原因码。 */
    public enum ReasonCode {
        VERSION_CONFLICT,
        EXPECTED_MEETING_MISMATCH,
        RESERVATION_EXPIRED,
        ALREADY_BOUND,
        BIND_REQUIRED,
        TERMINAL_STATE
    }

    private final ReasonCode reasonCode;

    public UsageReservationTransitionException(ReasonCode reasonCode) {
        super("usage reservation transition rejected: "
                + Objects.requireNonNull(reasonCode, "reasonCode").name());
        this.reasonCode = reasonCode;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }
}
