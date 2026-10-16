package com.czur.aimeeting.reference.mock
import org.junit.Assert.assertEquals

import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummaryResult
import org.json.JSONObject
import org.junit.Test
class MockSummaryAdapterTest {
    @Test fun mapsFinalTranscriptToStandardSummary() {
        val adapter = MockSummaryAdapter()
        val request = JSONObject(adapter.requestBody(SummaryInput(
                "zh-CN", "先讨论方案。\n再确定负责人。")))
        assertEquals("zh-CN", request.getString("language"))
        assertEquals("先讨论方案。\n再确定负责人。", request.getString("transcript"))
        val result = adapter.parseResponse("{\"title\":\"方案讨论\","
                + "\"overview\":\"已确定实施方向\","
                + "\"keyPoints\":[\"先做样例\"],\"actionItems\":[\"确认负责人\"]}", "zh-CN")
        assertEquals("方案讨论", result.title())
        assertEquals("已确定实施方向", result.overview())
        assertEquals("先做样例", result.keyPoints()[0])
        assertEquals("确认负责人", result.actionItems()[0])
    }
}
