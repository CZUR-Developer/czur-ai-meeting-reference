package com.czur.aimeeting.model

/**
 * 标准字幕片段的一次修订，用于即时显示和最终产物；不包含模型原始消息。
 * Adapter 输出时 start/end 是本次模型任务收到音频的相对毫秒；SDK 仅转换一次，
 * App／会议 core 收到时已经是本场完整录音的相对毫秒，后端保存同一时间。
 */
class TranscriptEvent @JvmOverloads constructor(
    private val segmentId: String,
    private val revision: Int,
    private val startMs: Long,
    private val endMs: Long,
    private val language: String,
    private val text: String,
    private val isFinal: Boolean,
    private val sessionSpeakerId: String? = null,
) {
    /** 修订间稳定的片段 ID。 */
    fun segmentId(): String = segmentId
    /** 从 1 开始递增的修订号。 */
    fun revision(): Int = revision
    /** Adapter 边界为本模型任务相对起点；SDK 向上交付后为本场完整录音起点，毫秒。 */
    fun startMs(): Long = startMs
    /** 与 startMs 同一时基的终点，毫秒；Adapter 不添加连接续接偏移。 */
    fun endMs(): Long = endMs
    /** 片段语言代码。 */
    fun language(): String = language
    /** 当前完整文字。 */
    fun text(): String = text
    /** 模型是否确认不再修订。 */
    fun isFinal(): Boolean = isFinal
    /** 同场临时发言人，缺失为 null，不是跨场身份。 */
    fun sessionSpeakerId(): String? = sessionSpeakerId
    /** 本场未授权发言人能力时移除临时标识。 */
    fun withoutSpeaker(): TranscriptEvent = if (sessionSpeakerId == null) this else
        TranscriptEvent(segmentId, revision, startMs, endMs, language, text, isFinal, null)
}
