package com.czur.reference.application.http;

import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.MeetingEventPage;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Socket 字节通过 JDK 入口验证查询/删除身份接线和旧路由隔离。 */
class MeetingJdkHttpAuthenticatedRoutesTest {
    private static final String KEY = "test-idempotency-key-12345";

    @Test
    void readRoutesVerifyBearerAndPassOnlyTrustedContextToPorts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertEquals(401, fixture.status("GET", "/v1/meetings", "", ""));
            assertEquals(401, fixture.status("GET", "/v1/meetings/mtg_owned",
                    "Authorization: Basic private\r\n", ""));
            assertEquals(401, fixture.status("GET", "/v1/meetings/mtg_owned",
                    auth("invalid-token"), ""));
            assertEquals(0, fixture.queryCalls.get());
            assertEquals(1, fixture.provider.calls.get());

            assertEquals(200, fixture.status("GET", "/v1/meetings?pageSize=7",
                    auth("owner-token") + "X-Deployment-Scope: foreign\r\n", ""));
            assertEquals(404, fixture.status("GET", "/v1/meetings/mtg_owned",
                    auth("owner-token"), ""));
            assertEquals(403, fixture.status("GET", "/v1/meetings/mtg_owned",
                    auth("other-token"), ""));
            assertEquals(403, fixture.status("GET", "/v1/meetings/mtg_foreign_deployment",
                    auth("owner-token"), ""));
            assertEquals(400, fixture.status("GET", "/v1/meetings?pageSize=101",
                    auth("owner-token"), ""));
            assertEquals(4, fixture.queryCalls.get());
            assertEquals("trusted-deployment", fixture.lastDeployment);
            assertTrue(fixture.provider.allCredentialsClosed());
            assertEquals("trusted-audience", fixture.provider.lastRequest.audience());
            assertEquals("trusted-client", fixture.provider.lastRequest.clientId());
        }
    }

    @Test
    void eventsRouteUsesTheSameBearerResolverAndTrustedEventPort() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String path = "/v1/meetings/mtg_owned/events?afterSequence=0&limit=2";
            assertEquals(401, fixture.status("GET", path, "", ""));
            assertEquals(401, fixture.status("GET", path, auth("invalid-token"), ""));
            assertEquals(0, fixture.eventCalls.get());
            assertEquals(200, fixture.status("GET", path, auth("read-only-token"), ""));
            assertEquals(403, fixture.status("GET", path, auth("other-token"), ""));
            assertEquals(2, fixture.eventCalls.get());
            assertEquals("trusted-deployment", fixture.lastDeployment);
            assertTrue(fixture.provider.allCredentialsClosed());
        }
    }

    @Test
    void clientInputRoutesRequireBearerAndCreatePermissionBeforeReachingPort() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String start = "/v1/meetings/mtg_owned:startClient";
            String append = "/v1/meetings/mtg_owned/events:append";
            String usage = "/v1/meetings/mtg_owned/usage:submit";
            assertEquals(401, fixture.status("POST", start, "", ""));
            assertEquals(403, fixture.status("POST", start,
                    auth("read-only-token"), ""));
            assertEquals(501, fixture.status("POST", start,
                    auth("owner-token"), ""));
            assertEquals(401, fixture.status("POST", append, "", ""));
            assertEquals(403, fixture.status("POST", append,
                    auth("read-only-token"), ""));
            assertEquals(501, fixture.status("POST", append,
                    auth("owner-token"), ""));
            assertEquals(401, fixture.status("POST", usage, "", ""));
            assertEquals(403, fixture.status("POST", usage,
                    auth("read-only-token"), ""));
            assertEquals(501, fixture.status("POST", usage,
                    auth("owner-token"), ""));
            assertTrue(fixture.provider.allCredentialsClosed());
        }
    }

    @Test
    void deletePermissionAndBadInputStopBeforeDeletionPort() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String target = "/v1/meetings/mtg_owned";
            assertEquals(401, fixture.status("DELETE", target, "", ""));
            assertEquals(403, fixture.status("DELETE", target,
                    auth("read-only-token") + key(), ""));
            assertEquals(0, fixture.deleteCalls.get());

            assertEquals(400, fixture.status("DELETE", target,
                    auth("owner-token") + key(), "private-body"));
            assertEquals(400, fixture.status("DELETE", target,
                    auth("owner-token"), ""));
            assertEquals(0, fixture.deleteCalls.get());

            assertEquals(403, fixture.status("DELETE", target,
                    auth("other-token") + key(), ""));
            assertEquals(403, fixture.status("DELETE", "/v1/meetings/mtg_foreign_deployment",
                    auth("owner-token") + key(), ""));
            assertEquals(404, fixture.status("DELETE", target,
                    auth("owner-token") + key(), ""));
            assertEquals(3, fixture.deleteCalls.get());
            assertTrue(fixture.provider.allCredentialsClosed());
        }
    }

    @Test
    void unknownCreateAndStopRoutesKeepTheirOwnAuthenticationBehavior() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertEquals(404, fixture.status("GET", "/v1/meetings/mtg_owned/extra",
                    auth("owner-token"), ""));
            assertEquals(404, fixture.status("POST", "/v1/meetings/mtg_owned",
                    auth("owner-token"), ""));
            assertEquals(0, fixture.provider.calls.get());
            assertEquals(0, fixture.queryCalls.get());

            assertEquals(501, fixture.status("POST", "/v1/meetings",
                    auth("invalid-token"), "invalid JSON"));
            assertEquals(0, fixture.provider.calls.get());

            assertEquals(401, fixture.status("POST", "/v1/meetings/mtg_owned:stop",
                    key(), ""));
            assertEquals(0, fixture.provider.calls.get());
            assertEquals(404, fixture.status("POST", "/v1/meetings/mtg_owned:stop",
                    auth("owner-token") + key(), ""));
            assertEquals(1, fixture.provider.calls.get());
            assertEquals(1, fixture.stopCalls.get());
            assertTrue(fixture.provider.allCredentialsClosed());
        }
    }

    @Test
    void networkAuthenticationLogsAndResponsesNeverExposeSecrets() throws Exception {
        CopyOnWriteArrayList<String> logs = new CopyOnWriteArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                logs.add(formatter.formatMessage(record));
            }
            @Override public void flush() {
            }
            @Override public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try (Fixture fixture = new Fixture()) {
            String response = fixture.send("DELETE",
                    "/v1/meetings/mtg_owned?private=URL_SECRET_SENTINEL",
                    auth("TOKEN_SECRET_SENTINEL") + key(), "BODY_SECRET_SENTINEL");
            assertEquals(401, status(response));
            String combined = String.join("\n", logs) + response;
            for (String secret : new String[]{"TOKEN_SECRET_SENTINEL", "URL_SECRET_SENTINEL",
                    "BODY_SECRET_SENTINEL", KEY}) {
                assertFalse(combined.contains(secret));
            }
            assertFalse(logs.isEmpty());
            assertTrue(fixture.provider.allCredentialsClosed());
        } finally {
            root.removeHandler(capture);
        }
    }

    @Test
    void resolverAndNetworkMustShareErrorMapper() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var other = new AuthenticatedMeetingHttpContextResolver("trusted-deployment",
                    "trusted-audience", "trusted-client", fixture.provider,
                    new HttpErrorMapper());
            assertThrows(IllegalArgumentException.class, () -> new MeetingJdkHttpServer(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    fixture.runtime, fixture.errors, other));
        }
    }

    private static String auth(String token) {
        return "Authorization: Bearer " + token + "\r\n";
    }

    private static String key() {
        return "Idempotency-Key: " + KEY + "\r\n";
    }

    private static int status(String response) {
        return Integer.parseInt(response.substring(0, response.indexOf("\r\n")).split(" ")[1]);
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordingProvider provider = new RecordingProvider();
        private final HttpErrorMapper errors = new HttpErrorMapper(() -> "fixed-network-diag");
        private final MeetingHttpRuntime runtime;
        private final MeetingJdkHttpServer server;
        private final AtomicInteger queryCalls = new AtomicInteger();
        private final AtomicInteger deleteCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger eventCalls = new AtomicInteger();
        private volatile String lastDeployment;

        private Fixture() throws Exception {
            MeetingQueryPort query = new MeetingQueryPort() {
                @Override public Result get(GetRequest request) {
                    queryCalls.incrementAndGet();
                    lastDeployment = request.context().deploymentScope();
                    if (!"trusted-deployment".equals(lastDeployment)
                            || !"owner".equals(request.context().identity().subject().subjectScope())
                            || "mtg_foreign_deployment".equals(request.meetingId())) {
                        return new AccessDenied();
                    }
                    return new NotFound();
                }
                @Override public ListResult list(ListRequest request) {
                    queryCalls.incrementAndGet();
                    lastDeployment = request.context().deploymentScope();
                    if (!"trusted-deployment".equals(lastDeployment)
                            || !"owner".equals(request.context().identity().subject().subjectScope())) {
                        return new ListAccessDenied();
                    }
                    return new ListPage(java.util.List.of(), null);
                }
            };
            MeetingDeletionPort deletion = request -> {
                deleteCalls.incrementAndGet();
                if (!"trusted-deployment".equals(request.context().deploymentScope())
                        || !"owner".equals(request.context().identity().subject().subjectScope())
                        || "mtg_foreign_deployment".equals(request.meetingId())) {
                    return new DeleteResult.AccessDenied();
                }
                return new DeleteResult.NotFound();
            };
            OwnedMeetingStopUnitOfWork stop = request -> {
                stopCalls.incrementAndGet();
                return new OwnedMeetingStopUnitOfWork.Failure(
                        OwnedMeetingStopUnitOfWork.ErrorCode.MEETING_NOT_FOUND);
            };
            var stopService = new AuthenticatedMeetingStopApplicationService(
                    "trusted-deployment", provider, stop, query);
            MeetingEventLogPort events = request -> {
                eventCalls.incrementAndGet();
                lastDeployment = request.context().deploymentScope();
                if (!"trusted-deployment".equals(lastDeployment)
                        || !"owner".equals(request.context().identity()
                                .subject().subjectScope())) {
                    return new MeetingEventLogPort.AccessDenied();
                }
                return new MeetingEventLogPort.Page(
                        MeetingEventPage.empty(request.afterSequence()));
            };
            runtime = new MeetingHttpRuntime(query, deletion,
                    new ReferenceMeetingHttpRequestDecoder("trusted-audience", "trusted-client"),
                    errors, stopService, null, null, false, null, null, null, events);
            var resolver = new AuthenticatedMeetingHttpContextResolver("trusted-deployment",
                    "trusted-audience", "trusted-client", provider, errors);
            server = new MeetingJdkHttpServer(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), 0), runtime, errors, resolver);
            server.start();
        }

        private int status(String method, String path, String headers, String body) throws Exception {
            return MeetingJdkHttpAuthenticatedRoutesTest.status(send(method, path, headers, body));
        }

        private String send(String method, String path, String headers, String body) throws Exception {
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            byte[] prefix = (method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + headers + "Content-Length: " + content.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.address().getPort()));
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(prefix);
                socket.getOutputStream().write(content);
                socket.getOutputStream().flush();
                socket.shutdownOutput();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                socket.getInputStream().transferTo(bytes);
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }

        @Override public void close() {
            server.close();
            runtime.close();
        }
    }

    private static final class RecordingProvider implements IdentityProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CopyOnWriteArrayList<IdentityVerificationRequest> requests =
                new CopyOnWriteArrayList<>();
        private volatile IdentityVerificationRequest lastRequest;

        @Override public IdentityProviderResult<VerifiedIdentity> verifyCredential(
                IdentityVerificationRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            lastRequest = request;
            String token = request.credential().useBytes(
                    bytes -> new String(bytes, StandardCharsets.US_ASCII));
            if (token.startsWith("invalid-") || token.startsWith("TOKEN_SECRET")) {
                return IdentityProviderResult.failure(new IdentityProviderError(
                        IdentityProviderError.Code.INVALID_CREDENTIAL, false));
            }
            String subject = token.startsWith("other-") ? "other" : "owner";
            Set<String> permissions = token.startsWith("read-only-")
                    ? Set.of() : Set.of(MeetingPermissions.DELETE,
                            MeetingPermissions.STOP, MeetingPermissions.CREATE);
            return IdentityProviderResult.success(new VerifiedIdentity(
                    new Subject(subject), Set.of(), permissions,
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-10-02T00:00:00Z"), "credential-id"));
        }

        private boolean allCredentialsClosed() {
            for (IdentityVerificationRequest request : requests) {
                assertNotNull(request);
                if (!request.credential().isDestroyed()) {
                    return false;
                }
            }
            return true;
        }
    }
}
