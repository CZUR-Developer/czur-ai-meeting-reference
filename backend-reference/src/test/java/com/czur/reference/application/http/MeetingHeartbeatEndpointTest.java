package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingHeartbeatStore;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议心跳路由的鉴权与状态门禁；不启动网络、数据库或真实模型。
 *
 * <p>认证复用 dispatcher 的 CLIENT_WRITE 路线；只有持有会议创建权限的主体才能
 * 到达查询端口。会话存在性与归属仍由查询端口决定，状态不符统一返回 409。</p>
 */
class MeetingHeartbeatEndpointTest {
    private static final String ID = "mtg_heartbeat_test";
    private static final String PATH = "/v1/meetings/" + ID + ":heartbeat";
    private static final Instant NOW = Instant.parse("2030-05-01T01:02:03.123456Z");
    private static final int INTERVAL_MS = 60_000;

    @Test
    void disabledEndpointKeepsRouteClosedBeforeAuthentication() {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = new MeetingHttpDispatcher(fixture.runtime, fixture.errors);
            assertEquals(501, dispatcher.handle(request("owner")).status());
            assertEquals(0, fixture.identityRequests.size());
            assertEquals(0, fixture.queryCalls.get());
            assertEquals(0, fixture.stored.size());
        }
    }

    @Test
    void heartbeatRequiresClientWriteIdentityAndActiveMeeting() {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = fixture.dispatcher();

            assertEquals(401, dispatcher.handle(request(null)).status());
            assertEquals(0, fixture.identityRequests.size());
            assertEquals(0, fixture.queryCalls.get());

            assertEquals(403, dispatcher.handle(request("reader")).status());
            assertEquals(0, fixture.queryCalls.get());
            assertEquals(0, fixture.stored.size());

            fixture.queryResult.set(new MeetingQueryPort.NotFound());
            assertEquals(404, dispatcher.handle(request("owner")).status());
            assertEquals(1, fixture.queryCalls.get());
            assertEquals(0, fixture.stored.size());

            fixture.queryResult.set(new MeetingQueryPort.AccessDenied());
            assertEquals(403, dispatcher.handle(request("owner")).status());
            assertEquals(0, fixture.stored.size());

            // CREATED 还未真正开始，不能先写“会议已开始”的事实。
            for (MeetingState inactive : List.of(MeetingState.CREATED, MeetingState.STOPPING,
                    MeetingState.READY, MeetingState.DELETED)) {
                fixture.queryResult.set(found(inactive));
                var response = dispatcher.handle(request("owner"));
                assertEquals(409, response.status(), inactive.name());
                assertTrue(response.body().contains("HEARTBEAT_INVALID_STATE"), inactive.name());
                assertEquals(0, fixture.stored.size(), inactive.name());
            }
            assertEquals("stable-owner", fixture.lastContext.identity().subject().subjectScope());
            assertEquals("trusted-deployment", fixture.lastContext.deploymentScope());

            fixture.queryResult.set(found(MeetingState.STARTING));
            var first = dispatcher.handle(request("owner"));
            assertEquals(200, first.status());
            assertTrue(first.body().contains("\"heartbeatIntervalMs\":" + INTERVAL_MS));
            assertFalse(first.body().contains("startedAt"));
            assertEquals(List.of(ID), fixture.stored);
            assertEquals(NOW, fixture.storedAt.get());

            // 同一路由重复调用只刷新最后活跃；HTTP 层不产生第二种成功形状。
            fixture.queryResult.set(found(MeetingState.RUNNING));
            var repeat = dispatcher.handle(request("owner"));
            assertEquals(200, repeat.status());
            assertEquals(first.body(), repeat.body());
            assertEquals(List.of(ID, ID), fixture.stored);
            assertEquals(NOW, fixture.storedAt.get());
        }
    }

    @Test
    void requestShapeAndStorageFailuresUseExistingContractErrors() {
        try (Fixture fixture = new Fixture()) {
            var dispatcher = fixture.dispatcher();
            fixture.queryResult.set(found(MeetingState.RUNNING));

            var withBody = new HttpRequest("POST", PATH,
                    Map.of("authorization", "Bearer owner"), "{}");
            assertEquals(400, dispatcher.handle(withBody).status());
            assertEquals(0, fixture.queryCalls.get());

            var withQuery = new HttpRequest("POST", PATH,
                    Map.of("authorization", "Bearer owner"), Map.of("seq", "1"), "");
            assertEquals(400, dispatcher.handle(withQuery).status());
            assertEquals(0, fixture.queryCalls.get());

            var wrongMethod = new HttpRequest("GET", PATH,
                    Map.of("authorization", "Bearer owner"), "");
            assertEquals(404, dispatcher.handle(wrongMethod).status());
            assertEquals(0, fixture.queryCalls.get());

            fixture.storageFailure.set(true);
            var unavailable = dispatcher.handle(request("owner"));
            assertEquals(503, unavailable.status());
            assertTrue(unavailable.body().contains("HEARTBEAT_UNAVAILABLE"));

            // 存储端口返回 false：查询后会议已不可活跃或已落弃场决定，按状态冲突处理。
            fixture.storageFailure.set(false);
            fixture.rejectedStore.set(true);
            var rejected = dispatcher.handle(request("owner"));
            assertEquals(409, rejected.status());
            assertTrue(rejected.body().contains("HEARTBEAT_INVALID_STATE"));
            assertTrue(fixture.stored.isEmpty());
        }
    }

    private static HttpRequest request(String token) {
        return new HttpRequest("POST", PATH,
                token == null ? Map.of() : Map.of("authorization", "Bearer " + token), "");
    }

    private static MeetingQueryPort.Found found(MeetingState state) {
        return new MeetingQueryPort.Found(new MeetingStatus(ID, state,
                FinalizationFact.accepted("receipt"), ManifestStatus.NOT_STARTED, NOW));
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpErrorMapper errors = new HttpErrorMapper(() -> "heartbeat-diag");
        private final List<com.czur.reference.application.port.identity.IdentityVerificationRequest>
                identityRequests = new ArrayList<>();
        private final AtomicInteger queryCalls = new AtomicInteger();
        private final AtomicReference<MeetingQueryPort.Result> queryResult =
                new AtomicReference<>(new MeetingQueryPort.NotFound());
        private final List<String> stored = new ArrayList<>();
        private final AtomicReference<Instant> storedAt = new AtomicReference<>();
        private final AtomicReference<Boolean> storageFailure = new AtomicReference<>(false);
        private final AtomicReference<Boolean> rejectedStore = new AtomicReference<>(false);
        private MeetingCommandContext lastContext;

        private final IdentityProvider provider = request -> {
            identityRequests.add(request);
            boolean reader = request.credential().useBytes(bytes ->
                    java.util.Arrays.equals(bytes,
                            "reader".getBytes(StandardCharsets.US_ASCII)));
            Instant now = Instant.now();
            return IdentityProviderResult.success(new VerifiedIdentity(
                    new Subject("stable-owner"), Set.of(),
                    reader ? Set.of() : Set.of(MeetingPermissions.CREATE, MeetingPermissions.DELETE),
                    now.minusSeconds(60), now.plusSeconds(60), "verified-credential"));
        };

        private final MeetingQueryPort query = new MeetingQueryPort() {
            @Override
            public Result get(GetRequest request) {
                queryCalls.incrementAndGet();
                lastContext = request.context();
                return queryResult.get();
            }

            @Override
            public ListResult list(ListRequest request) {
                throw new AssertionError("heartbeat test does not list meetings");
            }
        };

        private final MeetingHeartbeatStore store = (meetingId, now) -> {
            if (storageFailure.get()) {
                throw new IllegalStateException("storage down");
            }
            // 查询后会议已被弃场决定/状态变化收口：端口拒绝本次心跳。
            if (rejectedStore.get()) {
                return false;
            }
            stored.add(meetingId);
            storedAt.set(now);
            return true;
        };

        private final MeetingHttpRuntime runtime = new MeetingHttpRuntime(
                new MeetingHttpAdapter(query,
                        request -> new DeleteResult.NotFound(),
                        new ReferenceMeetingHttpRequestDecoder("trusted-audience", "trusted-client"),
                        errors), errors);

        private final MeetingHeartbeatEndpoint endpoint = new MeetingHeartbeatEndpoint(
                query, store, Clock.fixed(NOW, ZoneOffset.UTC), INTERVAL_MS, errors);

        private MeetingHttpDispatcher dispatcher() {
            return new MeetingHttpDispatcher(runtime, errors,
                    new AuthenticatedMeetingHttpContextResolver("trusted-deployment",
                            "trusted-audience", "trusted-client", provider, errors),
                    null, null, null, null, endpoint);
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}
