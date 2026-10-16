package com.czur.aimeeting.reference.bailian
import com.czur.aimeeting.client.provider.BailianSummaryAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

import com.czur.aimeeting.model.SummaryInput
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
class BailianSummaryAdapterTest {
    @Test fun encodesPromptAndReadsOutputTextAsCanonicalSummary() {
        val adapter = BailianSummaryAdapter()
        val request = JSONObject(adapter.requestBody(
                SummaryInput("zh-CN", "今天讨论了交付方案。")))
        val prompt = request.getJSONObject("input").getString("prompt")
        assertTrue(prompt.contains("今天讨论了交付方案。"))
        assertTrue(prompt.contains("仅输出一个 JSON 对象"))
        assertFalse(request.toString().contains("DASHSCOPE_API_KEY"))
        val canonical = JSONObject().put("title", "交付方案")
                .put("overview", "确认了上线范围。")
                .put("keyPoints", JSONArray().put("先做最小链路"))
                .put("actionItems", JSONArray().put("周五前复核"))
        val provider = JSONObject().put("output", JSONObject()
                .put("text", canonical.toString())).toString()
        val result = adapter.parseResponse(provider, "zh-CN")
        assertEquals("交付方案", result.title())
        assertEquals("确认了上线范围。", result.overview())
        assertEquals("先做最小链路", result.keyPoints()[0])
        assertEquals("周五前复核", result.actionItems()[0])
        val fenced = JSONObject().put("output", JSONObject()
                .put("text", "```json\n" + canonical + "\n```" )).toString()
        assertEquals(result, adapter.parseResponse(fenced, "zh-CN"))
    }

    @Test fun rejectsNonStructuredOutputWithoutEchoingProviderText() {
        val failure = assertThrows(IOException::class.java) { BailianSummaryAdapter().parseResponse(
                        "{\"output\":{\"text\":\"private transcript\"}}", "zh-CN") }
        assertEquals("百炼纪要响应格式错误", failure.message)
        assertEquals(null, failure.cause)
    }
}
