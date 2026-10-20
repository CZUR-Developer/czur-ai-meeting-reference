package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.speaker.SpeakerNameEditPort;
import com.czur.reference.application.port.speaker.SpeakerResultAccess;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 不依赖数据库；验证原分发顺序及同一dispatcher被真实JDK listener消费。 */
class MeetingHttpDispatcherTest {
    private static final String ID = "mtg_dispatcher_test";
    private static final String DETAIL = "/v1/meetings/" + ID;

    @Test
    void absentResolverKeepsReadClosedAndEmailDisabled() {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = new MeetingHttpDispatcher(fixture.runtime, fixture.errors);
            assertEquals(401, dispatcher.handle(request("GET", DETAIL, "owner", "")).status());
            assertEquals(501, dispatcher.handle(emailRequest("owner")).status());
            assertEquals(404, dispatcher.handle(request("GET", "/unknown", "owner", "")).status());
            assertEquals(0, fixture.identityRequests.size());
            assertEquals(0, fixture.queryCalls);
        }
    }

    @Test
    void readsAndDeletesUseExistingTrustedResolverAndPassContextToPorts() {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = fixture.dispatcher(null);
            assertEquals(401, dispatcher.handle(request("GET", DETAIL, null, "")).status());
            assertEquals(0, fixture.queryCalls);
            assertEquals(404, dispatcher.handle(request("GET", DETAIL, "owner", "")).status());
            assertEquals(1, fixture.queryCalls);
            assertEquals("trusted-deployment", fixture.lastContext.deploymentScope());
            assertEquals("stable-owner", fixture.lastContext.identity().subject().subjectScope());
            assertEquals("trusted-audience", fixture.identityRequests.getFirst().audience());
            assertEquals("trusted-client", fixture.identityRequests.getFirst().clientId());

            assertEquals(403, dispatcher.handle(request("DELETE", DETAIL, "reader", "")).status());
            assertEquals(0, fixture.deleteCalls);
            var delete = new HttpRequest("DELETE", DETAIL,
                    Map.of("authorization", "Bearer owner",
                            "idempotency-key", "dispatcher-delete-key-12345"), "");
            assertEquals(404, dispatcher.handle(delete).status());
            assertEquals(1, fixture.deleteCalls);
            assertEquals(3, fixture.identityRequests.size());
            fixture.assertCredentialsClosed();
        }
    }

    @Test
    void clientWriteUsesExistingPermissionWhileCreateStopAndUnknownStayInRuntime() {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = fixture.dispatcher(null);
            String write = DETAIL + "/usage:submit";
            assertEquals(401, dispatcher.handle(request("POST", write, null, "")).status());
            assertEquals(403, dispatcher.handle(request("POST", write, "reader", "")).status());
            assertEquals(501, dispatcher.handle(request("POST", write, "owner", "")).status());
            int verified = fixture.identityRequests.size();
            assertEquals(501, dispatcher.handle(request("POST", "/v1/meetings", "owner", "{}")).status());
            assertEquals(501, dispatcher.handle(request("POST", DETAIL + ":stop", "owner", "{}")).status());
            assertEquals(404, dispatcher.handle(request("POST", DETAIL + "/unknown", "owner", "")).status());
            assertEquals(verified, fixture.identityRequests.size());
            fixture.assertCredentialsClosed();
        }
    }

    @Test
    void speakerEditingIsOptInAndKeepsTheExistingAuthenticatedWriteContext() {
        try (Fixture fixture = new Fixture()) {
            String path = DETAIL + "/speakers/s1:rename";
            String body = "{\"displayName\":\"名字\",\"scope\":\"ALL\",\"remember\":false}";
            assertEquals(501, fixture.dispatcher(null).handle(request("POST", path, "owner", body)).status());
            var calls = new AtomicInteger();
            var endpoint = new SpeakerNameEditEndpoint((context, id, speaker, edit) -> {
                calls.incrementAndGet();
                assertEquals("stable-owner", context.identity().subject().subjectScope());
                assertEquals("s1", speaker);
                return new com.czur.reference.application.port.speaker.SpeakerNameEditPort.Updated(2, 3,
                        com.czur.reference.application.port.speaker.SpeakerNameEditPort.Registration.NOT_REQUESTED);
            }, fixture.errors);
            var dispatcher = new MeetingHttpDispatcher(fixture.runtime, fixture.errors,
                    new AuthenticatedMeetingHttpContextResolver("trusted-deployment", "trusted-audience",
                            "trusted-client", fixture.provider, fixture.errors), null, null, null, endpoint);
            assertTrue(dispatcher.supportsReferenceBrowserPost(path));
            assertEquals(401, dispatcher.handle(request("POST", path, null, body)).status());
            assertEquals(403, dispatcher.handle(request("POST", path, "reader", body)).status());
            assertEquals(200, dispatcher.handle(request("POST", path, "owner", body)).status());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void resultLinkOnlyAuthorizesThisMeetingWithoutInventingAnOwnerIdentity() {
        try (Fixture fixture = new Fixture()) {
            var resolver = new AuthenticatedMeetingHttpContextResolver("trusted-deployment", "trusted-audience",
                    "trusted-client", fixture.provider, fixture.errors);
            MeetingManifestPort manifest = request -> {
                assertEquals(ID, request.meetingId());
                assertEquals(123, request.genTime());
                Instant now = Instant.now();
                return new MeetingManifestPort.Found(new MeetingManifest("0.1", ID, 1,
                        new MeetingManifest.Profile("reference-asr", 1), "参考会议", ManifestStatus.READY,
                        now, now, 0, new MeetingManifest.Languages("zh-CN", List.of()), List.of(), List.of()));
            };
            var policy = SpeakerResultAccessPolicy.resultLink(resolver, manifest, "trusted-deployment", fixture.errors);
            assertFalse(policy.requiresLogin());
            var calls = new AtomicInteger();
            var port = new SpeakerNameEditPort() {
                @Override public Result edit(MeetingCommandContext context, String id, String speaker, Edit edit) {
                    throw new AssertionError("Link visitors must not be converted into logged-in owners");
                }
                @Override public Result editAuthorized(SpeakerResultAccess access, String id, String speaker, Edit edit) {
                    calls.incrementAndGet();
                    assertNull(access.ownerContext());
                    assertTrue(access.allows(ID, "trusted-deployment", "stored-owner"));
                    assertFalse(access.allows("mtg_other", "trusted-deployment", "stored-owner"));
                    assertFalse(access.allows(ID, "other-deployment", "stored-owner"));
                    return new Updated(2, 1, Registration.NOT_REQUESTED);
                }
            };
            var dispatcher = new MeetingHttpDispatcher(fixture.runtime, fixture.errors, resolver,
                    null, null, null, new SpeakerNameEditEndpoint(port, fixture.errors, policy));
            var request = new HttpRequest("POST", DETAIL + "/speakers/s1:rename", Map.of(),
                    Map.of("genTime", "123"), "{\"displayName\":\"名字\",\"scope\":\"ALL\",\"remember\":false}");
            assertEquals(200, dispatcher.handle(request).status());
            assertEquals(1, calls.get());
            assertEquals(0, fixture.identityRequests.size());
            assertEquals(401, dispatcher.handle(request("POST", DETAIL + "/usage:submit", null, "")).status());

            // 同一策略仍兼容旧所有者插件；没有扩展新方法的插件不会自动开放链接编辑。
            SpeakerNameEditPort legacy = (context, id, speaker, edit) -> {
                assertEquals("stable-owner", context.identity().subject().subjectScope());
                return new SpeakerNameEditPort.Updated(3, 1, SpeakerNameEditPort.Registration.NOT_REQUESTED);
            };
            var endpoint = new SpeakerNameEditEndpoint(legacy, fixture.errors, policy);
            assertEquals(503, endpoint.handle(request).status());
            assertEquals(200, endpoint.handle(request("POST", request.path(), "owner", request.body())).status());
        }
    }

    @Test
    void optionalEmailKeepsDisabledBeforeAuthAndEnabledUsesTheSameWriteContext() {
        try (Fixture fixture = new Fixture()) {
            assertEquals(501, fixture.dispatcher(null).handle(emailRequest("owner")).status());
            assertEquals(0, fixture.identityRequests.size());
            var endpoint = fixture.emailEndpoint();
            assertEquals(401, new MeetingHttpDispatcher(fixture.runtime, fixture.errors,
                    null, endpoint).handle(emailRequest("owner")).status());
            assertEquals(0, fixture.queryCalls);
            assertEquals(0, fixture.identityRequests.size());

            var dispatcher = fixture.dispatcher(endpoint);
            assertEquals(403, dispatcher.handle(emailRequest("reader")).status());
            assertEquals(0, fixture.queryCalls);
            assertEquals(0, fixture.sent);
            fixture.readyResult = true;
            assertEquals(200, dispatcher.handle(emailRequest("owner")).status());
            assertEquals(1, fixture.queryCalls);
            assertEquals(1, fixture.sent);
            assertEquals("trusted-deployment", fixture.lastContext.deploymentScope());
            assertEquals(2, fixture.identityRequests.size());
            fixture.assertCredentialsClosed();
        }
    }

    @Test
    void closedRuntimeRemainsUnavailableWithoutIntroducingDispatcherLifecycle() {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = fixture.dispatcher(null);
            fixture.runtime.close();
            assertEquals(503, dispatcher.handle(request("GET", DETAIL, "owner", "")).status());
            assertEquals(0, fixture.queryCalls);
        }
    }

    @Test
    void jdkUsesTheSuppliedDispatcherAndKeepsHealthPathsOutsideBusinessRouting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = fixture.dispatcher(null);
            var probes = new AtomicInteger();
            assertEquals(404, dispatcher.handle(request("GET", MeetingJdkHttpServer.LIVENESS_PATH,
                    null, "")).status());
            assertEquals(404, dispatcher.handle(request("GET", MeetingJdkHttpServer.READINESS_PATH,
                    null, "")).status());
            try (var server = new MeetingJdkHttpServer(new InetSocketAddress("127.0.0.1", 0),
                    dispatcher, () -> { probes.incrementAndGet(); return true; });
                    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                server.start();
                String base = "http://127.0.0.1:" + server.address().getPort();
                for (String path : List.of(MeetingJdkHttpServer.LIVENESS_PATH,
                        MeetingJdkHttpServer.READINESS_PATH)) {
                    var response = http.send(java.net.http.HttpRequest.newBuilder(URI.create(base + path))
                                    .timeout(Duration.ofSeconds(5)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    assertEquals("{\"status\":\"UP\"}", response.body());
                }
                assertEquals(1, probes.get());
                assertEquals(0, fixture.identityRequests.size());
                var network = http.send(java.net.http.HttpRequest.newBuilder(URI.create(base + DETAIL))
                                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer owner").GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                var direct = dispatcher.handle(request("GET", DETAIL, "owner", ""));
                assertEquals(direct.status(), network.statusCode());
                assertEquals(direct.body(), network.body());
                assertEquals(2, fixture.queryCalls);
                fixture.assertCredentialsClosed();
            }
        }
    }

    @Test
    void resolverAndDispatcherMustShareTheErrorMapper() {
        try (Fixture fixture = new Fixture()) {
            var other = new AuthenticatedMeetingHttpContextResolver("trusted-deployment",
                    "trusted-audience", "trusted-client", fixture.provider, new HttpErrorMapper());
            assertThrows(IllegalArgumentException.class, () -> new MeetingHttpDispatcher(
                    fixture.runtime, fixture.errors, other));
        }
    }

    private static HttpRequest request(String method, String path, String token, String body) {
        return new HttpRequest(method, path,
                token == null ? Map.of() : Map.of("authorization", "Bearer " + token), body);
    }

    private static HttpRequest emailRequest(String token) {
        String url = "https://result.example.test/index.html?meetingId=" + ID
                + "&genTime=" + System.currentTimeMillis();
        return request("POST", DETAIL + ":sendResultEmail", token,
                "{\"recipients\":[\"viewer@example.test\"],\"resultUrl\":\"" + url + "\"}");
    }

    private static final class Fixture implements AutoCloseable {
        private int queryCalls;
        private int deleteCalls;
        private int sent;
        private boolean readyResult;
        private MeetingCommandContext lastContext;
        private final HttpErrorMapper errors = new HttpErrorMapper(() -> "dispatcher-diag");
        private final List<IdentityVerificationRequest> identityRequests = new ArrayList<>();
        private final IdentityProvider provider = request -> {
            identityRequests.add(request);
            boolean readOnly = request.credential().useBytes(bytes ->
                    java.util.Arrays.equals(bytes, "reader".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            Instant now = Instant.now();
            return IdentityProviderResult.success(new VerifiedIdentity(new Subject("stable-owner"),
                    Set.of(), readOnly ? Set.of() : Set.of(MeetingPermissions.CREATE, MeetingPermissions.DELETE),
                    now.minusSeconds(60), now.plusSeconds(60), "verified-credential"));
        };
        private final MeetingQueryPort query = new MeetingQueryPort() {
            @Override public Result get(GetRequest request) {
                queryCalls++;
                lastContext = request.context();
                return readyResult ? new Found(new MeetingStatus(ID, MeetingState.READY,
                        FinalizationFact.accepted("receipt"), ManifestStatus.READY, Instant.now())) : new NotFound();
            }
            @Override public ListResult list(ListRequest request) {
                throw new AssertionError("list is covered by existing JDK route tests");
            }
        };
        private final MeetingHttpRuntime runtime = new MeetingHttpRuntime(new MeetingHttpAdapter(
                query, request -> { deleteCalls++; lastContext = request.context(); return new DeleteResult.NotFound(); },
                new ReferenceMeetingHttpRequestDecoder("trusted-audience", "trusted-client"), errors), errors);
        private MeetingHttpDispatcher dispatcher(ResultEmailEndpoint email) {
            return new MeetingHttpDispatcher(runtime, errors,
                    new AuthenticatedMeetingHttpContextResolver("trusted-deployment", "trusted-audience",
                            "trusted-client", provider, errors), email);
        }

        private ResultEmailEndpoint emailEndpoint() {
            MeetingManifestPort manifest = request -> {
                Instant now = Instant.now();
                return new MeetingManifestPort.Found(new MeetingManifest("0.1", ID, 1,
                        new MeetingManifest.Profile("reference-asr", 1), "参考会议", ManifestStatus.READY,
                        now, now, 0, new MeetingManifest.Languages("zh-CN", List.of()), List.of(), List.of()));
            };
            return new ResultEmailEndpoint(query, manifest, (recipients, url) -> {
                assertEquals(List.of("viewer@example.test"), recipients);
                sent++;
            }, errors);
        }

        private void assertCredentialsClosed() {
            assertTrue(identityRequests.stream().allMatch(request -> request.credential().isDestroyed()));
        }

        @Override public void close() { runtime.close(); }
    }
}
