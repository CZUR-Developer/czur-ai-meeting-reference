package com.czur.aimeeting.sdk

import com.czur.aimeeting.model.SummaryAdapter
import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummaryResult
import com.czur.aimeeting.model.SummaryRuntime
import com.czur.aimeeting.model.SummarySessionGrant
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** 一次性文本模型请求；Adapter 处理厂商正文，SDK 不持久化文本或输出完整地址/材料。 */
class AndroidSummaryRuntime internal constructor(private val http: OkHttpClient) : SummaryRuntime {
    constructor() : this(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build())

    @Throws(IOException::class)
    override fun summarize(grant: SummarySessionGrant, adapter: SummaryAdapter, input: SummaryInput): SummaryResult {
        require(grant.adapterId() == adapter.id()) { "总结 Adapter 与本场模型授权不匹配" }
        val request = ModelHttpRequests.build(grant.endpoint(), adapter.request(grant, input))
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("文本模型请求失败，HTTP " + response.code)
            val body = response.body ?: throw IOException("文本模型没有响应正文")
            return adapter.parseResponse(body.string(), input.language())
        }
    }
}
