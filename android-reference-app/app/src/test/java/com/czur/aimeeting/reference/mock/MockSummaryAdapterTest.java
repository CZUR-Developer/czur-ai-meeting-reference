package com.czur.aimeeting.reference.mock;

import static org.junit.Assert.assertEquals;

import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import org.json.JSONObject;
import org.junit.Test;

public final class MockSummaryAdapterTest {
    @Test public void mapsFinalTranscriptToStandardSummary() throws Exception {
        MockSummaryAdapter adapter = new MockSummaryAdapter();
        JSONObject request = new JSONObject(adapter.requestBody(new SummaryInput(
                "zh-CN", "先讨论方案。\n再确定负责人。")));
        assertEquals("zh-CN", request.getString("language"));
        assertEquals("先讨论方案。\n再确定负责人。", request.getString("transcript"));
        SummaryResult result = adapter.parseResponse("{\"title\":\"方案讨论\","
                + "\"overview\":\"已确定实施方向\","
                + "\"keyPoints\":[\"先做样例\"],\"actionItems\":[\"确认负责人\"]}", "zh-CN");
        assertEquals("方案讨论", result.title());
        assertEquals("已确定实施方向", result.overview());
        assertEquals("先做样例", result.keyPoints().get(0));
        assertEquals("确认负责人", result.actionItems().get(0));
    }
}
