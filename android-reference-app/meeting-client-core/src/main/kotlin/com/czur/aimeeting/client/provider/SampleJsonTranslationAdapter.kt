package com.czur.aimeeting.client.provider

import com.czur.aimeeting.model.*
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * 独立文本翻译的最小公开协议样例。可直接注册 sample-json-translation；伙伴服务接收
 * sourceSegmentId/sourceRevision/sourceLanguage/targetLanguage/text，返回 text 和可选
 * sourceLanguage。这里不内置任何模型 Key、地址、账号或固定中英语言表。
 *
 * Grant 可选 tokenHeader（默认 Authorization）和 tokenPrefix；Authorization 的默认前缀
 * 是 Bearer，其他头默认无前缀，例如 x-api-key。空 token 不发送鉴权头；其他厂商协议由
 * 伙伴另写 Adapter，而非修改会议页面、音源或状态机。
 */
class SampleJsonTranslationAdapter : TranslationAdapter {
    override fun id(): String = ID
    override fun request(grant: TranslationSessionGrant, input: TranslationInput): ModelHttpRequest {
        val header = grant.initParams()["tokenHeader"] ?: "Authorization"
        val prefix = grant.initParams()["tokenPrefix"] ?: if (header.equals("Authorization", true)) "Bearer" else ""
        val headers = if (grant.token().isNullOrBlank()) emptyMap() else
            mapOf(header to if (prefix.isBlank()) grant.token()!! else "$prefix ${grant.token()}")
        val body = JSONObject().put("sourceSegmentId", input.sourceSegmentId())
            .put("sourceRevision", input.sourceRevision()).put("sourceLanguage", input.sourceLanguage())
            .put("targetLanguage", input.targetLanguage()).put("text", input.text()).toString()
        return ModelHttpRequest("POST", headers, "application/json; charset=utf-8", body)
    }

    @Throws(IOException::class)
    override fun parseResponse(response: String, input: TranslationInput): TranslationEvent = try {
        val json = JSONObject(response)
        input.result(json.getString("text"), json.optString("sourceLanguage", input.sourceLanguage()))
    } catch (invalid: JSONException) { throw IOException("独立翻译响应格式错误", invalid) }

    companion object { const val ID = "sample-json-translation" }
}
