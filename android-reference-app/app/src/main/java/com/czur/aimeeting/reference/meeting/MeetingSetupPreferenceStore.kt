package com.czur.aimeeting.reference.meeting

import android.content.Context

/**
 * 参考 App 的普通会前偏好。只保存模式、标准语言码和总结选择，不保存能力授权或模型材料。
 *
 * 本存储不替代部署发现：读取后必须用当前部署支持的语言／能力解析再显示。
 * 伙伴可在 App 层替换存储方式；SDK 与正在进行会议的冻结选项均不受影响。
 */
class MeetingSetupPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "czur_reference_meeting_setup", Context.MODE_PRIVATE)

    fun mode(): MeetingSetup.Mode = if (preferences.getString("mode", null) == MeetingSetup.Mode.LIVE_TRANSLATION.name)
        MeetingSetup.Mode.LIVE_TRANSLATION else MeetingSetup.Mode.LIVE_TRANSCRIPT

    /** 两种页面各自保留语言与总结开关；新安装不绑定听悟或任何固定语种。 */
    fun read(mode: MeetingSetup.Mode): MeetingSetupSelection = MeetingSetupSelection(mode,
        preferences.getString("${mode.name}.source", "").orEmpty(),
        preferences.getString("${mode.name}.target", "").orEmpty(),
        preferences.getBoolean("${mode.name}.summary", false))

    /** 只在用户实际改变选择时保存，能力不足引起的临时隐藏／回退不覆盖用户原偏好。 */
    fun save(selection: MeetingSetupSelection) {
        val prefix = selection.mode.name
        preferences.edit().putString("mode", prefix)
            .putString("$prefix.source", selection.sourceLanguage)
            .putString("$prefix.target", selection.targetLanguage)
            .putBoolean("$prefix.summary", selection.summaryEnabled).apply()
    }
}
