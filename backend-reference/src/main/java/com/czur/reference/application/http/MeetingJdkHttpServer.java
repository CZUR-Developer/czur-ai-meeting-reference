package com.czur.reference.application.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDK HTTP server 到 {@link MeetingHttpRuntime} 的最小网络字节边界。
 *
 * <p>调用方必须显式指定监听地址，负责本对象和运行时的关闭顺序。本类不做身份
 * 验证、不装配业务或数据库；在 15-02 接入可信认证上下文之前，只能向运行时
 * 传入空上下文，故 GET/DELETE 仍按既有 401 关闭式处理。任何原始请求在构造
 * {@link HttpRequest} 前都先经过上限与歧义检查；日志不包含 URL、头值或正文。
 * 仅供参考后端使用，不作为 TLS 终点直接暴露在公网上。</p>
 */
public final class MeetingJdkHttpServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(MeetingJdkHttpServer.class.getName());
    private static final int MAX_QUERY_CHARS = 4096;

    private final HttpServer server;
    private final MeetingHttpRuntime runtime;
    private final HttpErrorMapper errors;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建尚未启动的监听器；传入端口 0 可由系统分配随机端口。
     *
     * @param address 明确的监听地址；是否允许非 loopback 由部署者决定
     * @param runtime 已装配的传输无关运行时，本类不拥有其生命周期
     * @param errors 与运行时使用同一错误映射器
     * @throws IOException 端口绑定失败时不留下运行中的监听器
     */
    public MeetingJdkHttpServer(
            InetSocketAddress address, MeetingHttpRuntime runtime, HttpErrorMapper errors)
            throws IOException {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.server = HttpServer.create(Objects.requireNonNull(address, "address"), 0);
        this.server.createContext("/", this::handle);
    }

    /** 开始接收请求；每个 server 实例只能启动一次。 */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("HTTP server is closed");
        }
        server.start();
        LOGGER.log(Level.INFO, "meeting HTTP 网络入口已启动");
    }

    /** 返回监听地址；端口 0 在启动后解析为实际随机端口。 */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            HttpResponse response;
            try {
                HttpRequest request = decode(exchange);
                response = runtime.handle(request, null);
            } catch (IllegalArgumentException badRequest) {
                LOGGER.log(Level.WARNING, "meeting HTTP 原始请求被拒绝 reason=INVALID_TRANSPORT");
                response = errors.exception(badRequest);
            } catch (RuntimeException unexpected) {
                LOGGER.log(Level.SEVERE, "meeting HTTP 网络入口异常 reason=INTERNAL_ERROR");
                response = errors.internalFailure();
            }
            write(exchange, response);
        }
    }

    private static HttpRequest decode(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getRawPath();
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                || path == null || path.length() > HttpRequest.MAX_PATH_CHARS
                || !path.startsWith("/") || path.contains("%") || path.contains("\\")
                || path.contains("//") || path.endsWith("/.") || path.endsWith("/..")
                || path.contains("/./") || path.contains("/../") || !isVisibleAscii(path)) {
            throw new IllegalArgumentException("URI path is invalid");
        }
        String rawQuery = uri.getRawQuery();
        Map<String, String> query = decodeQuery(rawQuery);
        Map<String, String> headers = decodeHeaders(exchange.getRequestHeaders());
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            // 不信任 Content-Length；chunked 请求也只会读取上限加一个字节。
            body = input.readNBytes(HttpRequest.MAX_BODY_BYTES + 1);
        }
        if (body.length > HttpRequest.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("body exceeds transport limit");
        }
        return new HttpRequest(exchange.getRequestMethod(), path, headers, query,
                decodeUtf8(body));
    }

    private static Map<String, String> decodeHeaders(Headers raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        long size = 0;
        int count = 0;
        for (var entry : raw.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isEmpty() || !name.chars().allMatch(
                    character -> isHeaderToken(character))) {
                throw new IllegalArgumentException("header name is invalid");
            }
            if (entry.getValue() == null || entry.getValue().size() != 1) {
                throw new IllegalArgumentException("duplicate header is invalid");
            }
            String value = entry.getValue().get(0);
            if (value == null || value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("header value is invalid");
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (headers.putIfAbsent(normalized, value) != null) {
                throw new IllegalArgumentException("duplicate header is invalid");
            }
            count++;
            size += normalized.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length;
            if (count > HttpRequest.MAX_HEADER_COUNT || size > HttpRequest.MAX_HEADER_BYTES) {
                throw new IllegalArgumentException("headers exceed transport limit");
            }
        }
        return headers;
    }

    private static boolean isHeaderToken(int value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
    }

    private static Map<String, String> decodeQuery(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.length() > MAX_QUERY_CHARS || !isVisibleAscii(raw) || raw.indexOf('+') >= 0) {
            throw new IllegalArgumentException("query is invalid");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : raw.split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator <= 0 || part.indexOf('=', separator + 1) >= 0) {
                throw new IllegalArgumentException("query is invalid");
            }
            String key = decodePercent(part.substring(0, separator));
            String value = decodePercent(part.substring(separator + 1));
            if (key.isBlank() || result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate or empty query key");
            }
        }
        return result;
    }

    private static String decodePercent(String raw) {
        byte[] bytes = new byte[raw.length()];
        int length = 0;
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (character == '%') {
                if (i + 2 >= raw.length()) {
                    throw new IllegalArgumentException("percent encoding is invalid");
                }
                int first = Character.digit(raw.charAt(++i), 16);
                int second = Character.digit(raw.charAt(++i), 16);
                if (first < 0 || second < 0) {
                    throw new IllegalArgumentException("percent encoding is invalid");
                }
                bytes[length++] = (byte) (first * 16 + second);
            } else {
                bytes[length++] = (byte) character;
            }
        }
        String result = decodeUtf8(java.util.Arrays.copyOf(bytes, length));
        if (result.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("query control is invalid");
        }
        return result;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid UTF-8");
        }
    }

    private static boolean isVisibleAscii(String value) {
        return value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }

    private static void write(HttpExchange exchange, HttpResponse response) throws IOException {
        for (var header : response.headers().entrySet()) {
            exchange.getResponseHeaders().set(header.getKey(), header.getValue());
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    /** 停止接收新连接；关闭运行时与数据库是外层 owner 的责任。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            LOGGER.log(Level.INFO, "meeting HTTP 网络入口已关闭");
        }
    }
}
