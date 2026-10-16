package com.czur.aimeeting.client

/** 最终字幕段，无实时 revision/final；sessionSpeakerId 缺失时为 null。 */
data class FinalTranscriptSegment(
    private val segmentId: String,
    private val startMs: Long,
    private val endMs: Long,
    private val language: String,
    private val text: String,
    private val sessionSpeakerId: String?,
) {
    fun segmentId(): String = segmentId
    fun startMs(): Long = startMs
    fun endMs(): Long = endMs
    fun language(): String = language
    fun text(): String = text
    fun sessionSpeakerId(): String? = sessionSpeakerId
}
