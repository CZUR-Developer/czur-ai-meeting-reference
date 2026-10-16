package com.czur.aimeeting.client

import com.czur.aimeeting.model.SummaryResult

/** 仅包含 Manifest 中 READY 的产物，章节以 artifactTypes 为准；PDF／图片绘制属于 App。 */
data class MeetingResultContent(
    private var artifactTypes: Set<String>,
    private val transcriptText: String,
    private var transcriptSegments: List<FinalTranscriptSegment>,
    private var translations: List<FinalTranslation>,
    private val summary: SummaryResult?,
    private var presentationFrames: List<PresentationFrame>,
) {
    init {
        artifactTypes = java.util.Set.copyOf(artifactTypes)
        transcriptSegments = java.util.List.copyOf(transcriptSegments)
        translations = java.util.List.copyOf(translations)
        presentationFrames = java.util.List.copyOf(presentationFrames)
    }
    fun artifactTypes(): Set<String> = artifactTypes
    fun transcriptText(): String = transcriptText
    fun transcriptSegments(): List<FinalTranscriptSegment> = transcriptSegments
    fun translations(): List<FinalTranslation> = translations
    fun summary(): SummaryResult? = summary
    fun presentationFrames(): List<PresentationFrame> = presentationFrames
}
