package com.czur.reference.application.bootstrap;

import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.MeetingHttpAdapter;
import com.czur.reference.application.http.MeetingHttpRuntime;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingQueryPort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外层 owner 的顺序、同一截止时间和重复关闭门禁。 */
class ReferenceMeetingJdkHttpServiceTest {
    @Test
    void resourcesCloseOnlyAfterAcceptedNetworkTaskDrains() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean probeExited = new AtomicBoolean();
        AtomicBoolean resourcesClosed = new AtomicBoolean();
        try (Fixture fixture = new Fixture(() -> {
            entered.countDown();
            waitIgnoringInterrupt(release);
            probeExited.set(true);
            return true;
        }, () -> {
            assertTrue(probeExited.get(), "resource must close after network task");
            resourcesClosed.set(true);
        })) {
            Future<String> slow = fixture.pool.submit(fixture::readiness);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<ReferenceMeetingJdkHttpService.CloseResult> stopping =
                    fixture.pool.submit(() -> fixture.owner.shutdown(Duration.ofSeconds(2)));
            assertFalse(stopping.isDone());
            release.countDown();
            assertTrue(slow.get(2, TimeUnit.SECONDS).contains("200"));
            var result = stopping.get(2, TimeUnit.SECONDS);
            assertTrue(result.clean());
            assertTrue(result.network().clean());
            assertTrue(result.resourcesClosed());
            assertTrue(resourcesClosed.get());
            assertEquals(result, fixture.owner.shutdown(Duration.ZERO));
        } finally {
            release.countDown();
        }
    }

    @Test
    void slowResourceCleanupReturnsNonCleanWithinSameDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean resourcesClosed = new AtomicBoolean();
        try (Fixture fixture = new Fixture(() -> true, () -> {
            waitIgnoringInterrupt(release);
            resourcesClosed.set(true);
        })) {
            long start = System.nanoTime();
            var result = fixture.owner.shutdown(Duration.ofMillis(150));
            assertTrue(Duration.ofNanos(System.nanoTime() - start)
                    .compareTo(Duration.ofSeconds(2)) < 0);
            assertFalse(result.clean());
            assertTrue(result.network().clean());
            assertFalse(result.resourcesClosed());
            assertTrue(result.cleanupPending());
            assertEquals(result, fixture.owner.shutdown(Duration.ofSeconds(2)));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!resourcesClosed.get() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(resourcesClosed.get(), "test must release and reclaim cleanup thread");
        } finally {
            release.countDown();
        }
    }

    @Test
    void resourceCloseFailureIsNonCleanAndLogsOnlyFixedClassification() throws Exception {
        String sentinel = "SECRET_RESOURCE_FAILURE_SENTINEL";
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
        try (Fixture fixture = new Fixture(() -> true, () -> {
            throw new IllegalStateException(sentinel);
        })) {
            var result = fixture.owner.shutdown(Duration.ofSeconds(1));
            assertFalse(result.clean());
            assertTrue(result.network().clean());
            assertFalse(result.resourcesClosed());
            assertFalse(result.cleanupPending());
            assertTrue(result.cleanupFailed());
            assertEquals(result, fixture.owner.shutdown(Duration.ZERO));
            synchronized (logs) {
                assertTrue(logs.toString().contains("RESOURCE_CLOSE_FAILED"));
                assertFalse(logs.toString().contains(sentinel));
            }
        } finally {
            root.removeHandler(capture);
        }
    }

    private static void waitIgnoringInterrupt(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // Exercise owner timeout even when cleanup ignores interruption.
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final MeetingHttpRuntime runtime;
        private final MeetingJdkHttpServer server;
        private final ReferenceMeetingJdkHttpService owner;

        private Fixture(java.util.function.BooleanSupplier readiness,
                AutoCloseable onResourceClose) throws Exception {
            var errors = new HttpErrorMapper();
            MeetingQueryPort query = new MeetingQueryPort() {
                @Override public Result get(GetRequest request) { return new NotFound(); }
                @Override public ListResult list(ListRequest request) { return new ListNotFound(); }
            };
            var adapter = new MeetingHttpAdapter(query,
                    request -> new DeleteResult.NotFound(),
                    (request, context) -> { throw new AssertionError("unused decoder"); },
                    errors);
            runtime = new MeetingHttpRuntime(adapter, errors);
            server = new MeetingJdkHttpServer(new InetSocketAddress("127.0.0.1", 0),
                    runtime, errors, null, readiness);
            server.start();
            owner = new ReferenceMeetingJdkHttpService(server, () -> {
                try {
                    onResourceClose.close();
                } finally {
                    runtime.close();
                }
            });
        }

        private String readiness() throws Exception {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.address().getPort()));
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write(("GET " + MeetingJdkHttpServer.READINESS_PATH
                        + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                socket.shutdownOutput();
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                socket.getInputStream().transferTo(response);
                return response.toString(StandardCharsets.UTF_8);
            }
        }

        @Override public void close() throws Exception {
            owner.close();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
