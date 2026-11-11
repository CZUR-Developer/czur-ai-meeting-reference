package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.result.ResultOptions

/**
 * 点击开始时冻结的会前设置。模型能力与结果动作独立；前者进入组合入口，后者由 App
 * 会前准备并在 READY 后显式执行。旧 MEETING_MINUTES 继续表示字幕加总结。
 */
class MeetingSetup(
    private val mode: Mode, private val sourceLanguage: String, private val targetLanguage: String,
    private val presentationCapture: Boolean, private val resultDelivery: ResultDelivery,
    private val resultWebUrl: String, resultEmails: List<String>, summaryEnabled: Boolean,
    private val resultOptions: ResultOptions
) {
    private val resultEmails = java.util.List.copyOf(resultEmails)
    private val summaryEnabled = summaryEnabled || mode == Mode.MEETING_MINUTES

    constructor(mode: Mode, sourceLanguage: String, targetLanguage: String, presentationCapture: Boolean,
        resultDelivery: ResultDelivery, resultWebUrl: String, resultEmails: List<String>, summaryEnabled: Boolean
    ) : this(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery, resultWebUrl,
        resultEmails, summaryEnabled, ResultOptions.fromLegacy(MeetingOptions.ResultDelivery.valueOf(resultDelivery.name)))

    /** 新动作入口只冻结设置，插件参数不经通用参数总线。 */
    constructor(mode: Mode, sourceLanguage: String, targetLanguage: String, presentationCapture: Boolean,
        resultOptions: ResultOptions, resultWebUrl: String, resultEmails: List<String>, summaryEnabled: Boolean
    ) : this(mode, sourceLanguage, targetLanguage, presentationCapture,
        ResultDelivery.valueOf(resultOptions.legacyDelivery().name), resultWebUrl, resultEmails, summaryEnabled, resultOptions)

    @JvmOverloads constructor(mode: Mode, sourceLanguage: String, targetLanguage: String,
        presentationCapture: Boolean, resultDelivery: ResultDelivery, resultWebUrl: String,
        resultEmails: List<String> = emptyList()
    ) : this(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery, resultWebUrl,
        resultEmails, mode == Mode.MEETING_MINUTES)

    enum class Mode(private val displayName: String, private val requiredCapability: String) {
        LIVE_TRANSCRIPT("实时字幕", MeetingCapabilityState.TRANSCRIPT),
        // “AI 会议纪要”依赖服务端生成能力；设备直连的 summary.structured 不再是必需项。
        MEETING_MINUTES("AI 会议纪要", MeetingCapabilityState.SUMMARY_SERVER),
        LIVE_TRANSLATION("现场互译", MeetingCapabilityState.TRANSLATION);
        fun displayName() = displayName
        fun requiredCapability() = requiredCapability
    }
    enum class ResultDelivery { WEB, LOCAL, EMAIL, TRACELESS }
    fun mode() = mode
    fun sourceLanguage() = sourceLanguage
    fun targetLanguage() = targetLanguage
    fun presentationCapture() = presentationCapture
    fun resultDelivery() = resultDelivery
    fun resultWebUrl() = resultWebUrl
    fun resultEmails() = resultEmails
    fun summaryEnabled() = summaryEnabled
    fun resultOptions() = resultOptions
    fun requestedCapabilities() = toOptions().requestedCapabilities()
    fun targetLanguages() = toOptions().targetLanguages()

    /** UI 与 Hook 只转换到组合层唯一业务类型，不重建协议状态。 */
    fun toOptions(): MeetingOptions {
        val options = MeetingOptions(MeetingOptions.Mode.valueOf(mode.name), sourceLanguage, targetLanguage,
            presentationCapture, resultOptions, resultWebUrl, resultEmails, summaryEnabled)
        // 只有 SDK 运行时真正能封口录音时才向服务端请求 audio.original；Mock 保持无音频路径。
        return options.withCreateParameters("参考会议", "Asia/Shanghai",
            MeetingRuntimeFactory.supportsRetainedAudio())
    }

    companion object {
        @JvmStatic fun transcript(sourceLanguage: String, resultWebUrl: String) = MeetingSetup(
            Mode.LIVE_TRANSCRIPT, sourceLanguage, "en-US", false, ResultDelivery.WEB, resultWebUrl, emptyList())
    }
}
