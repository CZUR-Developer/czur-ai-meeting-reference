package com.czur.aimeeting.client.display

import com.czur.aimeeting.model.AudioDirectionSample
import com.czur.aimeeting.model.SegmentStartedEvent
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import java.util.Locale
import java.util.OptionalLong
import java.util.logging.Logger

/** 单场显示状态，页面与悬浮窗共用；模型修订只更新同一行，计时统一使用单调时钟。 */
class MeetingDisplayState {
    class Segment private constructor(private val event: TranscriptEvent) {
        private val id = event.segmentId()
        private val revision = event.revision()
        private val text = event.text()
        private val language = event.language()
        private val isFinal = event.isFinal()
        fun id(): String = id
        fun text(): String = text
        fun language(): String = language
        fun revision(): Int = revision
        fun isFinal(): Boolean = isFinal
        fun alpha(): Float = if (isFinal) 1f else .8f
        internal fun event(): TranscriptEvent = event
        companion object { internal fun from(event: TranscriptEvent): Segment = Segment(event) }
    }
    /** 空栏表示原文／译文未到，左右由本场路由决定。 */
    class BilingualRow internal constructor(private val id: String, private val left: String, private val right: String,
        private val leftAlpha: Float, private val rightAlpha: Float, private val basis: DisplayRouter.Basis) {
        fun id(): String = id
        fun left(): String = left
        fun right(): String = right
        fun leftAlpha(): Float = leftAlpha
        fun rightAlpha(): Float = rightAlpha
        fun basis(): DisplayRouter.Basis = basis
        fun languageFallback(): Boolean = basis == DisplayRouter.Basis.MODEL_LANGUAGE ||
            basis == DisplayRouter.Basis.TEXT_LANGUAGE || basis == DisplayRouter.Basis.LANGUAGE_UNRESOLVED
    }
    private class TranslationSegment(val event: TranslationEvent) {
        val sourceId = event.sourceSegmentId()
        val revision = event.revision()
        val sourceLanguage = event.sourceLanguage()
        val text = event.text()
        val isFinal = event.isFinal()
        fun alpha(): Float = if (isFinal) 1f else .8f
    }
    private val segments = LinkedHashMap<String, Segment>()
    private val translations = LinkedHashMap<String, TranslationSegment>()
    /** 每段等待期只保留最新完整修订，起点不随 partial 推迟。 */
    private val pendingLanguages = LinkedHashMap<String, PendingLanguage>()
    private lateinit var router: DisplayRouter
    private var bilingual = false
    private var swapped = false
    private var languageA = "zh-CN"
    private var languageB = "en-US"
    private var transcript = "等待字幕…"
    private var latestTranscriptSegmentId: String? = null
    private var finalTranscript = false
    private var startedAtElapsedMs = 0L
    private var waitForModelLanguage = false
    private var finalizing = false

