package com.czur.aimeeting.reference.bailian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.SummaryInput;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class BailianSummaryAdapterTest {
    @Test public void encodesPromptAndReadsOutputTextAsCanonicalSummary() throws Exception {
        BailianSummaryAdapter adapter = new BailianSummaryAdapter();
        JSONObject request = new JSONObject(adapter.requestBody(
                new SummaryInput("zh-CN", "今天讨论了交付方案。")));
        String prompt = request.getJSONObject("input").getString("prompt");
        assertTrue(prompt.contains("今天讨论了交付方案。"));
        assertTrue(prompt.contains("仅输出一个 JSON 对象"));
        assertFalse(request.toString().contains("DASHSCOPE_API_KEY"));

        JSONObject canonical = new JSONObject().put("title", "交付方案")
                .put("overview", "确认了上线范围。")
                .put("keyPoints", new JSONArray().put("先做最小链路"))
                .put("actionItems", new JSONArray().put("周五前复核"));
        String provider = new JSONObject().put("output", new JSONObject()
                .put("text", canonical.toString())).toString();
        var result = adapter.parseResponse(provider, "zh-CN");
        assertEquals("交付方案", result.title());
        assertEquals("确认了上线范围。", result.overview());
        assertEquals("先做最小链路", result.keyPoints().get(0));
        assertEquals("周五前复核", result.actionItems().get(0));

        String fenced = new JSONObject().put("output", new JSONObject()
                .put("text", "```json\n" + canonical + "\n```" )).toString();
        assertEquals(result, adapter.parseResponse(fenced, "zh-CN"));
    }

    @Test public void rejectsNonStructuredOutputWithoutEchoingProviderText() {
        IOException failure = assertThrows(IOException.class, () ->
                new BailianSummaryAdapter().parseResponse(
                        "{\"output\":{\"text\":\"private transcript\"}}", "zh-CN"));
        assertEquals("百炼纪要响应格式错误", failure.getMessage());
        assertEquals(null, failure.getCause());
    }
}
