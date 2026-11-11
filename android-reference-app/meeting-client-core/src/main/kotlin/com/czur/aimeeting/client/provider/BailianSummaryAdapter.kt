package com.czur.aimeeting.client.provider

import com.czur.aimeeting.model.SummaryAdapter
import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummaryResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * 百炼 completion 文本协议，不持有 Key、AppId 或会议状态。
 *
 * @deprecated 纪要改由服务端生成后，SDK 不再在设备侧领取文本模型材料或调用本协议；
 * 仅为兼容仍直接引用它的参考 App／伙伴代码而保留。
 */
@Deprecated("服务端生成纪要后不再由设备调用；仅保留给参考 App 兼容")
class BailianSummaryAdapter : SummaryAdapter {
    override fun id(): String = ID
    override fun requestBody(input: SummaryInput): String {
        val prompt = "请根据以下已经确认的最终字幕生成会议纪要。仅输出一个 JSON 对象，" +
            "不要 Markdown 或解释。字段必须为 title、overview、keyPoints、actionItems；" +
            "后两项是字符串数组，没有明确待办时给空数组，不要臆造。\n" +
            "语言：${input.language()}\n最终字幕：\n${input.transcript()}"
        try { return JSONObject().put("input", JSONObject().put("prompt", prompt))
            .put("parameters", JSONObject()).toString() }
        catch (invalid: JSONException) { throw IllegalArgumentException("百炼总结请求无法编码") }
    }
    @Throws(IOException::class)
    override fun parseResponse(response: String, language: String): SummaryResult {
        try {
            var text = JSONObject(response).getJSONObject("output").getString("text").trim()
            if (text.startsWith("```")) {
                val firstLine = text.indexOf('\n')
                val lastFence = text.lastIndexOf("```")
                if (firstLine < 0 || lastFence <= firstLine) throw JSONException("fence")
                text = text.substring(firstLine + 1, lastFence).trim()
            }
            val summary = JSONObject(text)
            return SummaryResult(language, required(summary.getString("title"), 512),
                required(summary.getString("overview"), 16_384), items(summary.getJSONArray("keyPoints")),
                items(summary.getJSONArray("actionItems")))
        } catch (invalid: JSONException) { throw IOException("百炼纪要响应格式错误") }
        catch (invalid: IllegalArgumentException) {
            // 厂商正文可含用户转写，不附加 cause 或原文到日志／UI。
            throw IOException("百炼纪要响应格式错误")
        }
    }
    companion object {
        const val ID = "bailian-summary"
        private fun required(text: String, maxLength: Int): String {
            val value = text.trim()
            require(value.isNotEmpty() && value.length <= maxLength) { "summary text" }
            return value
        }
        private fun items(values: JSONArray): List<String> {
            require(values.length() <= 100) { "summary items" }
            return (0 until values.length()).map { required(values.getString(it), 4096) }
        }
    }
}
