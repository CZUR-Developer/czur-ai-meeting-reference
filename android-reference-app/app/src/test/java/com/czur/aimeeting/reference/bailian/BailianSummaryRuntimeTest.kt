package com.czur.aimeeting.reference.bailian

import com.czur.aimeeting.client.provider.BailianSummaryAdapter
import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummarySessionGrant
import com.czur.aimeeting.sdk.AndroidSummaryRuntime
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 假应用 completion，不使用真实永久或短期材料。 */
class BailianSummaryRuntimeTest {
    @Test fun callsApplicationWithTemporaryBearerAndParsesStructuredText() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"output\":{\"text\":\"{\\\"title\\\":\\\"例会\\\","
                + "\\\"overview\\\":\\\"确定交付顺序\\\",\\\"keyPoints\\\":[\\\"先接协议\\\"],\\\"actionItems\\\":[] }\"}}"))
            server.start()
            val grant = SummarySessionGrant(BailianSummaryAdapter.ID, server.url("/api/v1/apps/app-local/completion").toString(), "2030-01-01T00:01:00Z", "st-local-only")
            val result = AndroidSummaryRuntime().summarize(grant, BailianSummaryAdapter(), SummaryInput("zh-CN", "今天讨论交付顺序。"))
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals("/api/v1/apps/app-local/completion", request.path)
            assertEquals("Bearer st-local-only", request.getHeader("Authorization"))
            assertTrue(JSONObject(request.body.readUtf8()).getJSONObject("input").getString("prompt").contains("今天讨论交付顺序。"))
            assertEquals("例会", result.title()); assertEquals("确定交付顺序", result.overview()); assertEquals("先接协议", result.keyPoints()[0])
        }
    }
}
