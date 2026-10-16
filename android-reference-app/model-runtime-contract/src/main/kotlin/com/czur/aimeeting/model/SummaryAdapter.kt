package com.czur.aimeeting.model

import java.io.IOException

/** 构建期文本模型协议 Adapter。SDK 发 HTTP，Adapter 构造／解析协议，不接触长期 Key 或持久化。 */
interface SummaryAdapter {
    fun id(): String
    fun requestBody(input: SummaryInput): String
    @Throws(IOException::class)
    fun parseResponse(response: String, language: String): SummaryResult
}
