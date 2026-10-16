package com.czur.aimeeting.reference.meeting
import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.server.HttpMeetingServerClient
import com.czur.aimeeting.client.server.HttpMeetingSessionClient
import com.czur.aimeeting.client.server.ReferenceLoginSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows

import java.io.IOException
import java.net.URL
import java.util.Set
import java.util.List
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Test
/** 参考账号登录只验证与真实会议入口直接相关的请求和会话字段。 */
class HttpMeetingSessionClientAppTest {
    @Test fun logsInAndReturnsBearerTokenForMeetingCalls() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{"
                    + "\"accessToken\":\"meeting-token\","
                    + "\"expiresAt\":\"2030-01-01T00:00:00Z\"}"))
            server.enqueue(MockResponse().setResponseCode(201)
                    .setBody("{\"meetingId\":\"mtg_login\",\"limits\":{\"maxMeetingDurationMs\":600000}}"))
            server.start()
            val backendUrl = server.url("/").toString()
            val session = HttpMeetingSessionClient(backendUrl)
                    .login("viewer", "test-password")
            HttpMeetingServerClient(MeetingClientConfig.reference(backendUrl, session.accessToken())).createMeeting(
                    MeetingSetup.transcript("zh-CN", "https://result.example.test/index.html").toOptions())
            val login = server.takeRequest()
            val create = server.takeRequest()
            assertEquals("/v1/reference/session:login", login.path)
            assertNull(login.getHeader("Authorization"))
            val credentials = JSONObject(login.body.readUtf8())
            assertEquals("viewer", credentials.getString("username"))
            assertEquals("test-password", credentials.getString("password"))
            assertEquals("2030-01-01T00:00:00Z", session.expiresAt())
            assertEquals("Bearer meeting-token", create.getHeader("Authorization"))
        }
    }

    @Test fun reportsRejectedCredentialsWithoutAConferenceToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val failure = assertThrows(IOException::class.java) {
                HttpMeetingSessionClient(server.url("/").toString()).login("viewer", "wrong-password")
            }
            assertEquals("账号或密码错误", failure.message)
        }
    }

    @Test fun readsDeploymentCapabilitiesAfterLoginWithoutModelSecrets() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{"
                    + "\"specVersion\":\"v1\","
                    + "\"capabilities\":[\"transcript.realtime\",\"summary.structured\"],"
                    + "\"languages\":[\"zh-CN\"],\"audioProfiles\":[\"PCM_S16LE\"],"
                    + "\"resultLinkTtlMs\":86400000,\"resultEmailEnabled\":true,"
                    + "\"limits\":{\"maxMeetingDurationMs\":600000,"
                    + "\"maxAudioFrameBytes\":32000,\"eventReplayWindow\":100}}"))
            server.start()
            val options = HttpMeetingSessionClient(server.url("/").toString())
                    .deploymentOptions()
            val capabilities = options.capabilities()
            assertEquals(setOf(MeetingCapabilityState.TRANSCRIPT,
                    MeetingCapabilityState.SUMMARY), capabilities)
            assertEquals(86400000, options.resultLinkTtlMs())
            assertEquals(true, options.resultEmailEnabled())
            assertEquals(listOf("zh-CN"), options.languages())
            val discovery = server.takeRequest()
            assertEquals("GET", discovery.method)
            assertEquals("/v1/capabilities", discovery.path)
            assertNull(discovery.getHeader("Authorization"))
        }
    }

    @Test fun loginRequiresHttpsExceptLoopbackDevelopmentAddress() {
        assertThrows(IOException::class.java) { HttpMeetingSessionClient.validateLoginUrl(
                URL("http://192.168.1.10:8080/v1/reference/session:login")) }
        HttpMeetingSessionClient.validateLoginUrl(
                URL("https://meeting.example.com/v1/reference/session:login"))
        HttpMeetingSessionClient.validateLoginUrl(
                URL("http://localhost:8080/v1/reference/session:login"))
        HttpMeetingSessionClient.validateLoginUrl(
                URL("http://127.0.0.1:8080/v1/reference/session:login"))
    }
}
