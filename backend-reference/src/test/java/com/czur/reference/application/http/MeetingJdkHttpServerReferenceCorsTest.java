package com.czur.reference.application.http;

import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.speaker.SpeakerIdentityEnrollmentPort;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态参考 Web 的正常跨源写链：真实 loopback HTTP，不接数据库、模型或设备。
 * 登录和注册用计数端口验证网络分发；实际账号／会议归属由既有业务测试覆盖。
 */
class MeetingJdkHttpServerReferenceCorsTest {
    private static final String LOGIN = "/v1/reference/session:login";
    private static final String ENROLL = "/v1/meetings/mtg_cors/speakers/speaker-1:enroll";
    private static final String LOGIN_BODY = "{\"username\":\"reference\",\"password\":\"synthetic-cors-password\"}";

    @Test
    void loginPreflightDoesNotLogInAndSuccessAndFailureRemainReadable() throws Exception {
        try (var fixture = new Fixture(true, true)) {
            var preflight = fixture.preflight(LOGIN);
            assertPreflight(preflight);
            assertEquals(0, fixture.logins.get());
            assertEquals(0, fixture.verifications.get());
            assertEquals(0, fixture.enrollments.get());

            var loggedIn = fixture.post(LOGIN, LOGIN_BODY, false);
            assertEquals(200, loggedIn.statusCode());
            assertReadable(loggedIn);
            var rejected = fixture.post(LOGIN, "{\"username\":\"reference\",\"password\":\"wrong\"}", false);
            assertEquals(401, rejected.statusCode());
            assertReadable(rejected);
            assertEquals(2, fixture.logins.get());
            assertEquals(0, fixture.verifications.get());
        }
    }

    @Test
    void enrollmentPreflightSkipsBusinessAndPostKeepsOriginalBearerGate() throws Exception {
        try (var fixture = new Fixture(true, true)) {
            assertPreflight(fixture.preflight(ENROLL));
            assertEquals(0, fixture.verifications.get());
            assertEquals(0, fixture.enrollments.get());

            var anonymous = fixture.post(ENROLL, "{\"displayName\":\"参考人物\"}", false);
            assertEquals(401, anonymous.statusCode());
            assertReadable(anonymous);
            assertEquals(0, fixture.enrollments.get());

            var enrolled = fixture.post(ENROLL, "{\"displayName\":\"参考人物\"}", true);
            assertEquals(200, enrolled.statusCode());
            assertReadable(enrolled);
            assertTrue(enrolled.body().contains("person_cors"));
            assertTrue(enrolled.body().contains("参考人物"));
            assertEquals(1, fixture.verifications.get());
            assertEquals(1, fixture.enrollments.get());
        }
    }

