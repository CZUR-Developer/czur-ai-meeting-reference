package com.czur.aimeeting.model;

/** 模型会话生命周期回调；可发生在非 UI 线程。 */
public interface ModelSessionListener {
    /** 模型已确认结束，之前收到的标准字幕可以提交后端并完成会议。 */
    void onFinished();

    /** 运行时无法继续本场会议；message 只包含可向用户展示的诊断信息。 */
    void onError(String message);
}
