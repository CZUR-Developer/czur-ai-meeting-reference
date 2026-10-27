package com.czur.aimeeting.client.server

import java.io.IOException

/**
 * 后端返回了调用方未接受的 HTTP 状态码。
 *
 * <p>调用方通常只关心成功/失败；少数可重放操作（例如标准纪要已提交）需要区分
 * 409，避免一次确认丢失就永久无法收尾。正文超过部署预算时 body 带稳定错误码
 * {@code CONTENT_TOO_LARGE}，调用方据此停止重试同一事件，而不是把它当普通失败。
 * 消息里不带响应正文，只带状态码与稳定错误码。</p>
 */
class MeetingHttpStatusException(val status: Int, val errorCode: String? = null) :
    IOException(if (errorCode == null) "后端请求失败，HTTP $status"
        else "后端请求失败，HTTP $status $errorCode") {

    /** 服务端已明确拒绝该事件：正文累计超过部署预算，原样重试不会成功。 */
    val isContentTooLarge: Boolean
        get() = status == HTTP_CONTENT_TOO_LARGE || errorCode == CODE_CONTENT_TOO_LARGE

    companion object {
        const val HTTP_CONTENT_TOO_LARGE = 413
        const val CODE_CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE"
    }
}