    fun start(elapsedRealtimeMs: Long) { start(elapsedRealtimeMs, false, "zh-CN", "en-US") }
    /** 会前语言／模式冻结，互译从首帧保持双栏。 */
    fun start(elapsedRealtimeMs: Long, bilingual: Boolean, languageA: String, languageB: String) {
        segments.clear()
        translations.clear()
        pendingLanguages.clear()
        this.bilingual = bilingual
        this.languageA = languageA
        this.languageB = languageB
        swapped = false
        router = DisplayRouter(languageA, languageB)
        transcript = "等待字幕…"
        latestTranscriptSegmentId = null
        finalTranscript = false
        startedAtElapsedMs = elapsedRealtimeMs
        waitForModelLanguage = false
        finalizing = false
    }
    fun apply(event: TranscriptEvent) { apply(event, System.nanoTime() / 1_000_000L) }
    /** 实际 Adapter 在首条字幕前声明是否可迟到语种；不按厂商或 JSON 推断。 */
    fun setWaitForModelLanguage(enabled: Boolean) { waitForModelLanguage = enabled }
    /** 句开始绑定方向，没有事件时在首条文字兜底。 */
    fun onSegmentStarted(event: SegmentStartedEvent) { router.onSegmentStarted(event.segmentId()) }
    /**
     * nowMs 与本场同一单调时基。只在有效模糊 DOA 区等待，最多1.5秒；明确方向／无 DOA
     * 直接显示。等待只影响呈现，标准原始事件可立即保存。
     */
    fun apply(event: TranscriptEvent, nowMs: Long) {
        releaseExpiredLanguages(nowMs)
        val previous = segments[event.segmentId()]
        if (previous != null && (event.revision() < previous.revision() ||
            previous.isFinal() && event.revision() <= previous.revision())) return
        val pending = pendingLanguages[event.segmentId()]
        if (pending != null && event.revision() < pending.event.revision()) return
        router.onSegmentStarted(event.segmentId())
        if (bilingual && waitForModelLanguage && router.isLanguageBased(event.segmentId()) &&
            router.existingRoute(event.segmentId()) == null && !router.hasConfiguredLanguage(event.language())) {
            if (finalizing) router.forceLocalFallback(event.segmentId(), event.text())
            else {
                pendingLanguages[event.segmentId()] = PendingLanguage(event, pending?.startedAtMs ?: nowMs)
                if (pending == null) LOG.fine("启动待语种显示窗口 segmentId=${event.segmentId()} deadlineMs=${nowMs + LANGUAGE_WAIT_MS}")
                return
            }
        }
        pendingLanguages.remove(event.segmentId())
        applyVisible(event)
    }
    private fun applyVisible(event: TranscriptEvent) {
        if (bilingual) router.route(event.segmentId(), event.language(), event.text())
        segments[event.segmentId()] = Segment.from(event)
        transcript = event.text()
        latestTranscriptSegmentId = event.segmentId()
        finalTranscript = event.isFinal()
    }
    fun apply(event: TranslationEvent) { apply(event, System.nanoTime() / 1_000_000L) }
    /** 同一 translationId 原位修订，可信源语种立即释放同段等待。 */
    fun apply(event: TranslationEvent, nowMs: Long) {
        releaseExpiredLanguages(nowMs)
        val previous = translations[event.translationId()]
        if (previous != null && (event.revision() < previous.revision ||
            previous.isFinal && event.revision() <= previous.revision)) return
        if (bilingual && router.hasConfiguredLanguage(event.sourceLanguage())) {
            router.route(event.sourceSegmentId(), event.sourceLanguage(), "")
            pendingLanguages.remove(event.sourceSegmentId())?.let { applyVisible(it.event) }
        } else if (bilingual) router.onSegmentStarted(event.sourceSegmentId())
        translations[event.translationId()] = TranslationSegment(event)
    }
    /** 最早期限供组合层只安排一个定时器，不依赖网络或秒级 tick。 */
    fun nextLanguageDeadlineMs(): OptionalLong {
        val earliest = pendingLanguages.values.minOfOrNull { it.startedAtMs + LANGUAGE_WAIT_MS }
        return if (earliest == null) OptionalLong.empty() else OptionalLong.of(earliest)
    }
    /** 期限到达冲刷最新修订并锁侧，迟到语种只改事实不搬栏。 */
    fun releaseExpiredLanguages(nowMs: Long): Boolean {
        var changed = false
        val iterator = pendingLanguages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            if (nowMs - pending.startedAtMs < LANGUAGE_WAIT_MS) continue
            router.forceLocalFallback(entry.key, pending.event.text())
            applyVisible(pending.event)
            LOG.fine("待语种显示窗口到期 segmentId=${entry.key} revision=${pending.event.revision()} waitMs=${nowMs - pending.startedAtMs}")
            iterator.remove()
            changed = true
        }
        return changed
    }
    /** 正常停止立即显示待语种段，不再等待，不改原始事件语言。 */
    fun flushPendingLanguages(): Boolean {
        finalizing = true
        val changed = pendingLanguages.isNotEmpty()
        if (changed) LOG.fine("停止时冲刷待语种显示段 count=${pendingLanguages.size}")
        for ((id, pending) in pendingLanguages) {
            router.forceLocalFallback(id, pending.event.text())
            applyVisible(pending.event)
        }
        pendingLanguages.clear()
        return changed
    }
    private data class PendingLanguage(val event: TranscriptEvent, val startedAtMs: Long)
    fun hasTranslation(): Boolean = translations.isNotEmpty()
    fun isBilingual(): Boolean = bilingual
    fun leftLanguage(): String = if (swapped) languageB else languageA
    fun rightLanguage(): String = if (swapped) languageA else languageB
    fun leftLabel(): String = languageLabel(leftLanguage())
    fun rightLabel(): String = languageLabel(rightLanguage())
    fun swapColumns() { swapped = !swapped }
    fun updateStableDirection(angle: Float) { router.updateStableAngle(angle) }
    /** NO_DIRECTION 保留最近稳定角，UNSUPPORTED 清角；已有句快照和已锁侧保持。 */
    fun updateAudioDirection(sample: AudioDirectionSample) {
        if (sample.status() == AudioDirectionSample.Status.AVAILABLE) updateStableDirection(sample.stableAngleDegrees())
        else if (sample.status() == AudioDirectionSample.Status.UNSUPPORTED) updateStableDirection(Float.NaN)
    }
    fun setSegmentDirection(segmentId: String, side: Int) { router.setSegmentDirection(segmentId, side) }
    fun route(segmentId: String): DisplayRouter.Route? = router.existingRoute(segmentId)
    /** 按片段配对原文和译文，两处 UI 读取同一已路由状态。 */
    fun bilingualRows(): List<BilingualRow> {
        val bySource = LinkedHashMap<String, TranslationSegment>()
        for (translation in translations.values) bySource[translation.sourceId] = translation
        val rows = ArrayList<BilingualRow>()
        for (source in segments.values) rows.add(bilingualRow(source.id(), source, bySource.remove(source.id())))
        for (sourceId in bySource.keys) rows.add(bilingualRow(sourceId, null, bySource[sourceId]))
        return rows
    }
    fun latestBilingualRow(): BilingualRow? = bilingualRows().lastOrNull()
    private fun bilingualRow(id: String, source: Segment?, translation: TranslationSegment?): BilingualRow {
        val sourceText = source?.text() ?: ""
        val sourceAlpha = source?.alpha() ?: 1f
        val translatedText = translation?.text ?: ""
        val translatedAlpha = translation?.alpha() ?: 1f
        val sourceLanguage = translation?.sourceLanguage ?: source?.language() ?: ""
        val route = router.route(id, sourceLanguage, sourceText)
        if (route.side() == 0) return BilingualRow(id, "语言识别中：$sourceText", "", sourceAlpha, 1f, route.basis())
        val sourceOnLeft = (route.side() == 1) != swapped
        return if (sourceOnLeft) BilingualRow(id, sourceText, translatedText, sourceAlpha, translatedAlpha, route.basis())
            else BilingualRow(id, translatedText, sourceText, translatedAlpha, sourceAlpha, route.basis())
    }
    /** 首次接收顺序，修订替换原行。 */
    fun segments(): List<Segment> = ArrayList(segments.values)
    fun segment(id: String): Segment? = segments[id]
    /** 不可变快照，嵌套行／事件不随以后修订改变。 */
    fun snapshot(): MeetingDisplaySnapshot {
        val bySource = LinkedHashMap<String, TranslationSegment>()
        for (translation in translations.values) bySource[translation.sourceId] = translation
        val rows = ArrayList<MeetingDisplaySnapshot.Row>()
        for (source in segments.values) rows.add(snapshotRow(source.id(), source, bySource.remove(source.id())))
        for (id in bySource.keys) rows.add(snapshotRow(id, null, bySource[id]))
        return MeetingDisplaySnapshot(bilingual, languageA, languageB, swapped, rows, latestTranscriptSegmentId)
    }
    private fun snapshotRow(id: String, source: Segment?, translation: TranslationSegment?): MeetingDisplaySnapshot.Row {
        val route = if (bilingual) router.existingRoute(id) else null
        val side = route?.side() ?: 0
        return MeetingDisplaySnapshot.Row(id, source?.event(), translation?.event,
            when (side) { 1 -> MeetingDisplaySnapshot.Side.A; 2 -> MeetingDisplaySnapshot.Side.B; else -> MeetingDisplaySnapshot.Side.UNRESOLVED },
            route?.basis() ?: DisplayRouter.Basis.LANGUAGE_UNRESOLVED)
    }
    fun transcript(): String = transcript
    fun transcriptAlpha(): Float = if (finalTranscript) 1f else .8f
    fun elapsed(elapsedRealtimeMs: Long): String {
        val seconds = maxOf(0L, (elapsedRealtimeMs - startedAtElapsedMs) / 1000L)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
    }
    companion object {
        private val LOG = Logger.getLogger(MeetingDisplayState::class.java.name)
        private const val LANGUAGE_WAIT_MS = 1500L
        private fun languageLabel(language: String): String = when {
            language.startsWith("zh") -> "中文"
            language.startsWith("en") -> "English"
            else -> language
        }
    }
}
