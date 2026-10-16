package com.czur.aimeeting.client;

import com.czur.aimeeting.model.SealedAudioArtifact;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * 已停止采音、尾事件／冻结用量／选定总结均已确认后的原场收尾记录。
 * 只续办原 sealed 文件的发布、同键 Stop 和实际 READY，不包含实时事件队列、
 * 模型会话、当前账号 Provider 或界面状态；未完成前半段的会议不能生成此记录。
 * 原访问凭据与文件位置仅供应用私有存储和原场请求使用，禁止输出到日志或公共协议。
 */
public final class StoppedMeetingCheckpoint {
    /** 顺序仅表达已确认的收尾前缀；发布确认和 Stop 接受均不等于实际 READY。 */
    public enum Phase { PUBLISH_PENDING, PUBLISHED, STOP_ACCEPTED, READY }

    private final MeetingArtifactPublisher.Context context;
    private final String stableStopKey;
    private final Phase phase;
    private final Long genTime;

    private StoppedMeetingCheckpoint(MeetingArtifactPublisher.Context context,
            String stableStopKey, Phase phase, Long genTime) {
        this.context = Objects.requireNonNull(context, "context");
        this.stableStopKey = Objects.requireNonNull(stableStopKey, "stableStopKey");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.genTime = genTime;
        if (stableStopKey.isBlank()) throw new IllegalArgumentException("停止幂等键不能为空");
        if (!context.meetingId().equals(context.audio().meetingId())
                || context.frozenAudioDurationMs() != context.audio().durationMs())
            throw new IllegalArgumentException("停止记录须使用同场 sealed 音频和原冻结用量");
        if ((phase.ordinal() >= Phase.STOP_ACCEPTED.ordinal()) != (genTime != null))
            throw new IllegalArgumentException("genTime 仅能在 Stop 实际接受后保存");
    }

    /** 仅协调器在原尾部全部确认、sealed 事实读取成功之后调用；同场只生成一次键。 */
    static StoppedMeetingCheckpoint pending(MeetingArtifactPublisher.Context context) {
        return new StoppedMeetingCheckpoint(context, UUID.randomUUID().toString(),
                Phase.PUBLISH_PENDING, null);
    }

    /**
     * 应用私有 Store 的读回工厂。传入先前保存的原场事实，不读取当前身份、重新登录、
     * 开音源或检查媒体；文件仍须由应用保持不变。阶段和 genTime 必须与原 ACK 一致，
     * 不能根据文件存在、PUT 成功或当前账号信息推导完成状态。
     * originalConfig 由先前保存的原 URL／Bearer／ClientIdentity 重建；只提取这三个
     * 已冻结值，不保留或调用其 Provider。发布前的候选记录 genTime 为 null；
     * Stop 接受后须使用首次保存的同一个值。
     */
    public static StoppedMeetingCheckpoint restore(SealedAudioArtifact audio,
            long frozenAudioDurationMs, MeetingClientConfig originalConfig,
            String stableStopKey, Phase phase, Long genTime) {
        var context = new MeetingArtifactPublisher.Context(audio.meetingId(), audio,
                frozenAudioDurationMs, originalConfig);
        return new StoppedMeetingCheckpoint(context, stableStopKey, phase, genTime);
    }

    /** 记录 ID 就是原会议 ID；不额外创建另一套会议编号。 */
    public String checkpointId() { return context.meetingId(); }
    public MeetingArtifactPublisher.Context context() { return context; }
    /** 首次 Stop 与所有同场重试共用；禁止日志输出。 */
    public String stableStopKey() { return stableStopKey; }
    public Phase phase() { return phase; }
    /** Stop 接受前为 null，接受后为原结果链接的固定时间基准。 */
    public Long genTime() { return genTime; }

    StoppedMeetingCheckpoint published() {
        return new StoppedMeetingCheckpoint(context, stableStopKey, Phase.PUBLISHED, null);
    }
    StoppedMeetingCheckpoint stopAccepted(long acceptedAtMs) {
        return new StoppedMeetingCheckpoint(context, stableStopKey, Phase.STOP_ACCEPTED, acceptedAtMs);
    }
    StoppedMeetingCheckpoint ready() {
        return new StoppedMeetingCheckpoint(context, stableStopKey, Phase.READY, genTime);
    }

    /** 只替换正常身份扩展已批准的访问材料，原会议／文件／用量／ACK／停止键保持。 */
    StoppedMeetingCheckpoint withAccessToken(String accessToken) {
        return new StoppedMeetingCheckpoint(context.withAccessToken(accessToken),
                stableStopKey, phase, genTime);
    }

    /**
     * Store 在同一记录上保存公共进度时复用此合并规则：先核对原场／文件／用量／
     * 服务归属／Stop 键，再保留更先进的 ACK 前缀。访问凭据是可续签材料，使用 update
     * 中正常身份流程已批准的值，不当作不可变原事实。协调器重试先读 Store 最新记录，
     * 再交身份扩展，不能拿较旧内存候选回写旧凭据。旧候选重入不得倒退阶段，Stop 后
     * 已保存的 genTime 不得替换。此方法只合并公共字段，Store 仍须保留自身已有的
     * 私有登记 ACK、发布计划等附加字段，不能拿公共记录整份覆盖私有存储。
     *
     * @param update 同场新的公共进度；可以是同进程重试时仍持有的较早候选
     * @return 保留原事实和最先进已确认阶段的记录
     * @throws IOException 两份记录的不可变原事实或已保存 genTime 不一致
     */
    public StoppedMeetingCheckpoint merge(StoppedMeetingCheckpoint update) throws IOException {
        Objects.requireNonNull(update, "update");
        var a = context.audio();
        var b = update.context.audio();
        if (!checkpointId().equals(update.checkpointId())
                || !a.file().equals(b.file()) || a.format() != b.format()
                || a.encodedBytes() != b.encodedBytes()
                || a.capturedPcmBytes() != b.capturedPcmBytes()
                || context.frozenAudioDurationMs() != update.context.frozenAudioDurationMs()
                || !context.serverUrl().equals(update.context.serverUrl())
                || !context.clientIdentity().equals(update.context.clientIdentity())
                || !stableStopKey.equals(update.stableStopKey)
                || genTime != null && update.genTime != null && !genTime.equals(update.genTime))
            throw new IOException("同场停止记录的原事实不一致");
        var acknowledged = phase.ordinal() >= update.phase.ordinal() ? this : update;
        return acknowledged.context.accessToken().equals(update.context.accessToken()) ? acknowledged
                : acknowledged.withAccessToken(update.context.accessToken());
    }

    @Override public String toString() {
        return "StoppedMeetingCheckpoint[phase=" + phase + ", originalFacts=redacted]";
    }
}