    @Test
    void disabledExtensionsAndOtherBusinessPostsDoNotGainCors() throws Exception {
        try (var fixture = new Fixture(false, false)) {
            for (String path : new String[]{LOGIN, ENROLL}) {
                var preflight = fixture.preflight(path);
                assertNotEquals(204, preflight.statusCode());
                assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
                var response = fixture.post(path, "{}", false);
                assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            }
            assertEquals(0, fixture.logins.get());
            assertEquals(0, fixture.verifications.get());
            assertEquals(0, fixture.enrollments.get());
        }
        try (var fixture = new Fixture(true, true)) {
            assertTrue(fixture.preflight("/v1/meetings").headers()
                    .firstValue("Access-Control-Allow-Origin").isEmpty());
            assertTrue(fixture.post("/v1/meetings", "{}", false).headers()
                    .firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }

    private static void assertPreflight(java.net.http.HttpResponse<String> response) {
        assertEquals(204, response.statusCode());
        assertReadable(response);
        assertEquals("POST", response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
        assertEquals("Content-Type, Authorization", response.headers()
                .firstValue("Access-Control-Allow-Headers").orElseThrow());
        assertEquals("", response.body());
    }

    private static void assertReadable(java.net.http.HttpResponse<String> response) {
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty(),
                "参考页面显式使用 Bearer，不额外引入跨源 Cookie 身份");
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger logins = new AtomicInteger();
        private final AtomicInteger verifications = new AtomicInteger();
        private final AtomicInteger enrollments = new AtomicInteger();
        private final MeetingHttpRuntime runtime;
        private final MeetingJdkHttpServer server;
        private final HttpClient client = HttpClient.newHttpClient();

        private Fixture(boolean loginEnabled, boolean enrollmentEnabled) throws Exception {
            HttpErrorMapper errors = new HttpErrorMapper();
            MeetingQueryPort query = new MeetingQueryPort() {
                @Override public Result get(GetRequest request) {
                    throw new AssertionError("跨源登录／命名不应读取会议");
                }
                @Override public ListResult list(ListRequest request) {
                    throw new AssertionError("跨源登录／命名不应列出会议");
                }
            };
            IdentityProvider identity = request -> {
                verifications.incrementAndGet();
                return IdentityProviderResult.success(new VerifiedIdentity(new Subject("cors-subject"),
                        Set.of(), Set.of(MeetingPermissions.CREATE), Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2100-01-01T00:00:00Z"), "cors-credential"));
            };
            var stop = new AuthenticatedMeetingStopApplicationService("cors-scope", identity,
                    request -> { throw new AssertionError("预检／命名不应停止会议"); }, query);
            SessionLoginEndpoint login = loginEnabled ? new SessionLoginEndpoint() {
                @Override public String path() { return LOGIN; }
                @Override public HttpResponse handle(HttpRequest request) {
                    logins.incrementAndGet();
                    return LOGIN_BODY.equals(request.body())
                            ? HttpResponse.json(200, "{\"accessToken\":\"synthetic-cors-fixture-token\"}")
                            : errors.missingContext();
                }
            } : null;
            runtime = new MeetingHttpRuntime(query,
                    request -> { throw new AssertionError("跨源登录／命名不应删除会议"); },
                    (request, context) -> { throw new AssertionError("跨源登录／命名不应解码删除"); },
                    errors, stop, null, null, false, null, null, null, null, null, null, login);
            var resolver = new AuthenticatedMeetingHttpContextResolver("cors-scope", "cors-audience",
                    "cors-client", identity, errors);
            SpeakerIdentityEnrollmentEndpoint enrollment = enrollmentEnabled
                    ? new SpeakerIdentityEnrollmentEndpoint((context, meetingId, speakerId, name) -> {
                        assertEquals("cors-subject", context.identity().subject().subjectScope());
                        assertEquals("cors-scope", context.deploymentScope());
                        assertEquals("mtg_cors", meetingId);
                        assertEquals("speaker-1", speakerId);
                        enrollments.incrementAndGet();
                        return new SpeakerIdentityEnrollmentPort.Enrolled(
                                new SpeakerIdentityProvider.Person("person_cors", name), 2);
                    }, errors) : null;
            var dispatcher = new MeetingHttpDispatcher(runtime, errors, resolver, null, null, enrollment);
            server = new MeetingJdkHttpServer(new InetSocketAddress("127.0.0.1", 0), dispatcher, () -> true);
            server.start();
        }

        private java.net.http.HttpRequest.Builder request(String path) {
            return java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.address().getPort() + path)).timeout(Duration.ofSeconds(5))
                    .header("Origin", "https://reference-web.example.invalid");
        }

        private java.net.http.HttpResponse<String> preflight(String path) throws Exception {
            return client.send(request(path).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,authorization")
                    .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        }

        private java.net.http.HttpResponse<String> post(String path, String body, boolean bearer) throws Exception {
            var request = request(path).header("Content-Type", "application/json");
            if (bearer) request.header("Authorization", "Bearer synthetic-cors-fixture-token");
            return client.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        }

        @Override public void close() {
            server.close();
            runtime.close();
            client.close();
        }
    }
}
