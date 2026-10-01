package com.czur.reference.application.http;

import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 loopback socket 覆盖 JDK 执行器受理、排队、过载和有界关闭。 */
class MeetingJdkHttpShutdownTest {
    @Test
    void closeBeforeStartReleasesBoundPortAndExecutor() throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            port = reserved.getLocalPort();
        }
        try (Fixture fixture = new Fixture(false, () -> true, false, port)) {
            assertThrows(IllegalStateException.class, fixture.server::address);
            assertTrue(fixture.server.shutdown(Duration.ofSeconds(1)).clean());
            assertTrue(fixture.server.executorTerminatedForTest());
            try (ServerSocket rebound = new ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(new InetSocketAddress("127.0.0.1", port));
                assertTrue(rebound.isBound());
            }
            assertTrue(port > 0);
        }
    }

    @Test
    void failedBindClosesExecutorAndLeavesOnlyTheExistingListener() throws Exception {
        int port;
        try (ServerSocket occupied = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            port = occupied.getLocalPort();
            try (Fixture fixture = new Fixture(false, () -> true, false, port)) {
                assertThrows(IOException.class, fixture.server::start);
                assertThrows(IllegalStateException.class, fixture.server::address);
                assertTrue(fixture.server.executorTerminatedForTest());
            }
        }
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress("127.0.0.1", port));
            assertTrue(rebound.isBound());
        }
    }

    @Test
    void repeatedStartRejectsWithoutClosingTheRunningListener() throws Exception {
        try (Fixture fixture = new Fixture(false, () -> true)) {
            assertThrows(IllegalStateException.class, fixture.server::start);
            assertEquals(200, status(fixture.send(MeetingJdkHttpServer.LIVENESS_PATH, false)));
            assertTrue(fixture.server.shutdown(Duration.ofSeconds(1)).clean());
        }
    }

    @Test
    void slowReadinessDoesNotBlockLivenessAndNormalDrainWaitsForRunningRequest()
            throws Exception {
        CountDownLatch readinessEntered = new CountDownLatch(1);
        CountDownLatch releaseReadiness = new CountDownLatch(1);
        BooleanSupplier readiness = () -> {
            readinessEntered.countDown();
            awaitIgnoringInterrupt(releaseReadiness);
            return true;
        };
        try (Fixture fixture = new Fixture(false, readiness)) {
            Future<String> slow = fixture.async(() -> fixture.send(
                    MeetingJdkHttpServer.READINESS_PATH, false));
            assertTrue(readinessEntered.await(2, TimeUnit.SECONDS));
            assertEquals(200, status(fixture.send(MeetingJdkHttpServer.LIVENESS_PATH, false)));
            Future<MeetingJdkHttpServer.DrainResult> stopping = fixture.async(
                    () -> fixture.server.shutdown(Duration.ofSeconds(3)));
            awaitCondition(fixture.server::closingForTest);
            assertFalse(stopping.isDone(), "running readiness must be included in drain");
            releaseReadiness.countDown();
            assertEquals(200, status(slow.get(3, TimeUnit.SECONDS)));
            var result = stopping.get(3, TimeUnit.SECONDS);
            assertTrue(result.clean());
            assertEquals(0, result.unfinishedAccepted());
            assertEquals(0, result.liveWorkers());
            assertTrue(fixture.server.executorTerminatedForTest());
            assertThrows(IOException.class,
                    () -> fixture.send(MeetingJdkHttpServer.LIVENESS_PATH, false));
            assertEquals(result, fixture.server.shutdown(Duration.ZERO));
        } finally {
            releaseReadiness.countDown();
        }
    }

    @Test
    void oneBlockedBusinessRequestDoesNotBlockLiveness() throws Exception {
        try (Fixture fixture = new Fixture(true, () -> true)) {
            Future<String> slow = fixture.async(() -> fixture.send("/v1/meetings", true));
            awaitCondition(() -> fixture.queryCalls.get() == 1);
            assertEquals(200, status(fixture.send(MeetingJdkHttpServer.LIVENESS_PATH, false)));
            fixture.releaseBusiness.countDown();
            assertEquals(200, status(slow.get(3, TimeUnit.SECONDS)));
            assertTrue(fixture.server.shutdown(Duration.ofSeconds(2)).clean());
        }
    }

    @Test
    void acceptedQueuedSocketGetsExplicitShutdownResponseBeforeCleanDrain()
            throws Exception {
        try (Fixture fixture = new Fixture(true, () -> true)) {
            Future<String> first = fixture.async(() -> fixture.send("/v1/meetings", true));
            Future<String> second = fixture.async(() -> fixture.send("/v1/meetings", true));
            awaitCondition(() -> fixture.queryCalls.get() == 2);
            Future<String> queued = fixture.async(() -> fixture.send("/v1/meetings", true));
            awaitCondition(() -> fixture.server.queuedRequestsForTest() == 1);
            assertEquals(2, fixture.queryCalls.get());
            Future<MeetingJdkHttpServer.DrainResult> stopping = fixture.async(
                    () -> fixture.server.shutdown(Duration.ofSeconds(3)));
            awaitCondition(fixture.server::closingForTest);
            long start = System.nanoTime();
            try {
                String unaccepted = fixture.send("/v1/meetings", true);
                assertTrue(unaccepted.isEmpty(),
                        "post-gate socket was never accepted, so JDK may only close it");
            } catch (IOException expected) {
                // A reset is the other JDK socket-close outcome for an unaccepted Exchange.
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - start)
                    .compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(2, fixture.queryCalls.get());
            fixture.releaseBusiness.countDown();
            assertEquals(200, status(first.get(3, TimeUnit.SECONDS)));
            assertEquals(200, status(second.get(3, TimeUnit.SECONDS)));
            String queuedResponse = queued.get(3, TimeUnit.SECONDS);
            assertEquals(503, status(queuedResponse));
            assertTrue(queuedResponse.contains("\"specVersion\":"));
            assertTrue(queuedResponse.contains(
                    "\"error\":{\"domain\":\"INTERNAL\",\"code\":\"RUNTIME_NOT_READY\""));
            assertEquals(2, fixture.queryCalls.get(),
                    "queued request must not enter identity or business port after gate closes");
            assertTrue(stopping.get(3, TimeUnit.SECONDS).clean());
        }
    }

    @Test
    void fullQueueRejectsOnlyUnacceptedSocketAndDispatcherRemainsUsable()
            throws Exception {
        try (Fixture fixture = new Fixture(true, () -> true)) {
            Future<String> first = fixture.async(() -> fixture.send("/v1/meetings", true));
            Future<String> second = fixture.async(() -> fixture.send("/v1/meetings", true));
            awaitCondition(() -> fixture.queryCalls.get() == 2);
            Future<String> third = fixture.async(() -> fixture.send("/v1/meetings", true));
            Future<String> fourth = fixture.async(() -> fixture.send("/v1/meetings", true));
            awaitCondition(() -> fixture.server.queuedRequestsForTest() == 2);
            long start = System.nanoTime();
            try {
                String overload = fixture.send(MeetingJdkHttpServer.LIVENESS_PATH, false);
                assertTrue(overload.isEmpty(), "unaccepted overload socket must close without HTTP");
            } catch (IOException expected) {
                // JDK may reset instead of orderly EOF; both are unaccepted socket closure.
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(
                    Duration.ofSeconds(2)) < 0, "dispatcher must reject overload promptly");
            assertEquals(2, fixture.queryCalls.get());
            fixture.releaseBusiness.countDown();
            for (Future<String> result : List.of(first, second, third, fourth)) {
                assertEquals(200, status(result.get(3, TimeUnit.SECONDS)));
            }
            assertEquals(200, status(fixture.send(MeetingJdkHttpServer.LIVENESS_PATH, false)));
            assertTrue(fixture.server.shutdown(Duration.ofSeconds(3)).clean());
        }
    }

    @Test
    void ignoredInterruptTimesOutNonCleanAndReportsLiveWorkerWithoutSecretLogs()
            throws Exception {
        String sentinel = "SECRET_CLOSE_TOKEN_SENTINEL";
        StringBuilder logs = new StringBuilder();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                synchronized (logs) { logs.append(formatter.formatMessage(record)).append('\n'); }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try (Fixture fixture = new Fixture(true, () -> true)) {
            Future<String> active = fixture.async(() -> fixture.send(
                    "/v1/meetings", true, "Bearer " + sentinel));
            awaitCondition(() -> fixture.queryCalls.get() == 1);
            long start = System.nanoTime();
            var result = fixture.server.shutdown(Duration.ofMillis(150));
            long elapsed = System.nanoTime() - start;
            assertFalse(result.clean());
            assertTrue(result.unfinishedAccepted() >= 1);
            assertTrue(result.liveWorkers() >= 1,
                    "ignoring interrupt must not be reported as reclaimed");
            assertTrue(Duration.ofNanos(elapsed).compareTo(Duration.ofSeconds(2)) < 0);
            assertThrows(IOException.class,
                    () -> fixture.send(MeetingJdkHttpServer.LIVENESS_PATH, false));
            assertEquals(result, fixture.server.shutdown(Duration.ofSeconds(3)));
            fixture.releaseBusiness.countDown();
            try {
                active.get(3, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // Forced stop may cut the already-running response socket.
            }
            awaitCondition(fixture.server::executorTerminatedForTest);
            synchronized (logs) {
                assertFalse(logs.toString().contains(sentinel));
            }
        } finally {
            root.removeHandler(capture);
        }
    }

    @Test
    void timeoutReportsBothRunningAndAbandonedAcceptedQueue() throws Exception {
        try (Fixture fixture = new Fixture(true, () -> true)) {
            Future<String> first = fixture.async(() -> fixture.send("/v1/meetings", true));
            Future<String> second = fixture.async(() -> fixture.send("/v1/meetings", true));
            awaitCondition(() -> fixture.queryCalls.get() == 2);
            Future<String> queued = fixture.async(() -> fixture.send("/v1/meetings", true));
            awaitCondition(() -> fixture.server.queuedRequestsForTest() == 1);
            var result = fixture.server.shutdown(Duration.ofMillis(150));
            assertFalse(result.clean());
            assertEquals(3, result.unfinishedAccepted());
            assertEquals(1, result.discardedQueued());
            assertEquals(2, result.liveWorkers());
            assertEquals(2, fixture.queryCalls.get(),
                    "discarded queue must never be mistaken for completed business work");
            fixture.releaseBusiness.countDown();
            for (Future<String> client : List.of(first, second, queued)) {
                try {
                    client.get(3, TimeUnit.SECONDS);
                } catch (ExecutionException ignored) {
                    // Forced stop may cut any of these sockets; none is a clean result.
                }
            }
            awaitCondition(fixture.server::executorTerminatedForTest);
        }
    }

    private static int status(String response) {
        return Integer.parseInt(response.substring(0, response.indexOf("\r\n"))
                .split(" ")[1]);
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "network task did not reach expected state");
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // Test the forced result when application work ignores interruption.
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ExecutorService clients = Executors.newCachedThreadPool();
        private final CountDownLatch releaseBusiness = new CountDownLatch(1);
        private final AtomicInteger queryCalls = new AtomicInteger();
        private final MeetingHttpRuntime runtime;
        private final MeetingJdkHttpServer server;

        private Fixture(boolean blockBusiness, BooleanSupplier readiness) throws Exception {
            this(blockBusiness, readiness, true, 0);
        }

        private Fixture(boolean blockBusiness, BooleanSupplier readiness,
                boolean start, int port) throws Exception {
            MeetingQueryPort query = new MeetingQueryPort() {
                @Override public Result get(GetRequest request) { return new NotFound(); }
                @Override public ListResult list(ListRequest request) {
                    queryCalls.incrementAndGet();
                    if (blockBusiness) awaitIgnoringInterrupt(releaseBusiness);
                    return new ListPage(List.of(), null);
                }
            };
            MeetingDeletionPort deletion = request -> new DeleteResult.NotFound();
            HttpErrorMapper errors = new HttpErrorMapper(() -> "shutdown-test-diag");
            runtime = new MeetingHttpRuntime(new MeetingHttpAdapter(query, deletion,
                    new ReferenceMeetingHttpRequestDecoder("test-audience", "test-client"),
                    errors), errors);
            IdentityProvider identity = request -> IdentityProviderResult.success(
                    new VerifiedIdentity(new Subject("test-subject"), Set.of(), Set.of(),
                            Instant.parse("2026-10-01T00:00:00Z"),
                            Instant.parse("2026-10-02T00:00:00Z"), "test-credential"));
            var resolver = new AuthenticatedMeetingHttpContextResolver(
                    "test-deployment", "test-audience", "test-client", identity, errors);
            server = new MeetingJdkHttpServer(new InetSocketAddress("127.0.0.1", port),
                    runtime, errors, resolver, readiness, 2, 2);
            if (start) server.start();
        }

        private <T> Future<T> async(Callable<T> task) { return clients.submit(task); }

        private String send(String path, boolean authenticated) throws IOException {
            return send(path, authenticated, "Bearer test-token");
        }

        private String send(String path, boolean authenticated, String authorization)
                throws IOException {
            String headers = authenticated ? "Authorization: " + authorization + "\r\n" : "";
            byte[] request = ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + headers + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.address().getPort()),
                        1_000);
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
                socket.shutdownOutput();
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                socket.getInputStream().transferTo(received);
                return received.toString(StandardCharsets.UTF_8);
            }
        }

        @Override public void close() throws Exception {
            releaseBusiness.countDown();
            server.close();
            runtime.close();
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
