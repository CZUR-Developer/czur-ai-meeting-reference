package com.czur.aimeeting.model;

/** 模型会话生命周期回调；可发生在非 UI 线程。 */
public interface ModelSessionListener {
    /**
     * 音源选择完成后返回本次实际模式。应用只观察该结果；旧系统兼容降级、平台探测
     * 和底层开关由 SDK 处理。Debug WAV 使用独立枚举值，不能当作真实 MIC 证据。
     */
    default void onAudioInputMode(AudioInputMode mode) {}

    /**
     * 正在使用的同一 PCM 流的 RMS 显示电平，默认约 300 ms 一次；不另开音源。
     * 与本场技术回调串行交付，可能来自非 UI 线程，调用方应快速返回。
     * stopInput、输入结束或 close 后不再通知，已进入的回调允许返回；宿主停止时复位 UI。
     * 默认空实现使旧运行时、仅字幕调用和没有实际 PCM 的 Mock 保持兼容。
     */
    default void onAudioLevel(AudioInputLevel level) {}

    /**
     * 当前系统方向样本，NO_DIRECTION 如实表示本次无有效读数。是否保留历史稳定角属于
     * 上层显示策略：国内组合规则保留 NO_DIRECTION 之前的有效角，UNSUPPORTED 按语种
     * 降级。基础层不把缓存角伪装成本次 AVAILABLE；已锁侧片段始终保持原栏。
     */
    default void onAudioDirection(AudioDirectionSample sample) {}

    /** 模型句开始的标准事实；与字幕/译文同队列交付，供上层绑定本段方向快照。 */
    default void onSegmentStarted(SegmentStartedEvent event) {}

    /**
     * 音频输入失败。SDK 不弹窗、不申请权限，应用根据 code 提示或进入自己的申请流程。
     * 默认转发已有 onError，保持只实现旧生命周期回调的应用和 Mock 源码兼容。
     */
    default void onAudioError(AudioInputErrorCode code, String message) { onError(message); }

    /** 已归一化译文修订；默认空实现保留现有仅字幕会话的行为。 */
    default void onTranslation(TranslationEvent event) {}

    /** 模型已确认结束，之前收到的标准字幕可以提交后端并完成会议。 */
    void onFinished();

    /** 运行时无法继续本场会议；message 只包含可向用户展示的诊断信息。 */
    void onError(String message);
}
