package com.czur.aimeeting.client;

import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;

/** 所有回调使用配置的串行 callbackExecutor；不提供原始音频/模型消息/访问材料。 */
public interface MeetingListener {
    /** 本场采音/模型即将启动；单调起点及最大时长供 App 安排提醒、采集 offset 和自动 Stop。 */
    default void onStarted(MeetingStarted started) { }
    /** 状态、可停止性及实际能力变化；RUNNING 只表示本场已开始，不代表结果 READY。 */
    default void onState(MeetingSessionState state) { }
    /** 页面/悬浮窗共用不可变快照，App 只按源侧、swapped 与 final 呈现，不自行解析/路由。 */
    default void onDisplay(MeetingDisplaySnapshot display) { }
    /** 非空原文到达即排入回调队列，值与 startedAtMs 使用同一单调时基，不等待语种显示窗口。 */
    default void onTranscriptActivity(long nowMs) { }
    /** 实际音源模式；组合层仍独占同一音源，App 不应再启动基础入口订阅。 */
    default void onAudioInputMode(AudioInputMode mode) { }
    /** App 处理权限/UI 提示；SDK 不申请权限、不自动重试。本场仍可正常 Stop，但不主动交付结果。 */
    default void onAudioError(AudioInputErrorCode code, String message) { }
    /**
     * App 停止截图 tick，在工作线程完成最后 capture → submitPresentation → close 采集后
     * 调用 continuation；没有采集时直接调用。不可在此阻塞 UI 或遗漏 continuation。
     * continuation 可从工作线程调用，仅首次有效；提交最后截图的调用必须发生在它之前。
     * 延续后不再接纳截图，再停止音源、排完模型尾事件，最后调用后端 Stop。
     */
    default void beforeStop(Runnable continuation) { continuation.run(); }
    /**
     * 实际READY清单先于READY onState。这里只保存结果，收到READY状态后再通用派发动作，
     * 不在此关闭client或宣称通知成功。正文／通知失败保持同一READY，允许宿主手动再执行。
     */
    default void onResult(MeetingResult result) { }
}
