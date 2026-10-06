package com.czur.aimeeting.reference.bailian;

import com.czur.aimeeting.client.provider.BailianSummaryAdapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.sdk.AndroidSummaryRuntime;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.Test;

/** SDK 模式的假应用 completion；真实永久/短期材料均不参与测试。 */
public final class BailianSummaryRuntimeTest {
    @Test public void callsApplicationWithTemporaryBearerAndParsesStructuredText()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"output\":{\"text\":\"{\\\"title\\\":\\\"例会\\\","
                            + "\\\"overview\\\":\\\"确定交付顺序\\\","
                            + "\\\"keyPoints\\\":[\\\"先接协议\\\"],"
                            + "\\\"actionItems\\\":[] }\"}}"));
            server.start();
            var grant = new SummarySessionGrant(BailianSummaryAdapter.ID,
                    server.url("/api/v1/apps/app-local/completion").toString(),
                    "2030-01-01T00:01:00Z", "st-local-only");
            var result = new AndroidSummaryRuntime().summarize(grant,
                    new BailianSummaryAdapter(),
                    new SummaryInput("zh-CN", "今天讨论交付顺序。"));
            var request = server.takeRequest();
            assertEquals("POST", request.getMethod());
            assertEquals("/api/v1/apps/app-local/completion", request.getPath());
            assertEquals("Bearer st-local-only", request.getHeader("Authorization"));
            assertTrue(new JSONObject(request.getBody().readUtf8()).getJSONObject("input")
                    .getString("prompt").contains("今天讨论交付顺序。"));
            assertEquals("例会", result.title());
            assertEquals("确定交付顺序", result.overview());
            assertEquals("先接协议", result.keyPoints().get(0));
        }
    }
}
