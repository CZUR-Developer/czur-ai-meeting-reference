package com.czur.aimeeting.client

/**
 * 会议过程中的非错误提示事件。SDK 只给事件码，宿主决定是否展示及展示什么话术。
 */
enum class MeetingNotice {
    /** 音频输入终止；具体原因见 onAudioError 的错误码。 */
    AUDIO_INPUT_ENDED,
    /** 模型会话已结束，会议仍可停止。 */
    MODEL_SESSION_ENDED,
    /** 独立翻译尚未完成，原文仍在保存。 */
    TRANSLATION_PENDING,
    /** 标准字幕或译文尚未全部确认。 */
    TRANSCRIPT_UNCONFIRMED,
    /**
     * 服务端按部署正文预算拒绝了标准事件（HTTP 413／CONTENT_TOO_LARGE）。
     * SDK 已停止继续上传，未确认的尾部事件不会重试；已接受内容仍可正常停止。
     */
    CONTENT_TOO_LARGE,
    /** 停止模型输入失败，可再次确认停止。 */
    STOP_RETRY_REQUIRED,
    /** 没有最终字幕，纪要不会生成。 */
    NO_FINAL_TRANSCRIPT,
}
