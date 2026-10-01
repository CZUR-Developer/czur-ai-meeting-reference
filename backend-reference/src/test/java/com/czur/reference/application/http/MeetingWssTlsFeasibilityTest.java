package com.czur.reference.application.http;

import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.extensions.DefaultExtension;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T16-01 的真实 loopback 网络小试；不创建会议、不读数据库、不解析 Protobuf。
 *
 * <p>此处的 WebSocket 实现与假凭据仅属于测试夹具，不是生产鉴权入口。
 * 临时 SAN 证书在 JUnit 临时目录生成并由客户端显式信任；测试结束后目录自动清理。
 * 后续 T16-06 可复用经过验证的传输组件，但必须另行接入 T16-03 凭据端口。</p>
 */
class MeetingWssTlsFeasibilityTest {
    private static final String HOST = "127.0.0.1";
    private static final String TEST_SESSION_TOKEN = "test-only-realtime-session";
    private static final Logger LOGGER = Logger.getLogger(MeetingWssTlsFeasibilityTest.class.getName());

    @TempDir
    Path temporaryDirectory;

    @Test
    void trustedWssAndJdkHttpCoexistAndCloseWithinDeadline() throws Exception {
        assertEquals("org.slf4j.jul.JDK14LoggerFactory",
                LoggerFactory.getILoggerFactory().getClass().getName());
        TestTls tls = TestTls.create(temporaryDirectory);
        Fixture fixture = new Fixture(tls.serverContext());
        try (HttpClient client = client(tls.trustedContext())) {
            fixture.start();
            assertEquals(200, liveness(fixture.httpPort()));
            assertTrue(fixture.wssPort() > 0);
            assertTrue(fixture.httpPort() > 0);
            assertNotEquals(fixture.wssPort(), fixture.httpPort());

            EchoListener listener = new EchoListener();
            java.net.http.WebSocket socket = connect(fixture.wssUri(), client,
                    TEST_SESSION_TOKEN, CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                    listener).join();
            assertEquals(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, socket.getSubprotocol());
            byte[] payload = new byte[]{0, 1, 2, (byte) 0xff, 0, 42};
            socket.sendBinary(ByteBuffer.wrap(payload), true).join();
            assertArrayEquals(payload, listener.echoed.orTimeout(5, TimeUnit.SECONDS).join());
            assertEquals(200, liveness(fixture.httpPort()));

            // 有一条真实活跃 WSS 连接时关闭两个监听器，不能只测空连接关闭。
            long start = System.nanoTime();
            fixture.close();
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
            assertTrue(fixture.httpDrainClean());
            assertTrue(fixture.wssConnectionsClosed());
            assertTrue(fixture.wssThreadsStopped());
            listener.closed.orTimeout(3, TimeUnit.SECONDS).join();
            assertThrows(IOException.class, () -> liveness(fixture.httpPort()));
            assertThrows(CompletionException.class, () -> connect(
                    fixture.wssUri(), client,
                    TEST_SESSION_TOKEN, CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                    new EchoListener()).join());
        } finally {
            fixture.close();
        }
    }

