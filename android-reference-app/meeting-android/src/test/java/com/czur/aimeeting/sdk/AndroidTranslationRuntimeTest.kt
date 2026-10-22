package com.czur.aimeeting.sdk

import com.czur.aimeeting.client.provider.SampleJsonTranslationAdapter
import com.czur.aimeeting.model.TranslationInput
import com.czur.aimeeting.model.TranslationSessionGrant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 使用真实回环 HTTP 验证公开样例请求／响应契约，不调用第三方模型或设备。 */
class AndroidTranslationRuntimeTest {
    @Test fun sampleAdapterSendsConfiguredApiKeyAndReturnsBoundCanonicalTranslation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"text\":\"こんにちは\",\"sourceLanguage\":\"zh-CN\"}"))
            server.start()
            val input = TranslationInput("reconnect-1:source-7", 3, "zh-CN", "ja-JP", "你好")
            val grant = TranslationSessionGrant(SampleJsonTranslationAdapter.ID, server.url("/translate").toString(),
                "2030-01-01T00:00:00Z", "fixture-translation-token", mapOf("tokenHeader" to "x-api-key"))
            val translated = AndroidTranslationRuntime().translate(grant, SampleJsonTranslationAdapter(), input)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("fixture-translation-token", request.getHeader("x-api-key"))
            assertNull(request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("ja-JP", body.getString("targetLanguage"))
            assertEquals("zh-CN", body.getString("sourceLanguage"))
            assertEquals("你好", body.getString("text"))
            assertEquals("reconnect-1:source-7", body.getString("sourceSegmentId"))
            assertEquals(3, body.getInt("sourceRevision"))
            assertEquals(input.sourceSegmentId(), translated.sourceSegmentId())
            assertEquals(3, translated.revision())
            assertEquals("ja-JP", translated.targetLanguage())
            assertEquals("こんにちは", translated.text())
            assertTrue(translated.isFinal())
        }
    }
}
