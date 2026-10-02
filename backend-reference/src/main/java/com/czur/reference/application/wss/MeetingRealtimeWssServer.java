package com.czur.reference.application.wss;

import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.realtime.RealtimeAudioIngress;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.protocol.realtime.RealtimeWireCodec;
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

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 独立于 JDK HTTP listener 的 TLS WebSocket 字节入口。
 *
 * <p>本类只守精确路径、固定子协议、session Bearer、二进制消息上限与连接生命周期；
 * 连接鉴权、逐帧重验、Proto 转换、Start 与持久音频调用由
 * {@link RealtimeWssConnection} 持有。握手凭据来自 V013/V014 核验端口，绝非
 * HTTP 用户 Bearer 或进程分配器缓存。调用方显式提供 TLS 身份、监听地址和端口，
 * 并拥有此监听器与共享数据库运行时的关闭顺序；本类不自动开启生产入口。</p>
 *
 * <p>Java-WebSocket 1.6.0 的 TRACE 会记录解密握手和短帧，构造时将库包与解码类
 * 固定为 INFO；这是进程级安全下限，不在关闭时恢复为可能暴露 Token/音频的 TRACE。
 * 日志只给固定拒绝类别，不记录 URL、头值、Token、音频或异常正文。</p>
 */
public final class MeetingRealtimeWssServer extends WebSocketServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(MeetingRealtimeWssServer.class.getName());
    private static final String PATH_PREFIX = "/v1/realtime/";
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private final RealtimeCredentialVerifier verifier;
    private final RealtimeAudioIngress ingress;
    private final Clock clock;
    private final ScheduledExecutorService expiryScanner = Executors.newSingleThreadScheduledExecutor(
            task -> {
                Thread thread = new Thread(task, "meeting-wss-expiry");
                thread.setDaemon(true);
                return thread;
            });
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicReference<Exception> startupFailure = new AtomicReference<>();
    private final AtomicBoolean startCalled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建尚未启动的 WSS listener。依赖生命周期仍由外层装配持有；无 TLS 上下文、
     * 凭据端口或音频编排时直接拒绝构造，不能退回明文 WS。
     */
    public MeetingRealtimeWssServer(InetSocketAddress address, SSLContext tls,
            RealtimeCredentialVerifier verifier, RealtimeAudioIngress ingress, Clock clock) {
        super(Objects.requireNonNull(address, "address"), 2,
                List.of(new Draft_6455(List.of(new DefaultExtension()), List.of(
                        new Protocol(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL)),
                        RealtimeWireCodec.MAX_ENVELOPE_BYTES)));
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.ingress = Objects.requireNonNull(ingress, "ingress");
        this.clock = Objects.requireNonNull(clock, "clock");
        setWebSocketFactory(new DefaultSSLWebSocketServerFactory(
                Objects.requireNonNull(tls, "tls")));
        setConnectionLostTimeout(0);
        Logger.getLogger("org.java_websocket").setLevel(Level.INFO);
        Logger.getLogger("org.java_websocket.WebSocketImpl").setLevel(Level.INFO);
    }

    /** 异步启动；调用方随后必须 awaitStarted，失败时关闭本 listener。 */
    @Override
    public synchronized void start() {
        if (closed.get() || !startCalled.compareAndSet(false, true)) {
            throw new IllegalStateException("WSS listener cannot start twice or after close");
        }
        super.start();
        expiryScanner.scheduleWithFixedDelay(this::closeExpiredSessions,
                1, 1, TimeUnit.SECONDS);
    }

    /** 单个 listener 的扫描器关闭空闲过期会话，不为每条连接创建定时线程。 */
    private void closeExpiredSessions() {
        if (closed.get()) return;
        for (WebSocket connection : getConnections()) {
            try {
                RealtimeWssConnection session = connection.getAttachment();
                if (session != null && session.closeIfInitiallyExpired()) {
                    connection.setAttachment(null);
                    connection.close(1008, "realtime rejected");
                }
            } catch (RuntimeException failure) {
                closeSession(connection);
                connection.close(1011, "realtime unavailable");
                LOGGER.log(Level.WARNING, "meeting WSS 到期扫描故障 category=INTERNAL");
            }
        }
    }

    /** 等待随机端口实际绑定；不会把可能含路径或系统细节的异常正文向上传递。 */
    public void awaitStarted(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (!started.await(timeout.toNanos(), TimeUnit.NANOSECONDS)
                || startupFailure.get() != null || getPort() <= 0) {
            throw new IllegalStateException("WSS listener failed to start");
        }
    }

    /** 精确会议路径、固定子协议与当前持久凭据核验都成功后才完成升级。 */
    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket connection, Draft draft, ClientHandshake request)
            throws InvalidDataException {
        String meetingId = meetingIdFromPath(request.getResourceDescriptor());
        if (meetingId == null || !CreateMeetingResponseFact.REALTIME_SUBPROTOCOL.equals(
                request.getFieldValue("Sec-WebSocket-Protocol"))) {
            throw rejectHandshake();
        }
        String authorization = request.getFieldValue("Authorization");
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{16,128}")) {
            throw rejectHandshake();
        }
        ServerHandshakeBuilder response = super.onWebsocketHandshakeReceivedAsServer(
                connection, draft, request);
        byte[] tokenBytes = authorization.substring("Bearer ".length())
                .getBytes(StandardCharsets.US_ASCII);
        SecretCredential credential;
        try {
            credential = SecretCredential.copyOf(tokenBytes);
        } finally {
            Arrays.fill(tokenBytes, (byte) 0);
        }
        try {
            RealtimeWssConnection session = RealtimeWssConnection.open(
                    meetingId, credential, verifier, ingress, clock);
            try {
                connection.setAttachment(session);
            } catch (RuntimeException failure) {
                session.close();
                throw failure;
            }
            return response;
        } catch (RuntimeException failure) {
            credential.close();
            throw rejectHandshake();
        }
    }

    private static String meetingIdFromPath(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX)) return null;
        String meetingId = path.substring(PATH_PREFIX.length());
        if (!meetingId.matches("mtg_[A-Za-z0-9_-]+")) return null;
        try {
            return MeetingIdPolicy.requireValid(meetingId);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static InvalidHandshakeException rejectHandshake() {
        LOGGER.log(Level.FINE, "meeting WSS 握手拒绝 category=INVALID_SESSION");
        return new InvalidHandshakeException("realtime handshake rejected");
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        if (connection.getAttachment() == null) connection.close(1008, "realtime rejected");
    }

    /** 文本帧从不进入应用端口。 */
    @Override
    public void onMessage(WebSocket connection, String text) {
        closeSession(connection);
        connection.close(1003, "binary only");
    }

    /** 在复制输入前限制聚合消息大小，然后只转交单连接适配器。 */
    @Override
    public void onMessage(WebSocket connection, ByteBuffer bytes) {
        RealtimeWssConnection session = connection.getAttachment();
        if (session == null || bytes == null || bytes.remaining() == 0
                || bytes.remaining() > RealtimeWireCodec.MAX_ENVELOPE_BYTES) {
            closeSession(connection);
            connection.close(1009, "realtime message rejected");
            return;
        }
        byte[] wire = new byte[bytes.remaining()];
        bytes.get(wire);
        try {
            RealtimeWssConnection.Result result = session.receive(wire);
            if (result instanceof RealtimeWssConnection.SendBinary reply) {
                if (!connection.isOpen() || connection.hasBufferedData()) {
                    // Java-WebSocket 的 outQueue 无界；单帧窗口下已有待发字节就不
                    // 再排 ACK。持久帧仍在 V015，客户端重连后按权威游标补发。
                    closeSession(connection);
                    connection.close(1013, "realtime reconnect required");
                } else {
                    connection.send(reply.bytes());
                }
            } else if (result instanceof RealtimeWssConnection.Terminate terminate) {
                connection.close(terminate.code(), "realtime rejected");
            }
        } catch (RuntimeException failure) {
            closeSession(connection);
            connection.close(1011, "realtime unavailable");
            LOGGER.log(Level.WARNING, "meeting WSS 连接处理故障 category=INTERNAL");
        } finally {
            Arrays.fill(wire, (byte) 0);
        }
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        closeSession(connection);
    }

    private static void closeSession(WebSocket connection) {
        RealtimeWssConnection session = connection.getAttachment();
        if (session != null) {
            session.close();
            connection.setAttachment(null);
        }
    }

    @Override
    public void onError(WebSocket connection, Exception error) {
        if (connection != null) {
            closeSession(connection);
            connection.close(1011, "realtime unavailable");
        }
        if (started.getCount() > 0) {
            startupFailure.compareAndSet(null, error);
            started.countDown();
        }
        LOGGER.log(Level.WARNING, "meeting WSS 网络故障 category=TRANSPORT");
    }

    @Override
    public void onStart() {
        started.countDown();
        LOGGER.log(Level.INFO, "meeting WSS 网络入口已启动");
    }

    /** 有界关闭此监听器及其连接；不关闭共享凭据、音频端口或数据库运行时。 */
    public synchronized void shutdown(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("WSS close timeout must be 0..5 minutes");
        }
        if (!closed.compareAndSet(false, true)) return;
        expiryScanner.shutdownNow();
        for (WebSocket connection : getConnections()) closeSession(connection);
        if (startCalled.get()) {
            stop((int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis())));
        }
        LOGGER.log(Level.INFO, "meeting WSS 网络入口已关闭");
    }

    /** 默认五秒关闭截止；中断时保留中断标志并向调用方报告未洁净关闭。 */
    @Override
    public void close() {
        try {
            shutdown(DEFAULT_CLOSE_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WSS close interrupted");
        }
    }
}
