package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingState;

import java.time.Instant;
import java.util.Objects;

/**
 * 单场会议的权威查询快照。
 *
 * <p>查询适配器必须从持久化事实源组装本对象，不能从 Worker 内存状态猜测。
 * 会议生命周期使用现有 {@link MeetingState}，输入接管和 Manifest 使用各自
 * 的公开状态集合。{@link FinalizationFact} 的 receipt 规则由其自身统一维护，
 * 因此查询、Stop 和未来删除适配器不会复制一套可不一致的 null 语义。</p>
 */
public record MeetingStatus(
        String meetingId,
        MeetingState state,
        FinalizationFact finalization,
        ManifestStatus manifestState,
        Instant updatedAt) {

    public MeetingStatus {
        requireText("meetingId", meetingId);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(finalization, "finalization");
        Objects.requireNonNull(manifestState, "manifestState");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 便于传输适配器按 OpenAPI 的字段拆分输入状态和 receipt。 */
    public InputState inputState() {
        return finalization.inputState();
    }

    /** 便于传输适配器读取可为空的 receipt；不会生成新的凭据。 */
    public String finalizationReceipt() {
        return finalization.finalizationReceipt();
    }

    /**
     * 将详情状态投影为不含 receipt 的列表摘要。
     *
     * <p>{@code title} 由查询适配器从服务端已发布的总结标题里取；没有总结时传 {@code null}，
     * 不在这一层猜测或从正文派生。</p>
     */
    public MeetingSummary summary(Instant createdAt, String title) {
        return new MeetingSummary(
                meetingId, title, state, inputState(), manifestState, createdAt, updatedAt);
    }

    /** 隐藏 receipt 正文；状态日志只能看到是否具备接管事实。 */
    @Override
    public String toString() {
        return "MeetingStatus[meetingId=" + meetingId
                + ", state=" + state
                + ", inputState=" + inputState()
                + ", manifestState=" + manifestState
                + ", receiptPresent=" + finalization.isAccepted() + ']';
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
