package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.server.HttpMeetingServerClient;
import com.czur.aimeeting.client.server.HttpMeetingSessionClient;
import com.czur.aimeeting.client.server.ReferenceLoginSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.net.URL;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;

/** 参考账号登录只验证与真实会议入口直接相关的请求和会话字段。 */
public final class HttpMeetingSessionClientAppTest {
    @Test
    public void logsInAndReturnsBearerTokenForMeetingCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"accessToken\":\"meeting-token\","
                    + "\"expiresAt\":\"2030-01-01T00:00:00Z\"}"));
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"meetingId\":\"mtg_login\",\"limits\":{\"maxMeetingDurationMs\":600000}}"));
            server.start();

            String backendUrl = server.url("/").toString();
            ReferenceLoginSession session = new HttpMeetingSessionClient(backendUrl)
                    .login("viewer", "test-password");
            new HttpMeetingServerClient(MeetingClientConfig.reference(backendUrl, session.accessToken())).createMeeting(
                    MeetingSetup.transcript("zh-CN", "https://result.example.test/index.html").toOptions());

            RecordedRequest login = server.takeRequest();
            RecordedRequest create = server.takeRequest();
            assertEquals("/v1/reference/session:login", login.getPath());
            assertNull(login.getHeader("Authorization"));
            JSONObject credentials = new JSONObject(login.getBody().readUtf8());
            assertEquals("viewer", credentials.getString("username"));
            assertEquals("test-password", credentials.getString("password"));
            assertEquals("2030-01-01T00:00:00Z", session.expiresAt());
            assertEquals("Bearer meeting-token", create.getHeader("Authorization"));
        }
    }

    @Test
    public void reportsRejectedCredentialsWithoutAConferenceToken() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.start();
            IOException failure = assertThrows(IOException.class,
                    () -> new HttpMeetingSessionClient(server.url("/").toString())
                            .login("viewer", "wrong-password"));
            assertEquals("账号或密码错误", failure.getMessage());
        }
    }

    @Test
    public void readsDeploymentCapabilitiesAfterLoginWithoutModelSecrets() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"specVersion\":\"v1\","
                    + "\"capabilities\":[\"transcript.realtime\",\"summary.structured\"],"
                    + "\"languages\":[\"zh-CN\"],\"audioProfiles\":[\"PCM_S16LE\"],"
                    + "\"resultLinkTtlMs\":86400000,\"resultEmailEnabled\":true,"
                    + "\"limits\":{\"maxMeetingDurationMs\":600000,"
                    + "\"maxAudioFrameBytes\":32000,\"eventReplayWindow\":100}}"));
            server.start();
            var options = new HttpMeetingSessionClient(server.url("/").toString())
                    .deploymentOptions();
            Set<String> capabilities = options.capabilities();
            assertEquals(Set.of(MeetingCapabilityState.TRANSCRIPT,
                    MeetingCapabilityState.SUMMARY), capabilities);
            assertEquals(86400000, options.resultLinkTtlMs());
            assertEquals(true, options.resultEmailEnabled());
            RecordedRequest discovery = server.takeRequest();
            assertEquals("GET", discovery.getMethod());
            assertEquals("/v1/capabilities", discovery.getPath());
            assertNull(discovery.getHeader("Authorization"));
        }
    }

    @Test
    public void loginRequiresHttpsExceptLoopbackDevelopmentAddress() throws Exception {
        assertThrows(IOException.class, () -> HttpMeetingSessionClient.validateLoginUrl(
                new URL("http://192.168.1.10:8080/v1/reference/session:login")));
        HttpMeetingSessionClient.validateLoginUrl(
                new URL("https://meeting.example.com/v1/reference/session:login"));
        HttpMeetingSessionClient.validateLoginUrl(
                new URL("http://localhost:8080/v1/reference/session:login"));
        HttpMeetingSessionClient.validateLoginUrl(
                new URL("http://127.0.0.1:8080/v1/reference/session:login"));
    }
}
