package com.czur.reference.domain.capability;

/** 能力计划输入或 Profile 配置不合法时抛出的稳定异常。 */
public final class CapabilityPlanningException extends IllegalArgumentException {
    private final ReasonCode reasonCode;

    public CapabilityPlanningException(ReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }

    /** 对外可映射为统一错误码的原因。 */
    public enum ReasonCode {
        CAPABILITY_NOT_ALLOWED,
        INVALID_PROFILE,
        CYCLIC_DEPENDENCY
    }
}
