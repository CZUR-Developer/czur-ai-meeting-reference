package com.czur.aimeeting.client

import com.czur.aimeeting.client.result.ResultOptions

/**
 * 本场会前快照，mode 控制会中呈现，summaryEnabled 独立控制会后纪要，结果动作独立。
 * title／timezone／retainAudio 为既有 Create 字段；不持有模型地址或访问材料。
 * 保留既有七／八／九／十二参 JVM 构造器及默认值，旧 MEETING_MINUTES 始终开启纪要。
 */
data class MeetingOptions(
    private val mode: Mode,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val presentationCapture: Boolean,
    private val resultDelivery: ResultDelivery,
    private val resultWebUrl: String,
    private var resultEmails: List<String>,
    private var summaryEnabled: Boolean,
    private val resultOptions: ResultOptions,
    private val title: String?,
    private val timezone: String,
    private val retainAudio: Boolean,
) {
    // 协商结果属于本场内部快照，不加入 data class 主构造器。
    // 主构造器保持原十二参，连同 Kotlin 生成的 copy／copy$default 保留原 JVM 签名，
    // 已编译的 SDK／伙伴调用方可继续消费新版 core；赋值入口只在下方两个复制方法内。
    private var requestedCapabilitiesOverride: List<String>? = null

    constructor(mode: Mode, sourceLanguage: String, targetLanguage: String,
        presentationCapture: Boolean, resultDelivery: ResultDelivery, resultWebUrl: String,
        resultEmails: List<String>, summaryEnabled: Boolean, resultOptions: ResultOptions) : this(
        mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery, resultWebUrl,
        resultEmails, summaryEnabled, resultOptions, "参考会议", "Asia/Shanghai", false)

    constructor(mode: Mode, sourceLanguage: String, targetLanguage: String,
        presentationCapture: Boolean, resultDelivery: ResultDelivery, resultWebUrl: String,
        resultEmails: List<String>, summaryEnabled: Boolean) : this(
        mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery, resultWebUrl,
        resultEmails, summaryEnabled, ResultOptions.fromLegacy(resultDelivery))

    /** 新伙伴动作用稳定字符串，不从旧枚举占位值推断动作。参数由 App prepare 冻结。 */
    constructor(mode: Mode, sourceLanguage: String, targetLanguage: String,
        presentationCapture: Boolean, resultOptions: ResultOptions, resultWebUrl: String,
        resultEmails: List<String>, summaryEnabled: Boolean) : this(
        mode, sourceLanguage, targetLanguage, presentationCapture, resultOptions.legacyDelivery(),
        resultWebUrl, resultEmails, summaryEnabled, resultOptions)

    constructor(mode: Mode, sourceLanguage: String, targetLanguage: String,
        presentationCapture: Boolean, resultDelivery: ResultDelivery, resultWebUrl: String,
        resultEmails: List<String>) : this(mode, sourceLanguage, targetLanguage, presentationCapture,
        resultDelivery, resultWebUrl, resultEmails, mode == Mode.MEETING_MINUTES)

    init {
        require(timezone.isNotBlank()) { "建会时区不能为空" }
        resultEmails = java.util.List.copyOf(resultEmails)
        summaryEnabled = summaryEnabled || mode == Mode.MEETING_MINUTES
    }

    fun mode(): Mode = mode
    fun sourceLanguage(): String = sourceLanguage
    fun targetLanguage(): String = targetLanguage
    fun presentationCapture(): Boolean = presentationCapture
    fun resultDelivery(): ResultDelivery = resultDelivery
    fun resultWebUrl(): String = resultWebUrl
    fun resultEmails(): List<String> = resultEmails
    fun summaryEnabled(): Boolean = summaryEnabled
    fun resultOptions(): ResultOptions = resultOptions
    fun title(): String? = title
    fun timezone(): String = timezone
    fun retainAudio(): Boolean = retainAudio

    /**
     * 仅复制建会参数，不改变其他会前选择；title 可为空由 HTTP 省略，不启动或保证录音。
     * 若关闭已协商的录音保留，同时移除依赖录音的跨会议身份请求；重新开启录音不会
     * 自动补回它，仍需经过本场能力发现。主构造器及 Kotlin copy 的 JVM 签名保持不变。
     */
    fun withCreateParameters(title: String?, timezone: String, retainAudio: Boolean): MeetingOptions =
        MeetingOptions(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery,
            resultWebUrl, resultEmails, summaryEnabled, resultOptions, title, timezone, retainAudio).also {
            it.requestedCapabilitiesOverride = requestedCapabilitiesOverride?.let { requested ->
                java.util.List.copyOf(requested.filter { capability ->
                    retainAudio || capability != MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING
                })
            }
        }

    /**
     * 根据本场能力发现冻结建会请求。
     *
     * 必需能力由模式和会后选择决定，始终保留；发言人识别是可选能力，只有后端已经声明
     * 且本地注册表存在对应 Adapter 时才加入本场 Create。跨会议身份另由服务端会后处理，
     * 仅在可用集合明确包含该能力、本场发言人且 retainAudio=true 时请求；可用集合由
     * Coordinator 先核对实际录音与发布端口，不能用实时 Adapter 的自声明代替部署声明。
     * 这样伙伴只提供字幕时不会因为
     * SDK 的默认能力集合被后端拒绝，同时不改变旧调用方直接读取 requestedCapabilities() 的
     * 默认兼容结果。
     */
    fun withAvailableCapabilities(availableCapabilities: Set<String>): MeetingOptions =
        MeetingOptions(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery,
            resultWebUrl, resultEmails, summaryEnabled, resultOptions, title, timezone, retainAudio).also {
            it.requestedCapabilitiesOverride = java.util.List.copyOf(buildList {
                addAll(requiredCapabilities())
                addAll(optionalCapabilities().filter(availableCapabilities::contains))
                if (retainAudio && availableCapabilities.contains(MeetingCapabilityState.SPEAKER) &&
                    availableCapabilities.contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
                    add(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING)
            })
        }

    enum class Mode(private val displayName: String, private val requiredCapability: String) {
        LIVE_TRANSCRIPT("实时字幕", MeetingCapabilityState.TRANSCRIPT),
        MEETING_MINUTES("AI 会议纪要", MeetingCapabilityState.SUMMARY),
        LIVE_TRANSLATION("现场互译", MeetingCapabilityState.TRANSLATION);
        fun displayName(): String = displayName
        fun requiredCapability(): String = requiredCapability
    }
    enum class ResultDelivery { WEB, LOCAL, EMAIL, TRACELESS }

    fun requestedCapabilities(): List<String> = requestedCapabilitiesOverride
        ?: java.util.List.copyOf(requiredCapabilities() + optionalCapabilities())
    private fun optionalCapabilities(): List<String> = listOf(MeetingCapabilityState.SPEAKER)
    /** 用户选定功能为本场必需，发言人为可选；Create 决策不满足时不启动模型。 */
    fun requiredCapabilities(): List<String> = java.util.List.copyOf(buildList {
        add(MeetingCapabilityState.TRANSCRIPT)
        if (mode == Mode.LIVE_TRANSLATION) add(MeetingCapabilityState.TRANSLATION)
        if (summaryEnabled) add(MeetingCapabilityState.SUMMARY)
    })
    fun targetLanguages(): List<String> = if (mode == Mode.LIVE_TRANSLATION) listOf(targetLanguage) else emptyList()
}
