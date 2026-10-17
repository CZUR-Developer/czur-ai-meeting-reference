package com.czur.aimeeting.model

import java.util.Collections

/**
 * Adapter 交给 SDK 执行的一次文本 HTTP 请求描述；不打开连接、不持有流或 Android 对象。
 * 地址来自本场 Grant，PCM 仍走既有实时传输；这里仅描述方法、请求头和可选文本正文，
 * 不构成通用网络客户端。SDK 在工作线程发送并关闭响应，Adapter 只接收完整响应字符串。
 *
 * headers 按本次请求完整提供。覆盖默认 request 时，SDK 不额外注入 Bearer，便于使用
 * x-api-key、签名 URL 或无需鉴权的伙伴模型。正文和请求头可能含本场材料，不得记录日志。
 * contentType 仅在存在 body 时使用；无正文传 null。所有集合在构造时复制。
 */
class ModelHttpRequest @JvmOverloads constructor(
    private val method: String,
    headers: Map<String, String> = emptyMap(),
    private val contentType: String? = null,
    private val body: String? = null,
) {
    private val headers = Collections.unmodifiableMap(LinkedHashMap(headers))
    fun method(): String = method
    fun headers(): Map<String, String> = headers
    fun contentType(): String? = contentType
    fun body(): String? = body
    override fun toString(): String = "ModelHttpRequest[method=$method, headers=redacted, body=redacted]"

    companion object {
        /** 旧默认协议的鉴权头；空 token 用于已签名 URL，不发送空 Bearer。 */
        @JvmStatic fun bearerHeaders(token: String?): Map<String, String> =
            if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
    }
}
