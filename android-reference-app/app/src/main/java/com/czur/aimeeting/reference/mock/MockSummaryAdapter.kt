package com.czur.aimeeting.reference.mock

import com.czur.aimeeting.model.SummaryAdapter
import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummaryResult
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 固定文本总结样例，只验证标准纪要提交和读回，不调用外部模型。 */
class MockSummaryAdapter : SummaryAdapter {
    override fun id() = ID
    override fun requestBody(input: SummaryInput): String = try {
        JSONObject().put("language", input.language()).put("transcript", input.transcript()).toString()
    } catch (invalid: JSONException) { throw IllegalStateException("无法编码模拟总结请求", invalid) }

    @Throws(IOException::class)
    override fun parseResponse(response: String, language: String): SummaryResult = try {
        val body = JSONObject(response)
        SummaryResult(language, body.getString("title"), body.getString("overview"),
            strings(body.getJSONArray("keyPoints")), strings(body.getJSONArray("actionItems")))
    } catch (invalid: JSONException) { throw IOException("模拟总结响应格式错误", invalid) }

    private fun strings(values: JSONArray): List<String> = (0 until values.length()).map { values.getString(it) }
    companion object { const val ID = "mock-summary" }
}
