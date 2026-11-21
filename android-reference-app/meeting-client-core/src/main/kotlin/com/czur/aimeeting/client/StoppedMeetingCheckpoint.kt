package com.czur.aimeeting.client

import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.IOException
import java.util.UUID

/**
 * 停音源且尾部／冻结用量／选定纪要确认后的原场停止记录。仅续办 sealed 文件发布、
 * 同键 Stop 及实际 READY，不含实时队列／模型／当前账号／UI。凭据和文件只供私有存储。
 *
 * 另保存会前语言快照与“纪要语言是否已上报”：进程在发布成功、上报语言前退出时，
 * 停止续办仍能补发服务端纪要请求；已经上报的场次不会重复请求。旧记录缺这两个字段，
 * 按 null／false 兼容，等价于修复前“续办不补发”的行为。
 */
class StoppedMeetingCheckpoint private constructor(
    private val context: MeetingArtifactPublisher.Context,
    private val stableStopKey: String,
    private val phase: Phase,
    private val genTime: Long?,
    private val deliversResult: Boolean,
    private val summaryLanguages: List<String>?,
    private val summaryReported: Boolean,
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
            "genTime 仅能在 Stop 受理或原场成功终态得到确认后保存"
        }
        require(summaryLanguages == null || summaryLanguages.size in 1..2) {
            "停止记录的纪要语言快照只能有1～2项"
        }
    }
    fun checkpointId(): String = context.meetingId()
    fun context(): MeetingArtifactPublisher.Context = context
    /** 同场重试共用首次键，禁止日志。 */
    fun stableStopKey(): String = stableStopKey
    fun phase(): Phase = phase
    /** 结果访问时间在 Stop ACK 或同场终态对账成功时冻结。 */
    fun genTime(): Long? = genTime
    /** 原场交付策略：false 表示 Stop 成功后只收口到 ENDED，不读取结果清单。 */
    fun deliversResult(): Boolean = deliversResult
    /**
     * 会前语言快照（有序1～2项，首项为主语言）。null 表示本场不要纪要，或旧记录没保存；
     * 两者都按“续办不补发纪要请求”处理。
     */
    fun summaryLanguages(): List<String>? = summaryLanguages?.let { java.util.List.copyOf(it) }
    /** 是否已向服务端上报过纪要语言；只有上报成功并再次落盘后为 true。 */
    fun summaryReported(): Boolean = summaryReported && summaryLanguages != null
    fun published(): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context, stableStopKey, Phase.PUBLISHED, null, deliversResult,
            summaryLanguages, summaryReported)
    fun stopAccepted(acceptedAtMs: Long): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context, stableStopKey, Phase.STOP_ACCEPTED, acceptedAtMs, deliversResult,
            summaryLanguages, summaryReported)
    fun ready(): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context, stableStopKey, Phase.READY, genTime, deliversResult,
            summaryLanguages, summaryReported)
    /** 原键 Stop 没有 ACK，但原 Owner 的状态及已发布清单均已核验。 */
    internal fun reconciledReady(observedGenTime: Long): StoppedMeetingCheckpoint {
        check(phase == Phase.PUBLISHED && deliversResult)
        return StoppedMeetingCheckpoint(context, stableStopKey, Phase.READY, observedGenTime, true,
            summaryLanguages, summaryReported)
    }
    fun withAccessToken(accessToken: String): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context.withAccessToken(accessToken), stableStopKey, phase, genTime,
            deliversResult, summaryLanguages, summaryReported)
    /** 首次创建停止记录时写入会前语言快照；本场不要纪要时传 null，保留旧记录语义。 */
    fun withSummaryLanguages(languages: List<String>?): StoppedMeetingCheckpoint =
        copyFacts(languages?.let { java.util.List.copyOf(it) }, summaryReported && languages != null)
    /** 纪要语言上报成功并持久化后置位；失败不置位，留给下次续办重试。 */
    fun withSummaryReported(): StoppedMeetingCheckpoint = copyFacts(summaryLanguages, true)

    private fun copyFacts(languages: List<String>?, reported: Boolean): StoppedMeetingCheckpoint =
        StoppedMeetingCheckpoint(context, stableStopKey, phase, genTime, deliversResult,
            languages, reported)

    /**
     * 同场保存公共进度时核对原文件／格式／用量／服务归属／Stop 键，保留更先进阶段。
     * token 使用 update 中正常身份流程批准的值，不能倒退既有 ACK 或改 genTime。
     * 纪要语言是原场事实：两边都有值时不得不同；已上报状态只增不减。
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
            genTime != null && update.genTime != null && genTime != update.genTime ||
            summaryLanguages != null && update.summaryLanguages != null &&
            summaryLanguages != update.summaryLanguages)
            throw IOException("同场停止记录的原事实不一致")
        val acknowledged = if (phase.ordinal >= update.phase.ordinal) this else update
        val languages = acknowledged.summaryLanguages ?: update.summaryLanguages
        val reported = languages != null && (summaryReported || update.summaryReported)
        var merged = if (acknowledged.summaryLanguages == languages &&
            acknowledged.summaryReported == reported) acknowledged
            else acknowledged.copyFacts(languages, reported)
        if (merged.context.accessToken() != update.context.accessToken())
            merged = merged.withAccessToken(update.context.accessToken())
        return merged
    }
    override fun toString(): String = "StoppedMeetingCheckpoint[phase=$phase, originalFacts=redacted]"
    companion object {
        /** 仅尾部确认且 sealed 事实读取成功后生成一次停止键；默认按主动交付收口。 */
        @JvmStatic fun pending(context: MeetingArtifactPublisher.Context,
            deliversResult: Boolean = true): StoppedMeetingCheckpoint =
            StoppedMeetingCheckpoint(context, UUID.randomUUID().toString(), Phase.PUBLISH_PENDING, null,
                deliversResult, null, false)
        /**
         * 私有 Store 读回原事实，不重读当前账号／登录／音源。文件由 App 保持不变；
         * originalConfig 只提取原 URL／Bearer／ClientIdentity，不保留或调用 Provider。
         * 旧记录没有交付策略字段时按主动交付续办。
         */
        @JvmStatic fun restore(audio: SealedAudioArtifact, frozenAudioDurationMs: Long,
            originalConfig: MeetingClientConfig, stableStopKey: String, phase: Phase, genTime: Long?,
            deliversResult: Boolean = true): StoppedMeetingCheckpoint =
            restore(audio, frozenAudioDurationMs, originalConfig, stableStopKey, phase, genTime,
                deliversResult, null, false)
        /**
         * 带会前语言快照的读回入口：旧记录没有这两个字段时由调用方传 null／false，
         * 与上面的七参重载行为一致；不改变已编译调用方的七参 JVM 签名。
         */
        @JvmStatic fun restore(audio: SealedAudioArtifact, frozenAudioDurationMs: Long,
            originalConfig: MeetingClientConfig, stableStopKey: String, phase: Phase, genTime: Long?,
            deliversResult: Boolean, summaryLanguages: List<String>?,
            summaryReported: Boolean): StoppedMeetingCheckpoint =
            StoppedMeetingCheckpoint(MeetingArtifactPublisher.Context(audio.meetingId(), audio,
                frozenAudioDurationMs, originalConfig), stableStopKey, phase, genTime, deliversResult,
                summaryLanguages?.let { java.util.List.copyOf(it) }, summaryReported)
    }
}
