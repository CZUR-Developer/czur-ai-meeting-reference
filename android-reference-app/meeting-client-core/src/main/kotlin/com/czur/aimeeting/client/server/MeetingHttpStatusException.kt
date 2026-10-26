package com.czur.aimeeting.client.server

import java.io.IOException

/**
 * 后端返回了调用方未接受的 HTTP 状态码。
 *
 * <p>调用方通常只关心成功/失败；少数可重放操作（例如标准纪要已提交）需要区分
 * 409，避免一次确认丢失就永久无法收尾。消息里不带响应正文。</p>
 */
class MeetingHttpStatusException(val status: Int) : IOException("后端请求失败，HTTP $status")
