package com.czur.reference.application.command;

import com.czur.reference.domain.meeting.MeetingAggregate;

import java.util.Objects;

/** 应用命令的稳定返回值；传输层可将其映射为 REST 或实时事件。 */
public record MeetingCommandResult(Operation operation, MeetingAggregate.MeetingSnapshot meeting) {
    public MeetingCommandResult {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(meeting, "meeting");
    }

    public enum Operation {
        CREATED,
        STOP_ACCEPTED
    }
}
