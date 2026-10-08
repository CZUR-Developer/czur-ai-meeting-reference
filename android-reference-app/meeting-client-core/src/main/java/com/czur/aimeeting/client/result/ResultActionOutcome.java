package com.czur.aimeeting.client.result;

import java.util.Objects;

/**
 * 一次查看／通知动作的结果，与MeetingResult.Status及会议READY状态独立。
 * 失败只显示message并允许宿主手动再执行，不触发模型／Stop／总结或计费。
 * 不包含原始异常、token、URL、收件人或完整正文。
 */
public record ResultActionOutcome(String actionId, Status status, String message) {
    public enum Status { SUCCEEDED, FAILED }
    public ResultActionOutcome {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }
    public static ResultActionOutcome success(String id, String message) {
        return new ResultActionOutcome(id, Status.SUCCEEDED, message);
    }
    public static ResultActionOutcome failure(String id, String message) {
        return new ResultActionOutcome(id, Status.FAILED, message);
    }
}
