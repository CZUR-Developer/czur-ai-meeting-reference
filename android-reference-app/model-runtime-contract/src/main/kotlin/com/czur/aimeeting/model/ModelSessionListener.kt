package com.czur.aimeeting.model

/** 模型生命周期，可能来自非 UI 线程；默认方法保持既有实现兼容。 */
interface ModelSessionListener {
    /** 本场实际音源，平台探测和开关由 SDK 负责；Debug WAV 不作为实体 MIC 证据。 */
    fun onAudioInputMode(mode: AudioInputMode) {}
    /**
     * 同一路 PCM 的显示电平，默认约 300ms 一次；与技术回调串行，接收方快速返回。
     * 停止／输入结束／close 后不再通知，已进入回调可返回；宿主停止时复位 UI。
     */
    fun onAudioLevel(level: AudioInputLevel) {}
    /**
     * NO_DIRECTION 如实表示无有效读数。保留旧稳定角、锁侧和语种降级由显示层处理，
     * 基础层不把缓存角伪装为本次 AVAILABLE。
     */
    fun onAudioDirection(sample: AudioDirectionSample) {}
    /** 与字幕／译文同队列交付，供上层绑定方向快照。 */
    fun onSegmentStarted(event: SegmentStartedEvent) {}
    /** 输入失败只返回分类，不申请权限或弹窗；默认委托既有错误回调。 */
    fun onAudioError(code: AudioInputErrorCode, message: String) { onError(message) }
    /** 标准译文修订，默认不处理以兼容仅字幕会话。 */
    fun onTranslation(event: TranslationEvent) {}
    /** 模型确认结束，可提交最终标准字幕。 */
    fun onFinished()
    /** 无法继续本场，message 仅含可展示诊断。 */
    fun onError(message: String)
}
