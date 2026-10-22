package com.czur.aimeeting.sdk

import com.czur.aimeeting.model.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 可选逐段文本翻译。只执行 Adapter 的一次 HTTP 文本请求，调用方在 IO 队列顺序调用；
 * 不开音源、不持会议状态、不自行选择目标语言。响应由 use 关闭，模型正文不进入日志。
 */
class AndroidTranslationRuntime internal constructor(private val http: OkHttpClient) : TranslationRuntime {
    constructor() : this(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build())

    @Throws(IOException::class)
    override fun translate(grant: TranslationSessionGrant, adapter: TranslationAdapter, input: TranslationInput): TranslationEvent {
        require(grant.adapterId() == adapter.id()) { "翻译 Adapter 与本场材料不匹配" }
        val request = ModelHttpRequests.build(grant.endpoint(), adapter.request(grant, input))
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("文本翻译请求失败，HTTP " + response.code)
            val body = response.body ?: throw IOException("文本翻译没有响应正文")
            adapter.parseResponse(body.string(), input)
        }
    }
}
