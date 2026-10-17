package com.czur.aimeeting.model

import java.io.IOException

/** 构建期文本模型协议 Adapter。SDK 发 HTTP，Adapter 构造／解析协议，不接触长期 Key 或持久化。 */
interface SummaryAdapter {
    fun id(): String
    /**
     * 生成本场一次文本请求；在 SDK 的 IO 线程调用。默认保留旧 POST/JSON/Bearer 协议，
     * 已有实现只需继续提供 requestBody。不同鉴权或内容类型可覆盖本方法，从 Grant
     * 选择材料；SDK 不在返回描述之外追加默认头，也不在 Adapter 中处理模型网络。
     */
    fun request(grant: SummarySessionGrant, input: SummaryInput): ModelHttpRequest =
        ModelHttpRequest("POST", ModelHttpRequest.bearerHeaders(grant.token()) + ("Accept" to "application/json"),
            "application/json; charset=utf-8", requestBody(input))
    /** 旧默认请求的文本正文；覆盖 request 后是否复用由实现自行决定。 */
    fun requestBody(input: SummaryInput): String
    @Throws(IOException::class)
    fun parseResponse(response: String, language: String): SummaryResult
}
