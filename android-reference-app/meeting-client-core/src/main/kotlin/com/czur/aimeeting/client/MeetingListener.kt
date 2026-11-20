package com.czur.aimeeting.client

import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.AudioInputLevel
import com.czur.aimeeting.model.AudioInputMode

/** 通知使用配置的串行 callbackExecutor，不提供原始音频、协议消息或访问材料。 */
interface MeetingListener {
    /** 采音／模型即将开始，单调起点和最长时长供 App 提醒、截图定位与自动停止。 */
    fun onStarted(started: MeetingStarted) {}
    /** 实际阶段、可停止性及能力；RUNNING 不表示结果 READY。 */
    fun onState(state: MeetingSessionState) {}
    /** 页面和悬浮窗共用快照，App 仅呈现，不解析模型或自行重路由。 */
    fun onDisplay(display: MeetingDisplaySnapshot) {}
    /** 非空原文的活跃单调时间，不等语种窗口。 */
    fun onTranscriptActivity(nowMs: Long) {}
    /** 本场唯一输入的模式，App 不再打开基础订阅。 */
    fun onAudioInputMode(mode: AudioInputMode) {}
    /**
     * 同一实际输入流的 0～100 显示电平，约 300ms 一次，不另开音源，不参与计费／无声判断。
     * 通知经串行执行器；停止后不再通知，App 在停止状态复位 UI。
     */
    fun onAudioLevel(level: AudioInputLevel) {}
    /**
     * SDK 不申请权限或重试；输入错误后仍可正常停止，但不主动交付结果。
     * 宿主应重写本方法并按码映射文案；原因只写 SDK 日志。
     */
    fun onAudioError(code: AudioInputErrorCode) { onAudioError(code, code.name) }
    /** 旧入口；新代码只回错误码名称，不再回 SDK 文案。 */
    fun onAudioError(code: AudioInputErrorCode, message: String) {}
    /**
     * App 在停止边界冻结末帧，异步完成保存／归档后续接；无采集时直接续接，不阻塞 UI。
     * SDK 停输入已并行启动，不等待此 ACK；仅首次续接有效，它只释放最终发布及后端 Stop 屏障。
     */
    fun beforeStop(continuation: Runnable) { continuation.run() }
    /** READY 清单先于 READY 状态；这里只保存结果，正文和通知由结果动作按需执行。 */
    fun onResult(result: MeetingResult) {}
}
