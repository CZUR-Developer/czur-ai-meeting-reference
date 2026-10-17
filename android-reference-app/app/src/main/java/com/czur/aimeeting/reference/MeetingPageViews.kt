package com.czur.aimeeting.reference

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.czur.aimeeting.reference.ui.CzurVoiceLevelView
import com.czur.aimeeting.reference.ui.SubtitleViewFactory

/**
 * App 页面装配，只呈现国内版资源和 SDK 快照，不创建客户端或第二份会议状态。
 * 伙伴可通过 Assembly 替换 Factory；字段保留 JVM 可见性，现有装配和回归继续可用。
 */
class MeetingPageViews(@JvmField val root: FrameLayout) {
    fun interface Factory { fun create(activity: Activity): MeetingPageViews }
    class DefaultFactory : Factory {
        override fun create(activity: Activity) = MeetingPageViews(LayoutInflater.from(activity)
            .inflate(R.layout.reference_meeting_page, null) as FrameLayout)
    }
    @JvmField val setupCard: View = root.findViewById(R.id.setup_card)
    @JvmField val meetingPanel: View = root.findViewById(R.id.meeting_panel)
    @JvmField val transcriptScroll: View = root.findViewById(R.id.transcript_scroll)
    @JvmField val bilingualArea: View = root.findViewById(R.id.bilingual_text_area)
    @JvmField val targetContainer: View = root.findViewById(R.id.target_language_container)
    @JvmField val controls: LinearLayout = root.findViewById(R.id.meeting_controls)
    @JvmField val transcriptList: LinearLayout = root.findViewById(R.id.transcript_list)
    @JvmField val bilingualHeader: LinearLayout = root.findViewById(R.id.bilingual_header)
    @JvmField val leftList: LinearLayout = root.findViewById(R.id.left_text_list)
    @JvmField val rightList: LinearLayout = root.findViewById(R.id.right_text_list)
    @JvmField val pluginSettingsHost: LinearLayout = root.findViewById(R.id.result_settings_host)
    @JvmField val setupTitle: TextView = root.findViewById(R.id.setup_title)
    @JvmField val meetingTitle: TextView = root.findViewById(R.id.meeting_title)
    @JvmField val elapsed: TextView = root.findViewById(R.id.elapsed)
    @JvmField val levelText: TextView = root.findViewById(R.id.audio_level_text)
    @JvmField val status: TextView = root.findViewById(R.id.status)
    @JvmField val presentationNotice: TextView = root.findViewById(R.id.presentation_notice)
    @JvmField val capabilities: TextView = root.findViewById(R.id.capabilities)
    @JvmField val leftTitle: TextView = root.findViewById(R.id.left_language_title)
    @JvmField val rightTitle: TextView = root.findViewById(R.id.right_language_title)
    @JvmField val account: Button = root.findViewById(R.id.account_button)
    @JvmField val start: Button = root.findViewById(R.id.start_meeting)
    /** 停止已退窗但 SDK 仍允许确认时使用；点击行为由 Activity 接回原 requestStop。 */
    @JvmField val stopConfirmation: Button = Button(root.context, null, 0, R.style.ReferenceButton).apply {
        text = "重新确认停止"
        contentDescription = "重新确认本场会议停止"
        visibility = View.GONE
    }
    @JvmField val stop: Button = root.findViewById(R.id.stop_meeting)
    @JvmField val overlay: Button = root.findViewById(R.id.overlay_button)
    @JvmField val swapColumns: Button = root.findViewById(R.id.swap_columns)
    @JvmField val swapLanguages: Button = root.findViewById(R.id.swap_languages)
    @JvmField val retry: Button = root.findViewById(R.id.retry_result)
    @JvmField val endResult: Button = root.findViewById(R.id.end_result)
    @JvmField val sourceLanguage: Spinner = root.findViewById(R.id.source_language)
    @JvmField val targetLanguage: Spinner = root.findViewById(R.id.target_language)
    @JvmField val summary: CheckBox = root.findViewById(R.id.summary_enabled)
    @JvmField val presentation: CheckBox = root.findViewById(R.id.presentation_capture)
    @JvmField val transcriptMode: RadioButton = root.findViewById(R.id.transcript_mode)
    @JvmField val translationMode: RadioButton = root.findViewById(R.id.translation_mode)
    @JvmField val modes: RadioGroup = root.findViewById(R.id.mode_group)
    @JvmField val results: RadioGroup = root.findViewById(R.id.result_group)
    @JvmField val level: ProgressBar = root.findViewById(R.id.audio_level)
    @JvmField val voiceLevel: CzurVoiceLevelView = root.findViewById(R.id.voice_level)
    private val singleControlsSlot: FrameLayout = root.findViewById(R.id.single_controls_slot)
    private val bilingualControlsSlot: FrameLayout = root.findViewById(R.id.bilingual_controls_slot)
    @JvmField val placeholder = SubtitleViewFactory.create(root.context, 24f, 3, false)
    private val scale: ReferenceUiScale
    /** 记录缩放后的原始宽度；只在翻译入口隐藏时让字幕入口填满模式栏。 */
    private var transcriptModeDesignWidth = 0
    init {
        placeholder.setTextColor(Color.WHITE)
        SubtitleViewFactory.render(placeholder, "", "记录中…", .8f)
        transcriptList.addView(placeholder)
        setupCard.clipToOutline = true
        // 与原“开始”占用相同会前槽位，不把已停止的会议页或浮窗重新显示出来。
        root.findViewById<LinearLayout>(R.id.setup_footer).addView(stopConfirmation, 1,
            LinearLayout.LayoutParams(start.layoutParams as LinearLayout.LayoutParams))
        scale = ReferenceUiScale.bind(root)
        transcriptModeDesignWidth = transcriptMode.layoutParams.width
        SubtitleViewFactory.setLineHeightPx(placeholder, scale.px(36))
        icon(stop, R.drawable.czur_ref_stop, 40, 40)
        icon(overlay, R.drawable.czur_ref_minimize, 40, 40)
        icon(swapColumns, R.drawable.czur_ref_swap_columns, 52, 28)
        icon(swapLanguages, R.drawable.czur_ref_swap_languages, 49, 24)
    }
    private fun icon(button: Button, resource: Int, width: Int, height: Int) {
        val drawable = root.context.getDrawable(resource)!!
        drawable.setBounds(0, 0, scale.px(width), scale.px(height))
        button.setCompoundDrawables(null, drawable, null, null)
        button.gravity = Gravity.CENTER
    }
    fun styleLanguageItem(text: TextView, popup: Boolean, selected: Boolean) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.px(24).toFloat())
        text.setTextColor(root.context.getColor(R.color.reference_blue))
        text.gravity = Gravity.CENTER_VERTICAL
        text.setPadding(scale.px(24), 0, scale.px(16), 0)
        text.minHeight = scale.px(50)
        text.layoutParams?.let { it.height = scale.px(50); text.layoutParams = it }
        val arrow = root.context.getDrawable(if (popup) R.drawable.czur_ref_language_selected else R.drawable.czur_ref_language_arrow)!!
        arrow.setTint(root.context.getColor(R.color.reference_blue))
        arrow.setBounds(0, 0, scale.px(if (popup) 24 else 14), scale.px(if (popup) 24 else 8))
        text.setCompoundDrawables(null, null, if (!popup || selected) arrow else null, null)
        if (popup) text.setBackgroundColor(Color.parseColor(if (selected) "#DADFFD" else "#F1F3FE"))
    }
    fun styleResultChoice(choice: RadioButton) {
        choice.setTextColor(root.context.getColor(R.color.reference_ink))
        choice.setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.px(24).toFloat())
        choice.setButtonDrawable(R.drawable.czur_ref_check); choice.buttonTintList = null
        choice.setPadding(scale.px(24), 0, 0, 0)
        choice.compoundDrawablePadding = scale.px(24); choice.minHeight = scale.px(58)
    }

    /**
     * 按登录后能力发现结果收敛会前入口。
     *
     * 互译或总结缺失时不留一个看似可点、实际无法建立的入口；字幕是剩余能力时仍
     * 保持可用，并让唯一的字幕入口占满模式栏。这里仅改变 View 的呈现和选择状态，
     * 不修改 SDK 的能力快照，也不把缺失能力伪造成降级成功。
     */
    fun showCapabilityOptions(
        summaryAvailable: Boolean,
        translationAvailable: Boolean,
        transcriptAvailable: Boolean = true,
    ) {
        val usableTranslation = transcriptAvailable && translationAvailable
        if (!usableTranslation && translationMode.isChecked) transcriptMode.isChecked = true
        modes.visibility = if (transcriptAvailable) View.VISIBLE else View.GONE
        transcriptMode.visibility = if (transcriptAvailable) View.VISIBLE else View.GONE
        transcriptMode.isEnabled = transcriptAvailable
        translationMode.visibility = if (usableTranslation) View.VISIBLE else View.GONE
        translationMode.isEnabled = usableTranslation
        summary.visibility = if (transcriptAvailable && summaryAvailable) View.VISIBLE else View.GONE
        summary.isEnabled = transcriptAvailable && summaryAvailable
        if (!transcriptAvailable || !summaryAvailable) summary.isChecked = false
        targetLanguage.isEnabled = usableTranslation && translationMode.isChecked
        transcriptMode.layoutParams = transcriptMode.layoutParams.apply {
            width = if (usableTranslation) transcriptModeDesignWidth else ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    /**
     * 保留旧调用者的默认标题行为；参考入口在能力收敛时传入真实的总结能力。
     */
    fun showSetupMode(translation: Boolean, summaryAvailable: Boolean = true, transcriptAvailable: Boolean = true) {
        val usableTranslation = translation && transcriptAvailable
        targetContainer.visibility = if (usableTranslation) View.VISIBLE else View.GONE
        swapLanguages.visibility = if (usableTranslation) View.VISIBLE else View.GONE
        presentation.visibility = if (usableTranslation || !transcriptAvailable) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.presentation_hint).visibility = if (usableTranslation || !transcriptAvailable) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.translation_illustration).visibility = if (usableTranslation) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.language_hint).apply {
            text = when {
                !transcriptAvailable -> "当前部署未提供实时字幕能力"
                usableTranslation -> "提示：两种语言的人群，分坐左右两侧"
                else -> "语言"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.px(if (usableTranslation) 20 else 30).toFloat())
            setTypeface(null, if (usableTranslation) Typeface.NORMAL else Typeface.BOLD)
        }
        setupTitle.text = when {
            !transcriptAvailable -> "实时字幕不可用"
            usableTranslation -> "AI双语互译"
            summaryAvailable -> "AI会议纪要"
            else -> "AI字幕"
        }
        root.findViewById<TextView>(R.id.setup_subtitle).text = when {
            !transcriptAvailable -> "（当前部署未声明 transcript.realtime）"
            usableTranslation -> "（实时将两种语言进行互译）"
            else -> "（实时将声音转文字）"
        }
    }
    fun textRow(ignoredLegacySp: Float, translated: Boolean): TextView =
        SubtitleViewFactory.create(root.context, scale.textSp(24), Int.MAX_VALUE, false).apply {
            SubtitleViewFactory.setLineHeightPx(this, scale.px(36))
            setTextColor(if (translated) root.context.getColor(R.color.reference_translation) else Color.WHITE)
            setPadding(0, 0, 0, scale.px(if (translated) 16 else 4))
        }
    fun showMeeting(active: Boolean) {
        // 页面可见的会中阶段才保亮屏；Stop 即退会中后解除，不延续至模型／Web收尾。
        root.keepScreenOn = active
        setupCard.visibility = if (active) View.GONE else View.VISIBLE
        meetingPanel.visibility = if (active) View.VISIBLE else View.GONE
        status.visibility = if (active) View.GONE else View.VISIBLE
    }
    /**
     * 仅投影 SDK 提供的停止能力，不在 UI 推断会议状态或自动补发停止请求。
     *
     * Activity 传入 `!active && canStop`：正常收尾中不出现按钮；服务端尚未确认且
     * SDK 允许重试时，在会前固定操作区显式再次确认。READY 后恢复原开始入口。
     */
    fun showStopConfirmation(visible: Boolean) {
        stopConfirmation.visibility = if (visible) View.VISIBLE else View.GONE
        start.visibility = if (visible) View.GONE else View.VISIBLE
    }
    /** 提示独立于常规阶段消息，只在会中显示必要截图限制和失败。 */
    fun showPresentationNotice(active: Boolean, message: String) {
        presentationNotice.text = message
        presentationNotice.visibility = if (active && message.isNotEmpty()) View.VISIBLE else View.GONE
    }
    /** 移动同一组控件，不复制停止、电平、交换状态或注册第二份监听。 */
    fun showBilingual(bilingual: Boolean) {
        bilingualHeader.visibility = if (bilingual) View.VISIBLE else View.GONE
        bilingualArea.visibility = if (bilingual) View.VISIBLE else View.GONE
        transcriptScroll.visibility = if (bilingual) View.GONE else View.VISIBLE
        swapColumns.visibility = if (bilingual) View.VISIBLE else View.GONE
        singleControlsSlot.visibility = if (bilingual) View.GONE else View.VISIBLE
        val slot = if (bilingual) bilingualControlsSlot else singleControlsSlot
        if (controls.parent !== slot) {
            (controls.parent as ViewGroup).removeView(controls)
            slot.addView(controls, FrameLayout.LayoutParams(-1, -1))
        }
        controls.orientation = if (bilingual) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        controls.gravity = if (bilingual) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        for (i in 0 until controls.childCount) {
            val child = controls.getChildAt(i)
            val params = child.layoutParams as LinearLayout.LayoutParams
            params.bottomMargin = if (bilingual) scale.px(26) else 0
            params.leftMargin = if (bilingual) 0 else scale.px(10)
            params.rightMargin = if (bilingual) 0 else scale.px(10)
            child.layoutParams = params
        }
        meetingPanel.setBackgroundResource(if (bilingual) R.drawable.czur_ref_translation_background else R.drawable.czur_ref_subtitle_background)
    }
    fun followsLatest(scrollId: Int): Boolean {
        val scroll = root.findViewById<ScrollView>(scrollId)
        return scroll.childCount == 0 || scroll.scrollY + scroll.height >= scroll.getChildAt(0).height - scale.px(36)
    }
    fun scrollLatest(scrollId: Int, follow: Boolean) {
        if (!follow) return
        val scroll = root.findViewById<ScrollView>(scrollId)
        scroll.post { scroll.scrollTo(0, if (scroll.childCount == 0) 0 else maxOf(0, scroll.getChildAt(0).height - scroll.height)) }
    }
}
