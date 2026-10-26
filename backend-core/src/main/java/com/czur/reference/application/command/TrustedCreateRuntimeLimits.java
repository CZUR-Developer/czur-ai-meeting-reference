package com.czur.reference.application.command;

/**
 * 参考部署在服务端装配时提供的完整 Create 运行限制。
 *
 * <p>此类型与控制 Provider 能力开关的 {@code DeploymentLimits} 分开，也不从
 * 客户端请求或模型返回中解码。构造时复用公共响应 Limits 的正数边界；会议
 * 时长最终仍须与首次授权 Profile 上限取较小值，不能与 UsageEstimate 混用。</p>
 *
 * @param maxMeetingDurationMs 部署允许的单场最大时长，单位毫秒
 * @param maxAudioFrameBytes 单次音频帧可接受的最大字节数
 * @param eventReplayWindow 事件重放窗口数量
 * @param maxReplayAudioMs 可选的音频补拉最大时长，null 表示未声明
 * @param heartbeatIntervalMs 客户端心跳间隔，单位毫秒
 */
public record TrustedCreateRuntimeLimits(
        long maxMeetingDurationMs,
        int maxAudioFrameBytes,
        int eventReplayWindow,
        Long maxReplayAudioMs,
        int heartbeatIntervalMs) {
    public TrustedCreateRuntimeLimits {
        new CreateMeetingResponseFact.Limits(maxMeetingDurationMs, maxAudioFrameBytes,
                eventReplayWindow, maxReplayAudioMs, heartbeatIntervalMs);
    }

    /** 把授权时长上限收紧为最终响应限制，永不放大已授权 Profile。 */
    public CreateMeetingResponseFact.Limits authorizedLimits(long authorizedMaxDurationMs) {
        if (authorizedMaxDurationMs <= 0) {
            throw new IllegalArgumentException("authorizedMaxDurationMs must be positive");
        }
        return new CreateMeetingResponseFact.Limits(
                Math.min(authorizedMaxDurationMs, maxMeetingDurationMs),
                maxAudioFrameBytes, eventReplayWindow, maxReplayAudioMs,
                heartbeatIntervalMs);
    }
}
