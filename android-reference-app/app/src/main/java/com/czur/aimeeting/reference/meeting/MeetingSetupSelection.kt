package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.MeetingCapabilityState

/**
 * App 的会前显示选择，不进入 SDK，也不持有任何模型地址或登录材料。
 *
 * 语言只使用部署发现的标准码：恢复偏好时求当前合法值，互译始终选择两个不同的语种。
 * 字幕和互译各保存一份本类型，切换模式不会覆盖另一模式的语言或总结选择。
 */
data class MeetingSetupSelection(
    val mode: MeetingSetup.Mode = MeetingSetup.Mode.LIVE_TRANSCRIPT,
    val sourceLanguage: String = "",
    val targetLanguage: String = "",
    val summaryEnabled: Boolean = false,
) {
    /** 保存值已不在当前部署时取可用列表中的值；不写回存储，不凭旧偏好恢复缺失能力。 */
    fun availableIn(languages: List<String>, summaryAvailable: Boolean): MeetingSetupSelection {
        val source = sourceLanguage.takeIf(languages::contains) ?: languages.firstOrNull().orEmpty()
        val target = targetLanguage.takeIf { it in languages && it != source }
            ?: languages.firstOrNull { it != source }.orEmpty()
        return copy(sourceLanguage = source, targetLanguage = target,
            summaryEnabled = summaryEnabled && summaryAvailable)
    }

    /**
     * 与 CZUR 原语言选择行为一致：选择另一侧已有语种时，把另一侧换成被替换的旧值。
     * 同一份新选择供两个 Spinner 一次重绘，避免先修改一侧时短暂保存相同语种。
     */
    fun selectLanguage(sourceSide: Boolean, language: String): MeetingSetupSelection = when {
        sourceSide -> copy(sourceLanguage = language,
            targetLanguage = if (mode == MeetingSetup.Mode.LIVE_TRANSLATION && language == targetLanguage)
                sourceLanguage else targetLanguage)
        else -> copy(targetLanguage = language,
            sourceLanguage = if (mode == MeetingSetup.Mode.LIVE_TRANSLATION && language == sourceLanguage)
                targetLanguage else sourceLanguage)
    }

    fun swapLanguages(): MeetingSetupSelection = copy(sourceLanguage = targetLanguage, targetLanguage = sourceLanguage)

    /** 页面按钮和实际点击入口共用；最终 Create 授权仍由 SDK／后端负责。 */
    fun canStart(loggedIn: Boolean, busy: Boolean, capabilities: Set<String>, languages: List<String>,
        hasResultAction: Boolean): Boolean = loggedIn && !busy && hasResultAction &&
        MeetingCapabilityState.TRANSCRIPT in capabilities && sourceLanguage in languages &&
        (!summaryEnabled || MeetingCapabilityState.SUMMARY in capabilities) &&
        (mode != MeetingSetup.Mode.LIVE_TRANSLATION ||
            (MeetingCapabilityState.TRANSLATION in capabilities && targetLanguage in languages &&
                targetLanguage != sourceLanguage))
}
