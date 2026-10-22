package com.czur.aimeeting.sdk

import com.czur.aimeeting.model.SummaryAdapter
import com.czur.aimeeting.model.ModelHttpRequest
import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummaryResult
import com.czur.aimeeting.model.SummarySessionGrant
import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AndroidSummaryRuntimeTest {
    @Test fun adapterCanUseApiKeyPutAndItsOwnContentTypeWithoutAnExtraBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("plain result"))
            server.start()
            val grant = SummarySessionGrant("custom-summary", server.url("/summary").toString(),
                "2030-01-01T00:01:00Z", "fixture-temporary-token", mapOf("apiVersion" to "v2"))
            val adapter = object : SummaryAdapter {
                override fun id() = "custom-summary"
                override fun requestBody(input: SummaryInput) = input.transcript()
                override fun request(grant: SummarySessionGrant, input: SummaryInput) = ModelHttpRequest("PUT",
                    mapOf("x-api-key" to grant.token()!!, "api-version" to grant.initParams().getValue("apiVersion")),
                    "text/plain; charset=utf-8", requestBody(input))
                override fun parseResponse(response: String, language: String) =
                    SummaryResult(language, "标题", response, emptyList(), emptyList())
            }
            assertEquals("plain result", AndroidSummaryRuntime().summarize(grant, adapter,
                SummaryInput("ja-JP", "会議本文")).overview())
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("fixture-temporary-token", request.getHeader("x-api-key"))
            assertEquals("v2", request.getHeader("api-version"))
            assertNull(request.getHeader("Authorization"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("text/plain"))
            assertEquals("会議本文", request.body.readUtf8())
        }
    }

    @Test fun defaultSummarySupportsSignedUrlWithNoBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("result"))
            server.start()
            val adapter = object : SummaryAdapter {
                override fun id() = "signed-summary"
                override fun requestBody(input: SummaryInput) = "{}"
                override fun parseResponse(response: String, language: String) =
                    SummaryResult(language, "标题", response, emptyList(), emptyList())
            }
            AndroidSummaryRuntime().summarize(SummarySessionGrant("signed-summary",
                server.url("/signed?fixture=1").toString(), "2030-01-01T00:01:00Z", null), adapter,
                SummaryInput("en-US", "Final input"))
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("/signed?fixture=1", request.path)
        }
    }

    @Test fun postsFinalTranscriptWithTemporaryBearerAndReturnsCanonicalSummary() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"output\":{\"text\":\"已讨论实施方案\"}}"))
            server.start()
            val grant = SummarySessionGrant("test-summary", server.url("/api/v1/apps/app-test/completion").toString(),
                "2030-01-01T00:01:00Z", "st-test-temporary")
            assertFalse(grant.toString().contains("st-test-temporary"))
            val result = AndroidSummaryRuntime().summarize(grant, object : SummaryAdapter {
                override fun id(): String = "test-summary"
                override fun requestBody(input: SummaryInput): String = "{\"input\":{\"prompt\":\"" + input.transcript() + "\"}}"
                override fun parseResponse(response: String, language: String): SummaryResult {
                    if (!response.contains("已讨论实施方案")) throw IOException("响应不符")
                    return SummaryResult(language, "实施方案", "已讨论实施方案", listOf("先做样例"), emptyList())
                }
            }, SummaryInput("zh-CN", "今天讨论方案"))
            val request = server.takeRequest()
            assertEquals("/api/v1/apps/app-test/completion", request.path)
            assertEquals("Bearer st-test-temporary", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            assertTrue(request.body.readUtf8().contains("今天讨论方案"))
            assertEquals("实施方案", result.title())
        }
    }
}
