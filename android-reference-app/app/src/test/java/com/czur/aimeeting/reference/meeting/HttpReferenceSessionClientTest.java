package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.net.URL;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;

/** 参考账号登录只验证与真实会议入口直接相关的请求和会话字段。 */
public final class HttpReferenceSessionClientTest {
    @Test
    public void logsInAndReturnsBearerTokenForMeetingCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"accessToken\":\"meeting-token\","
                    + "\"expiresAt\":\"2030-01-01T00:00:00Z\"}"));
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"meetingId\":\"mtg_login\"}"));
            server.start();

            String backendUrl = server.url("/").toString();
            ReferenceLoginSession session = new HttpReferenceSessionClient(backendUrl)
                    .login("viewer", "test-password");
            new HttpReferenceMeetingClient(backendUrl, session.accessToken()).createMeeting();

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
                    () -> new HttpReferenceSessionClient(server.url("/").toString())
                            .login("viewer", "wrong-password"));
            assertEquals("账号或密码错误", failure.getMessage());
        }
    }

    @Test
    public void loginRequiresHttpsExceptLoopbackDevelopmentAddress() throws Exception {
        assertThrows(IOException.class, () -> HttpReferenceSessionClient.validateLoginUrl(
                new URL("http://192.168.1.10:8080/v1/reference/session:login")));
        HttpReferenceSessionClient.validateLoginUrl(
                new URL("https://meeting.example.com/v1/reference/session:login"));
        HttpReferenceSessionClient.validateLoginUrl(
                new URL("http://localhost:8080/v1/reference/session:login"));
        HttpReferenceSessionClient.validateLoginUrl(
                new URL("http://127.0.0.1:8080/v1/reference/session:login"));
    }
}
