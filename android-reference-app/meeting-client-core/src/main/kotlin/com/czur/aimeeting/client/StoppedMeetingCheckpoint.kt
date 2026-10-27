package com.czur.aimeeting.client

import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.IOException
import java.util.UUID

/**
 * 停音源且尾部／冻结用量／选定纪要确认后的原场停止记录。仅续办 sealed 文件发布、
 * 同键 Stop 及实际 READY，不含实时队列／模型／当前账号／UI。凭据和文件只供私有存储。
 */
class StoppedMeetingCheckpoint private constructor(
    private val context: MeetingArtifactPublisher.Context,
    private val stableStopKey: String,
    private val phase: Phase,
    private val genTime: Long?,
    private val deliversResult: Boolean,
) {
    /** 顺序只表示已确认前缀，发布或 Stop 接受不等于实际 READY。 */
    enum class Phase { PUBLISH_PENDING, PUBLISHED, STOP_ACCEPTED, READY }
    init {
        require(stableStopKey.isNotBlank()) { "停止幂等键不能为空" }
        require(context.meetingId() == context.audio().meetingId() &&
            context.frozenAudioDurationMs() == context.audio().durationMs()) {
            "停止记录须使用同场 sealed 音频和原冻结用量"
        }
        require((phase.ordinal >= Phase.STOP_ACCEPTED.ordinal) == (genTime != null)) {
            "genTime 仅能在 Stop 实际接受后保存"
        }
    }
    fun checkpointId(): String = context.meetingId()
    fun context(): MeetingArtifactPublisher.Context = context
    /** 同场重试共用首次键，禁止日志。 */
    fun stableStopKey(): String = stableStopKey
    fun phase(): Phase = phase
    /** Stop 接受前为 null，之后固定为首次 ACK 的结果链接基准。 */
    fun genTime(): Long? = genTime
    /** 原场交付策略：false 表示 Stop 成功后只收口到 ENDED，不读取结果清单。 */
    fun deliversResult(): Boolean = deliversResult
    fun published(): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context, stableStopKey, Phase.PUBLISHED, null, deliversResult)
    fun stopAccepted(acceptedAtMs: Long): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context, stableStopKey, Phase.STOP_ACCEPTED, acceptedAtMs, deliversResult)
    fun ready(): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context, stableStopKey, Phase.READY, genTime, deliversResult)
    fun withAccessToken(accessToken: String): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context.withAccessToken(accessToken), stableStopKey, phase, genTime,
            deliversResult)

    /**
     * 同场保存公共进度时核对原文件／格式／用量／服务归属／Stop 键，保留更先进阶段。
     * token 使用 update 中正常身份流程批准的值，不能倒退既有 ACK 或改 genTime。
     * Store 另外保留自己的登记 ACK／prepareId，不用公共记录覆盖私有附加字段。
     */
    @Throws(IOException::class)
    fun merge(update: StoppedMeetingCheckpoint): StoppedMeetingCheckpoint {
        val a = context.audio()
        val b = update.context.audio()
        if (checkpointId() != update.checkpointId() || a.file() != b.file() || a.format() != b.format() ||
            a.encodedBytes() != b.encodedBytes() || a.capturedPcmBytes() != b.capturedPcmBytes() ||
            context.frozenAudioDurationMs() != update.context.frozenAudioDurationMs() ||
            context.serverUrl() != update.context.serverUrl() || context.clientIdentity() != update.context.clientIdentity() ||
            stableStopKey != update.stableStopKey || deliversResult != update.deliversResult ||
            genTime != null && update.genTime != null && genTime != update.genTime)
            throw IOException("同场停止记录的原事实不一致")
        val acknowledged = if (phase.ordinal >= update.phase.ordinal) this else update
        return if (acknowledged.context.accessToken() == update.context.accessToken()) acknowledged
            else acknowledged.withAccessToken(update.context.accessToken())
    }
    override fun toString(): String = "StoppedMeetingCheckpoint[phase=$phase, originalFacts=redacted]"
    companion object {
        /** 仅尾部确认且 sealed 事实读取成功后生成一次停止键；默认按主动交付收口。 */
        @JvmStatic fun pending(context: MeetingArtifactPublisher.Context,
            deliversResult: Boolean = true): StoppedMeetingCheckpoint =
            StoppedMeetingCheckpoint(context, UUID.randomUUID().toString(), Phase.PUBLISH_PENDING, null,
                deliversResult)
        /**
         * 私有 Store 读回原事实，不重读当前账号／登录／音源。文件由 App 保持不变；
         * originalConfig 只提取原 URL／Bearer／ClientIdentity，不保留或调用 Provider。
         * 旧记录没有交付策略字段时按主动交付续办。
         */
        @JvmStatic fun restore(audio: SealedAudioArtifact, frozenAudioDurationMs: Long,
            originalConfig: MeetingClientConfig, stableStopKey: String, phase: Phase, genTime: Long?,
            deliversResult: Boolean = true): StoppedMeetingCheckpoint =
            StoppedMeetingCheckpoint(MeetingArtifactPublisher.Context(audio.meetingId(), audio,
                frozenAudioDurationMs, originalConfig), stableStopKey, phase, genTime, deliversResult)
    }
}