    @Test
    void handshakeRejectsMissingOrWrongSessionBearerAndSubprotocol() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        try (Fixture fixture = new Fixture(tls.serverContext());
             HttpClient client = client(tls.trustedContext())) {
            fixture.start();
            for (String token : new String[]{null, "wrong-session-token"}) {
                assertThrows(CompletionException.class, () -> connect(fixture.wssUri(),
                        client, token,
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        new EchoListener()).join());
            }
            assertThrows(CompletionException.class, () -> connect(fixture.wssUri(),
                    client, TEST_SESSION_TOKEN, "wrong.realtime.protocol",
                    new EchoListener()).join());
            assertEquals(200, liveness(fixture.httpPort()));
        }
    }

    @Test
    void clientWithoutExplicitTrustRejectsTemporaryCertificate() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        try (Fixture fixture = new Fixture(tls.serverContext());
             HttpClient client = client(tls.untrustedContext())) {
            fixture.start();
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> connect(fixture.wssUri(),
                    client, TEST_SESSION_TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                    new EchoListener()).join());
            assertTrue(hasCause(failure, SSLHandshakeException.class),
                    "untrusted certificate must fail TLS validation");
            assertEquals(200, liveness(fixture.httpPort()));
        }
    }

    @Test
    void libraryTraceIsSuppressedEvenWhenApplicationRootUsesTrace() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        Logger root = Logger.getLogger("");
        Level oldRootLevel = root.getLevel();
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(formatter.formatMessage(record));
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        root.addHandler(capture);
        root.setLevel(Level.FINEST);
        try (Fixture fixture = new Fixture(tls.serverContext());
             HttpClient client = client(tls.trustedContext())) {
            fixture.start();
            assertFalse(Logger.getLogger("org.java_websocket.WebSocketImpl")
                    .isLoggable(Level.FINEST));
            EchoListener listener = new EchoListener();
            java.net.http.WebSocket socket = connect(fixture.wssUri(), client,
                    TEST_SESSION_TOKEN, CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                    listener).join();
            byte[] audioSentinel = "SENTINEL_AUDIO_MUST_NOT_LOG".getBytes(StandardCharsets.UTF_8);
            socket.sendBinary(ByteBuffer.wrap(audioSentinel), true).join();
            assertArrayEquals(audioSentinel,
                    listener.echoed.orTimeout(5, TimeUnit.SECONDS).join());
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            String captured = String.join("\n", messages);
            assertFalse(captured.contains(TEST_SESSION_TOKEN));
            assertFalse(captured.contains("SENTINEL_AUDIO_MUST_NOT_LOG"));
        } finally {
            root.setLevel(oldRootLevel);
            root.removeHandler(capture);
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> expected) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (expected.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static HttpClient client(SSLContext trust) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .sslContext(trust).build();
    }

    private static CompletableFuture<java.net.http.WebSocket> connect(
            URI uri, HttpClient client, String sessionToken, String subprotocol,
            java.net.http.WebSocket.Listener listener) {
        java.net.http.WebSocket.Builder builder = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3)).subprotocols(subprotocol);
        if (sessionToken != null) {
            builder.header("Authorization", "Bearer " + sessionToken);
        }
        return builder.buildAsync(uri, listener).orTimeout(5, TimeUnit.SECONDS);
    }

    private static int liveness(int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://" + HOST + ":" + port + MeetingJdkHttpServer.LIVENESS_PATH))
                .timeout(Duration.ofSeconds(3)).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /** JDK HttpClient 只订阅一条二进制回显；不把帧内容写入日志。 */
    private static final class EchoListener implements java.net.http.WebSocket.Listener {
        private final CompletableFuture<byte[]> echoed = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();

        @Override
        public void onOpen(java.net.http.WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(
                java.net.http.WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            fragments.writeBytes(bytes);
            if (last) {
                echoed.complete(fragments.toByteArray());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(java.net.http.WebSocket webSocket,
                                          int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(java.net.http.WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }

    /** 持有两个独立监听器；不持有数据库、业务状态或模型。 */
    private static final class Fixture implements AutoCloseable {
        private final MeetingHttpRuntime runtime;
        private final MeetingJdkHttpServer http;
        private final EchoWssServer wss;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Logger libraryLogger = Logger.getLogger("org.java_websocket");
        private final Logger decoderLogger = Logger.getLogger("org.java_websocket.WebSocketImpl");
        private final Level previousLibraryLevel;
        private final Level previousDecoderLevel;
        private MeetingJdkHttpServer.DrainResult httpDrain;
        private int boundHttpPort;
        private int boundWssPort;

        private Fixture(SSLContext serverTls) throws Exception {
            HttpErrorMapper errors = new HttpErrorMapper();
            MeetingQueryPort query = new MeetingQueryPort() {
                @Override
                public Result get(GetRequest request) { return new NotFound(); }

                @Override
                public ListResult list(ListRequest request) { return new ListNotFound(); }
            };
            MeetingDeletionPort deletion = new MeetingDeletionPort() {
                @Override
                public DeleteResult delete(DeleteRequest request) {
                    throw new AssertionError("business route must not run");
                }
            };
            MeetingHttpAdapter.RequestDecoder decoder = new MeetingHttpAdapter.RequestDecoder() {
                @Override
                public DeleteRequest decodeDelete(
                        com.czur.reference.application.http.HttpRequest request,
                        MeetingCommandContext context) {
                    throw new AssertionError("business route must not run");
                }
            };
            runtime = new MeetingHttpRuntime(
                    new MeetingHttpAdapter(query, deletion, decoder, errors), errors);
            http = new MeetingJdkHttpServer(
                    new InetSocketAddress(InetAddress.getByName(HOST), 0), runtime, errors);
            wss = new EchoWssServer(serverTls);
            // Java-WebSocket 1.6.0 在 TRACE 会打印解密后的握手和短帧字节。
            // 即使应用根 logger 开至 TRACE，此库包及敏感解码类仍固定为 INFO。
            previousLibraryLevel = libraryLogger.getLevel();
            previousDecoderLevel = decoderLogger.getLevel();
            libraryLogger.setLevel(Level.INFO);
            decoderLogger.setLevel(Level.INFO);
        }

        private void start() throws Exception {
            http.start();
            wss.start();
            wss.awaitStarted();
            boundHttpPort = http.address().getPort();
            boundWssPort = wss.getPort();
            LOGGER.log(Level.INFO, "T16-01 loopback HTTP/WSS 测试监听器均已启动");
        }

        private int httpPort() { return boundHttpPort; }

        private int wssPort() { return boundWssPort; }

        private URI wssUri() { return URI.create("wss://" + HOST + ":" + wssPort() + "/realtime"); }

        private boolean httpDrainClean() { return httpDrain != null && httpDrain.clean(); }

        private boolean wssConnectionsClosed() { return wss.getConnections().isEmpty(); }

        private boolean wssThreadsStopped() { return wss.threadsStopped(); }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                wss.stop((int) Math.min(2_000, remainingMillis(deadline)));
                wss.awaitThreadsStopped(deadline);
            } finally {
                try {
                    httpDrain = http.shutdown(Duration.ofMillis(remainingMillis(deadline)));
                } finally {
                    try {
                        runtime.close();
                    } finally {
                        decoderLogger.setLevel(previousDecoderLevel);
                        libraryLogger.setLevel(previousLibraryLevel);
                    }
                }
            }
            LOGGER.log(Level.INFO, "T16-01 loopback HTTP/WSS 测试监听器已关闭");
        }

        private static long remainingMillis(long deadline) {
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        }
    }

    /** 只验证网络握手与 TLS，不代表 T16-03 的持久凭据判定。 */
    private static final class EchoWssServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<Exception> startupFailure = new AtomicReference<>();
        private final AtomicReference<Thread> selectorThread = new AtomicReference<>();

        private EchoWssServer(SSLContext tls) throws Exception {
            super(new InetSocketAddress(InetAddress.getByName(HOST), 0), 2,
                    List.of(new Draft_6455(List.of(new DefaultExtension()), List.of(
                            new Protocol(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL)))));
            setWebSocketFactory(new DefaultSSLWebSocketServerFactory(tls));
            setConnectionLostTimeout(0);
        }

        private void awaitStarted() throws Exception {
            assertTrue(started.await(5, TimeUnit.SECONDS), "WSS asynchronous start timed out");
            if (startupFailure.get() != null) {
                throw startupFailure.get();
            }
            assertTrue(getPort() > 0, "WSS did not bind a random loopback port");
        }

        /** stop(timeout) 的 API 可能先于 worker 退出；补齐同一截止时间内的线程证据。 */
        private void awaitThreadsStopped(long deadline) throws InterruptedException {
            Thread selector = selectorThread.get();
            if (selector != null) {
                selector.join(Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            }
            for (Thread worker : decoders) {
                worker.join(Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            }
            assertTrue(threadsStopped(), "WSS selector/worker still running after close deadline");
        }

        private boolean threadsStopped() {
            Thread selector = selectorThread.get();
            return (selector == null || !selector.isAlive())
                    && decoders.stream().noneMatch(Thread::isAlive);
        }

        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
                WebSocket connection, Draft draft, ClientHandshake request)
                throws InvalidDataException {
            String authorization = request.getFieldValue("Authorization");
            if (authorization == null) {
                authorization = "";
            }
            byte[] actual = authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] expected = ("Bearer " + TEST_SESSION_TOKEN)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(actual, expected)) {
                LOGGER.log(Level.FINE, "T16-01 WSS 握手拒绝 reason=SESSION_BEARER_INVALID");
                throw new InvalidHandshakeException("session Bearer rejected");
            }
            if (!CreateMeetingResponseFact.REALTIME_SUBPROTOCOL.equals(
                    request.getFieldValue("Sec-WebSocket-Protocol"))) {
                LOGGER.log(Level.FINE, "T16-01 WSS 握手拒绝 reason=SUBPROTOCOL_INVALID");
                throw new InvalidHandshakeException("subprotocol rejected");
            }
            return super.onWebsocketHandshakeReceivedAsServer(connection, draft, request);
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            LOGGER.log(Level.FINE, "T16-01 WSS 测试连接建立");
        }

        @Override
        public void onMessage(WebSocket connection, String text) {
            connection.close(1003, "binary only");
        }

        @Override
        public void onMessage(WebSocket connection, ByteBuffer bytes) {
            connection.send(bytes);
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            LOGGER.log(Level.FINE, "T16-01 WSS 测试连接关闭");
        }

        @Override
        public void onError(WebSocket connection, Exception error) {
            if (started.getCount() > 0) {
                startupFailure.compareAndSet(null, error);
                started.countDown();
            }
            LOGGER.log(Level.FINE, "T16-01 WSS 网络异常 reason={0}",
                    error.getClass().getSimpleName());
        }

        @Override
        public void onStart() {
            selectorThread.set(Thread.currentThread());
            started.countDown();
        }
    }

    /** 在临时目录通过本机 JDK keytool 生成带 SAN 的测试身份，并装配独立信任上下文。 */
    private record TestTls(SSLContext serverContext, SSLContext trustedContext,
                           SSLContext untrustedContext) {
        private static TestTls create(Path directory) throws Exception {
            Path keystore = directory.resolve("loopback-test.p12");
            char[] password = ("temporary-" + UUID.randomUUID()).toCharArray();
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            Process process = new ProcessBuilder(keytool.toString(), "-genkeypair",
                    "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "1", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-storetype", "PKCS12", "-keystore", keystore.toString(),
                    "-storepass", new String(password), "-keypass", new String(password),
                    "-noprompt").redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("temporary test certificate generation timed out");
            }
            assertEquals(0, process.exitValue(), "temporary test certificate generation failed");

            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = java.nio.file.Files.newInputStream(keystore)) {
                keys.load(input, password);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);
            TrustManagerFactory trusted = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trusted.init(keys);
            KeyStore empty = KeyStore.getInstance("PKCS12");
            empty.load(null, null);
            TrustManagerFactory untrusted = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            untrusted.init(empty);

            SSLContext server = SSLContext.getInstance("TLS");
            server.init(keyManagers.getKeyManagers(), null, null);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, trusted.getTrustManagers(), null);
            SSLContext negative = SSLContext.getInstance("TLS");
            negative.init(null, untrusted.getTrustManagers(), null);
            return new TestTls(server, client, negative);
        }
    }
}
