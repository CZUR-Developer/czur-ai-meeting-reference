package com.czur.aimeeting.client

import com.czur.aimeeting.client.result.ResultOptions

/**
 * 本场会前快照，mode 控制会中呈现，summaryEnabled 独立控制会后纪要，结果动作独立。
 * summaryEnabled 只表示用户要本场纪要，纪要正文由服务端生成，不再表示设备侧调用文本模型。
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
    /** 本场能力发现结果；为空表示尚未协商（旧调用方直接读取快照）。 */
    private var availableCapabilitySet: Set<String> = emptySet()
    /** 本场共享音频混流选项；不进入 Create 请求，只影响设备端采集。 */
    private var audioMixOptions: AudioMixOptions = AudioMixOptions()

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
    /** 本场共享音频混流选项；默认开启播放捕获、两路增益 100。 */
    fun audioMix(): AudioMixOptions = audioMixOptions

    /**
     * 只替换本场音频混流选项，不改建会参数、能力协商或结果动作。
     * 选项只作用于设备端音源，不进入 Create 请求；老调用方不设置时沿用默认值。
     */
    fun withAudioMix(options: AudioMixOptions): MeetingOptions =
        MeetingOptions(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery,
            resultWebUrl, resultEmails, summaryEnabled, resultOptions, title, timezone, retainAudio).also {
            it.requestedCapabilitiesOverride = requestedCapabilitiesOverride
            it.audioMixOptions = options
        }

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
            it.audioMixOptions = audioMixOptions
        }

    /**
     * 根据本场能力发现冻结建会请求。
     *
     * 必需能力由模式和会后选择决定，始终保留：用户要纪要时必需项是服务端生成能力
     * `summary.server`，纪要正文不再由设备生成，所以不再要求设备直连的 `summary.structured`。
     * 后者是兼容项：只有部署发现结果仍包含它时才随请求发出（老部署过渡期保留该声明），
     * 本机没有注册文本总结 Adapter 也不影响纪要入口与建会。发言人识别是可选能力，只有后端
     * 已经声明且本地注册表存在对应 Adapter 时才加入本场 Create。跨会议身份另由服务端会后处理，
     * 仅在可用集合明确包含该能力、本场发言人且 retainAudio=true 时请求；可用集合由
     * Coordinator 先核对实际录音与发布端口，不能用实时 Adapter 的自声明代替部署声明。
     * 这样伙伴只提供字幕时不会因为 SDK 的默认能力集合被后端拒绝；旧调用方直接读取
     * requestedCapabilities() 时，唯一新增项是用户明确要纪要时的服务端生成能力。
     */
    fun withAvailableCapabilities(availableCapabilities: Set<String>): MeetingOptions =
        MeetingOptions(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery,
            resultWebUrl, resultEmails, summaryEnabled, resultOptions, title, timezone, retainAudio).also {
            it.availableCapabilitySet = availableCapabilities
            it.requestedCapabilitiesOverride = java.util.List.copyOf(buildList {
                addAll(it.requiredCapabilities())
                addAll(structuredSummaryCompatibility(availableCapabilities))
                addAll(optionalCapabilities().filter(availableCapabilities::contains))
                if (retainAudio && availableCapabilities.contains(MeetingCapabilityState.SPEAKER) &&
                    availableCapabilities.contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
                    add(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING)
            }.distinct())
            it.audioMixOptions = audioMixOptions
        }

    /** 模式只作为枚举交给宿主，SDK 不携带面向终端用户的名称。 */
    enum class Mode(private val requiredCapability: String) {
        LIVE_TRANSCRIPT(MeetingCapabilityState.TRANSCRIPT),
        // 会后纪要由服务端生成：模式依赖服务端能力，不再依赖设备直连的 summary.structured。
        MEETING_MINUTES(MeetingCapabilityState.SUMMARY_SERVER),
        LIVE_TRANSLATION(MeetingCapabilityState.TRANSLATION);
        fun requiredCapability(): String = requiredCapability
    }
    enum class ResultDelivery { WEB, LOCAL, EMAIL, TRACELESS }

    fun requestedCapabilities(): List<String> = requestedCapabilitiesOverride
        ?: java.util.List.copyOf(buildList {
            addAll(requiredCapabilities())
            // 旧调用方直接读取时不改原请求列表：设备直连兼容项仍随请求发出。
            if (summaryEnabled) add(MeetingCapabilityState.SUMMARY)
            addAll(optionalCapabilities())
        })
    private fun optionalCapabilities(): List<String> = listOf(MeetingCapabilityState.SPEAKER)
    /**
     * 设备直连兼容期遗留的 `summary.structured`：纪要正文改由服务端生成后它不再是必需项，
     * 仅当本场能力发现结果仍包含它（老部署同时声明且本机确有对应 Adapter）时随请求发出，
     * 让仍要求该声明的老服务端不被一次请求变更打断。本机没有注册总结 Adapter 时它不会
     * 出现在发现结果里，因此不会挡住纯服务端部署。
     */
    private fun structuredSummaryCompatibility(availableCapabilities: Set<String>): List<String> =
        if (summaryEnabled && availableCapabilities.contains(MeetingCapabilityState.SUMMARY))
            listOf(MeetingCapabilityState.SUMMARY) else emptyList()
    /** 用户选定功能为本场必需，发言人为可选；Create 决策不满足时不启动模型。 */
    fun requiredCapabilities(): List<String> = java.util.List.copyOf(buildList {
        add(MeetingCapabilityState.TRANSCRIPT)
        if (mode == Mode.LIVE_TRANSLATION) add(MeetingCapabilityState.TRANSLATION)
        // 纪要由服务端按客户端上报的语言列表生成时必需 summary.server；
        // 部署若仍由本机/本地服务生成纪要（如本地化部署只声明 summary.structured），
        // 则退回该兼容能力，避免可用集合有纪要能力却被门禁判为缺少必需项。
        if (summaryEnabled) {
            when {
                availableCapabilitySet.contains(MeetingCapabilityState.SUMMARY_SERVER) ->
                    add(MeetingCapabilityState.SUMMARY_SERVER)
                // 本地化部署（如 ai-meeting-local-deployment）只声明 summary.structured，
                // 但纪要由本地服务在 Stop 时自行生成，客户端不提交正文，也不请求 summary:request。
                availableCapabilitySet.contains(MeetingCapabilityState.SUMMARY) ->
                    add(MeetingCapabilityState.SUMMARY)
                // 未协商过可用集合（旧调用方直接读取）时保持原来的服务端语义。
                // 未协商（旧调用方）或部署两者都没声明：保持服务端要求，让门禁拒绝而不是静默降级。
                else -> add(MeetingCapabilityState.SUMMARY_SERVER)
            }
        }
    })
    fun targetLanguages(): List<String> = if (mode == Mode.LIVE_TRANSLATION) listOf(targetLanguage) else emptyList()
}
