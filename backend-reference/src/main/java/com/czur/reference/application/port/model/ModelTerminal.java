package com.czur.reference.application.port.model;

import java.util.Objects;

/** Driver 会话停止向 Orchestrator 产生事件后的终态通知。 */
public record ModelTerminal(Status status, ModelDriverError error) {
    public ModelTerminal {
        Objects.requireNonNull(status, "status");
        if (status == Status.COMPLETED && error != null) {
            throw new IllegalArgumentException("completed terminal must not have an error");
        }
        if (status != Status.COMPLETED && error == null) {
            throw new IllegalArgumentException("failed/cancelled terminal requires an error");
        }
    }

    /** Driver 输入完成或被取消后的终态。 */
    public enum Status {
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
