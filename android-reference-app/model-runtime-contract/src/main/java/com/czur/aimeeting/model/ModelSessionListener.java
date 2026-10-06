package com.czur.aimeeting.model;

/** 模型会话生命周期回调；可发生在非 UI 线程。 */
public interface ModelSessionListener {
    /**
     * 音源选择完成后返回本次实际模式。应用只观察该结果；旧系统兼容降级、平台探测
     * 和底层开关由 SDK 处理。Debug WAV 使用独立枚举值，不能当作真实 MIC 证据。
     */
    default void onAudioInputMode(AudioInputMode mode) {}

    /**
     * 当前系统方向样本。应用需要方向参与分栏时应在同一 UI 队列更新状态，再显示
     * 后续字幕；NO_DIRECTION／UNSUPPORTED 应清除旧全局方向，已锁侧片段保持原栏。
     */
    default void onAudioDirection(AudioDirectionSample sample) {}

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
