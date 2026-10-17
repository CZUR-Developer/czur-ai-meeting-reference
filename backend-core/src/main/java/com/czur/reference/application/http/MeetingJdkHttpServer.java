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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDK HTTP server 到 {@link MeetingHttpDispatcher} 的最小网络字节边界。
 *
 * <p>调用方必须显式指定监听地址，负责本对象和运行时的关闭顺序。本类不装配
 * 业务或数据库；可选的可信身份解析器只用于已匹配的查询/删除路由，Stop/Create
 * 继续由各自应用流程处理。不传解析器的旧构造器继续以空上下文关闭式拒绝
 * 业务 GET/DELETE。内部健康路径只按完整路径匹配，不调用身份或会议端口；
 * liveness 只反映本监听器已启动且尚未关闭，readiness 由同一装配根绑定的
 * 数据库只读探针给出。任何原始请求在构造
 * {@link HttpRequest} 前都先经过上限与歧义检查；日志不包含 URL、头值或正文。
 * 仅供参考后端使用，不作为 TLS 终点直接暴露在公网上。</p>
 */
public final class MeetingJdkHttpServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(MeetingJdkHttpServer.class.getName());
    private static final int MAX_QUERY_CHARS = 4096;
    private static final int DEFAULT_WORKERS = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 16;
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    /** 仅供受信网络调用的精确存活路径；不属于公开会议协议。 */
    public static final String LIVENESS_PATH = "/internal/health/liveness";
    /** 仅供受信网络调用的精确就绪路径；不属于公开会议协议。 */
    public static final String READINESS_PATH = "/internal/health/readiness";
    private static final HttpResponse HEALTH_OK = HttpResponse.json(200, "{\"status\":\"UP\"}");
    private static final HttpResponse HEALTH_UNAVAILABLE =
            HttpResponse.json(503, "{\"status\":\"DOWN\"}");
    private static final HttpResponse HEALTH_BAD_REQUEST =
            HttpResponse.json(400, "{\"status\":\"INVALID_REQUEST\"}");
    private static final HttpResponse HEALTH_METHOD_NOT_ALLOWED = new HttpResponse(
            405, Map.of("content-type", "application/json", "cache-control", "no-store",
                    "allow", "GET"), "{\"status\":\"METHOD_NOT_ALLOWED\"}");
    private final HttpServer server;
    private final InetSocketAddress bindAddress;
    private final MeetingHttpDispatcher dispatcher;
    private final HttpErrorMapper errors;
    private final BooleanSupplier readinessCheck;
    private final RequestExecutor requests;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile DrainResult drainResult;

    /**
     * 创建尚未绑定、尚未启动的监听器；传入端口 0 在启动时随机分配。
     *
     * @param address 明确的监听地址；是否允许非 loopback 由部署者决定
     * @param runtime 已装配的传输无关运行时，本类不拥有其生命周期
     * @param errors 与运行时使用同一错误映射器
     * @throws IOException JDK server 创建失败；地址绑定在 {@link #start()} 执行
     */
    public MeetingJdkHttpServer(
            InetSocketAddress address, MeetingHttpRuntime runtime, HttpErrorMapper errors)
            throws IOException {
        this(address, runtime, errors, null);
    }

    /**
     * 创建可认证 GET/list/DELETE 的网络入口。解析器的可信配置与身份 Provider
     * 由外层装配；它与运行时必须共享同一错误映射实例。未匹配路由不会访问解析器。
     */
    public MeetingJdkHttpServer(
            InetSocketAddress address, MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver) throws IOException {
        this(address, runtime, errors, contextResolver, () -> false);
    }

    /**
     * 创建带内部健康路径的网络入口。就绪探针必须由持有该运行时的装配根绑定到
     * 同一 {@code JdbcDatabaseRuntime.checkReadiness()}；每次 GET 都重新读取，
     * 不缓存启动状态。探针异常只映射固定 503。旧构造器没有数据库绑定，
     * 因而 readiness 关闭式返回 503。
     *
     * @param readinessCheck 同一装配根的只读数据库就绪探针，不能调用业务端口
     */
    public MeetingJdkHttpServer(
            InetSocketAddress address, MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            BooleanSupplier readinessCheck) throws IOException {
        this(address, runtime, errors, contextResolver, readinessCheck, null);
    }

    public MeetingJdkHttpServer(
            InetSocketAddress address, MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            BooleanSupplier readinessCheck, ResultEmailEndpoint resultEmail) throws IOException {
        this(address, runtime, errors, contextResolver, readinessCheck, resultEmail,
                DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * 使用装配根已经创建的同一业务分发器，不重复装配身份、邮件或会议运行时。
     * 健康路径和网络生命周期仍由本listener负责；本构造器不启动监听。
     *
     * @param dispatcher 与其他HTTP transport共享的业务入口，不转移资源所有权
     * @param readinessCheck 同一装配根的只读数据库就绪探针
     */
    public MeetingJdkHttpServer(InetSocketAddress address, MeetingHttpDispatcher dispatcher,
            BooleanSupplier readinessCheck) throws IOException {
        this(address, dispatcher, readinessCheck, DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * 供小型网络实例和测试夹具配置有界执行器；至少两个 worker，使一个慢
     * readiness 或业务调用不占住唯一 liveness 执行线程。
     */
    MeetingJdkHttpServer(
            InetSocketAddress address, MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            BooleanSupplier readinessCheck, int workers, int queueCapacity) throws IOException {
        this(address, runtime, errors, contextResolver, readinessCheck, null,
                workers, queueCapacity);
    }

    private MeetingJdkHttpServer(
            InetSocketAddress address, MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            BooleanSupplier readinessCheck, ResultEmailEndpoint resultEmail,
            int workers, int queueCapacity) throws IOException {
        this(address, new MeetingHttpDispatcher(runtime, errors, contextResolver, resultEmail),
                readinessCheck, workers, queueCapacity);
    }

    private MeetingJdkHttpServer(InetSocketAddress address, MeetingHttpDispatcher dispatcher,
            BooleanSupplier readinessCheck, int workers, int queueCapacity) throws IOException {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.errors = dispatcher.errorMapper();
        this.readinessCheck = Objects.requireNonNull(readinessCheck, "readinessCheck");
        this.requests = new RequestExecutor(workers, queueCapacity);
        this.bindAddress = Objects.requireNonNull(address, "address");
        HttpServer bound;
        try {
            bound = HttpServer.create();
        } catch (IOException | RuntimeException | Error failure) {
            requests.forceStop();
            throw failure;
        }
        try {
            bound.setExecutor(requests);
            bound.createContext("/", this::handle);
            this.server = bound;
        } catch (RuntimeException | Error failure) {
            bound.stop(0);
            requests.forceStop();
            throw failure;
        }
    }

    /**
     * 绑定并开始接收请求；每个实例只能启动一次。绑定或启动失败时尝试
     * stop JDK server 并关闭执行器；外层 owner 仍负责关闭 HTTP runtime 和数据库。
     * 已绑定但 JDK 内部 start 异常的极端窗口不保证端口立即可重绑。
     *
     * @throws IOException 地址已占用或绑定失败
     */
    public synchronized void start() throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("HTTP server is closed");
        }
        if (started.get()) {
            throw new IllegalStateException("HTTP server already started");
        }
        try {
            server.bind(bindAddress, 0);
            server.start();
            started.set(true);
            LOGGER.log(Level.INFO, "meeting HTTP 网络入口已启动");
        } catch (IOException | RuntimeException | Error failure) {
            closed.set(true);
            requests.closeGate();
            try {
                server.stop(0);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            requests.forceStop();
            LOGGER.log(Level.WARNING, "meeting HTTP 网络启动失败 reason={0}",
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** 返回已启动的监听地址；未启动时拒绝读取未分配的随机端口。 */
    public InetSocketAddress address() {
        if (!started.get()) {
            throw new IllegalStateException("HTTP server has not started");
        }
        return server.getAddress();
    }

    /** 仅供同包网络门禁确认 Exchange 已进入执行器队列，非业务指标。 */
    int queuedRequestsForTest() {
        return requests.queued();
    }

    /** 仅供同包门禁同步关闭门与并发 socket 断言。 */
    boolean closingForTest() { return closed.get(); }

    /** 仅供同包门禁确认强制关闭后的 worker 已实际退出。 */
    int liveWorkersForTest() { return requests.liveWorkers(); }

    /** 仅供同包门禁确认执行器终止，不能替代超时返回时的证据快照。 */
    boolean executorTerminatedForTest() { return requests.terminated(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            HttpResponse response;
            try {
                if (closed.get()) {
                    response = shutdownResponse(exchange);
                } else if (MeetingArtifactHttpEndpoint.matchesUploadPath(
                        exchange.getRequestMethod(), exchange.getRequestURI().getRawPath())) {
                    // 录音正文必须保持原始流；不能先走 UTF-8 String/JSON 解码。
                    response = dispatcher.handle(decodeBinary(exchange));
                } else {
                    HttpRequest request = decode(exchange);
                    if (closed.get()) {
                        response = shutdownResponse(exchange);
                    } else if (dispatcher.handlesArtifactMedia(request)) {
                        writeMedia(exchange, dispatcher.openArtifactMedia(request));
                        return;
                    } else {
                        response = dispatch(request);
                    }
                }
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

    private HttpResponse shutdownResponse(HttpExchange exchange) {
        String path = exchange.getRequestURI().getRawPath();
        if (LIVENESS_PATH.equals(path) || READINESS_PATH.equals(path)) {
            return HEALTH_UNAVAILABLE;
        }
        return errors.runtimeUnavailable();
    }

    private HttpResponse dispatch(HttpRequest request) {
        if (LIVENESS_PATH.equals(request.path()) || READINESS_PATH.equals(request.path())) {
            return health(request);
        }
        return dispatcher.handle(request);
    }

    private HttpResponse health(HttpRequest request) {
        if (!"GET".equals(request.method())) {
            return HEALTH_METHOD_NOT_ALLOWED;
        }
        if (!request.query().isEmpty() || !request.body().isEmpty()) {
            return HEALTH_BAD_REQUEST;
        }
        if (!started.get() || closed.get()) {
            return HEALTH_UNAVAILABLE;
        }
        if (LIVENESS_PATH.equals(request.path())) {
            return HEALTH_OK;
        }
        try {
            boolean ready = readinessCheck.getAsBoolean();
            LOGGER.log(Level.FINE, "meeting HTTP readiness 检查结果 ready={0}", ready);
            return ready ? HEALTH_OK : HEALTH_UNAVAILABLE;
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "meeting HTTP readiness 探针异常 reason=PROBE_FAILURE exception={0}",
                    failure.getClass().getSimpleName());
            return HEALTH_UNAVAILABLE;
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

    /**
     * 只解码媒体上传的元数据并保留请求流；正文长度必须显式声明，避免把未知长度
     * 的网络输入交给 ArtifactStore。关闭责任由 BinaryHttpRequest/dispatcher 负责。
     */
    private static BinaryHttpRequest decodeBinary(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getRawPath();
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                || path == null || path.length() > HttpRequest.MAX_PATH_CHARS
                || !path.startsWith("/") || path.contains("%") || path.contains("\\")
                || path.contains("//") || path.endsWith("/.") || path.endsWith("/..")
                || path.contains("/./") || path.contains("/../") || !isVisibleAscii(path)) {
            throw new IllegalArgumentException("URI path is invalid");
        }
        Map<String, String> query = decodeQuery(uri.getRawQuery());
        Map<String, String> headers = decodeHeaders(exchange.getRequestHeaders());
        String rawLength = headers.get("content-length");
        if (rawLength == null || !rawLength.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("binary content-length is invalid");
        }
        long contentLength;
        try {
            contentLength = Long.parseLong(rawLength);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("binary content-length is invalid");
        }
        var input = exchange.getRequestBody();
        try {
            return new BinaryHttpRequest(exchange.getRequestMethod(), path, headers, query,
                    contentLength, input);
        } catch (RuntimeException failure) {
            input.close();
            throw failure;
        }
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
        // 参考静态结果页可部署在不同源；能力发现提供同一结果链接有效期。
        String resultPath = exchange.getRequestURI().getRawPath();
        if ("GET".equals(exchange.getRequestMethod())
                && (ReferenceDeploymentCapabilitiesEndpoint.PATH.equals(resultPath)
                || resultPath.matches("/v1/meetings/mtg_[A-Za-z0-9_-]+/manifest")
                || resultPath.matches("/v1/artifacts/art_[A-Za-z0-9_-]+"))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        }
        for (var header : response.headers().entrySet()) {
            exchange.getResponseHeaders().set(header.getKey(), header.getValue());
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    /**
     * 将 READY 媒体按 Manifest 长度流式写出，支持播放器使用的单段字节 Range。
     *
     * <p>浏览器会先请求文件头，再请求文件尾读取 OGG 时长，拖动播放时也会请求新的
     * 字节区间。这里只改变 HTTP 传输，不改变 Artifact 的归属、有效期或 READY 规则；
     * 完整和分段读取都关闭同一个 Store 句柄，不把长录音读成 byte[]。</p>
     */
    private void writeMedia(HttpExchange exchange,
            com.czur.reference.application.port.MeetingArtifactMediaPort.Result result)
            throws IOException {
        if (!(result instanceof com.czur.reference.application.port.MeetingArtifactMediaPort.Found found)) {
            write(exchange, dispatcher.errorMapper().artifactMedia(result));
            return;
        }
        try (found) {
            var reference = found.reference();
            // 当前产物没有 HTTP 缓存校验器；带 If-Range 时返回完整实体，不能猜测版本匹配。
            String requestedRange = exchange.getRequestHeaders().getFirst("If-Range") == null
                    ? exchange.getRequestHeaders().getFirst("Range") : null;
            MediaRange range = MediaRange.select(requestedRange, reference.size());
            var headers = exchange.getResponseHeaders();
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Content-Type", reference.mediaType());
            headers.set("Cache-Control", "no-store");
            headers.set("Content-Disposition", "inline");
            headers.set("Accept-Ranges", "bytes");
            if (range.status() == 416) {
                headers.set("Content-Range", "bytes */" + reference.size());
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            if (range.status() == 206) {
                headers.set("Content-Range", "bytes " + range.first() + "-"
                        + (range.first() + range.length() - 1) + "/" + reference.size());
            }
            exchange.sendResponseHeaders(range.status(), range.length() == 0 ? -1 : range.length());
            found.content().skipNBytes(range.first());
            copyExactly(found.content(), exchange.getResponseBody(), range.length(),
                    range.status() == 200);
        }
    }

    private static void copyExactly(java.io.InputStream input, java.io.OutputStream output,
            long expected, boolean completeEntity) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long copied = 0;
        while (copied < expected) {
            int wanted = (int) Math.min(buffer.length, expected - copied);
            int count = input.read(buffer, 0, wanted);
            if (count < 0) throw new IOException("media stream ended before declared length");
            if (count == 0) continue;
            output.write(buffer, 0, count);
            copied += count;
        }
        if (completeEntity && input.read() >= 0) {
            throw new IOException("media stream exceeds declared length");
        }
    }

    /** 包内 HTTP 字节区间值；不进入公共媒体端口，也不改变共享客户端协议。 */
    record MediaRange(long first, long length, int status) {
        /**
         * 支持固定区间、从某处至文件尾、末尾 N 字节三种播放器请求。
         * 多段或不认识的格式直接按完整实体返回；本机宿主不生成 multipart 正文。
         * 可解析但完全超出文件的区间返回标准 416，避免伪造空的 206。
         */
        static MediaRange select(String value, long size) {
            var full = new MediaRange(0, size, 200);
            if (value == null) return full;
            var match = java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)")
                    .matcher(value.trim());
            if (!match.matches() || match.group(1).isEmpty() && match.group(2).isEmpty()) {
                return full;
            }
            try {
                if (match.group(1).isEmpty()) {
                    long suffix = Long.parseLong(match.group(2));
                    if (suffix == 0 || size == 0) return new MediaRange(0, 0, 416);
                    long length = Math.min(suffix, size);
                    return new MediaRange(size - length, length, 206);
                }
                long first = Long.parseLong(match.group(1));
                long last = match.group(2).isEmpty() ? size - 1 : Long.parseLong(match.group(2));
                if (first >= size || last < first) return new MediaRange(0, 0, 416);
                last = Math.min(last, size - 1);
                return new MediaRange(first, last - first + 1, 206);
            } catch (NumberFormatException unsupportedRange) {
                return full;
            }
        }
    }

    /**
     * 以执行器受理为边界排空请求；旧请求完成或在 handler 明确收到健康路径
     * 固定 503、业务路径公开错误 envelope 后才关闭监听器。排空期间 socket
     * 尚绑定，但 gate 拒绝新的执行器任务。
     * gate 后未被执行器受理的新连接由 JDK 断开，不承诺收到 HTTP 503；
     * 这些连接不计入已受理任务或洁净排空的成功请求。
     * 超时会停止监听并尝试中断 worker，返回非洁净结果；不保证忽略中断的
     * 线程已退出，部署方必须终止进程并按持久事实恢复。
     *
     * @param timeout 整个关闭过程的单一非负截止时间，最多五分钟
     * @return 已受理任务、排队丢弃与仍执行 worker 的关闭快照；重复调用返回同一结果
     */
    public synchronized DrainResult shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("HTTP close timeout must be 0..5 minutes");
        }
        if (drainResult != null) {
            return drainResult;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        closed.set(true);
        requests.closeGate();
        boolean interrupted = false;
        try {
            requests.awaitEmpty(deadline);
        } catch (InterruptedException failure) {
            interrupted = true;
        }
        int unfinished = requests.pending();
        // stop(0) 仅在本执行器已受理任务排空之后作为正常路径使用。
        // 超时路径强制断开 socket，并把遗留任务明确标记为非洁净。
        server.stop(0);
        int discarded = 0;
        if (unfinished == 0 && !interrupted) {
            requests.orderlyStop();
        } else {
            discarded = requests.forceStop();
        }
        boolean terminated = false;
        if (!interrupted) {
            try {
                terminated = requests.awaitTermination(deadline);
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (!terminated) {
            discarded += requests.forceStop();
        }
        int liveWorkers = requests.liveWorkers();
        boolean clean = unfinished == 0 && discarded == 0 && terminated && !interrupted;
        drainResult = new DrainResult(clean, unfinished, discarded, liveWorkers);
        LOGGER.log(clean ? Level.INFO : Level.WARNING,
                "meeting HTTP 网络入口关闭 clean={0} unfinishedAccepted={1} "
                        + "discardedQueued={2} liveWorkers={3}",
                new Object[]{clean, unfinished, discarded, liveWorkers});
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return drainResult;
    }

    /** 使用默认五秒截止时间；需判断强制关闭时应调用 {@link #shutdown(Duration)}。 */
    @Override
    public void close() {
        shutdown(DEFAULT_CLOSE_TIMEOUT);
    }

    /**
     * 网络入口关闭结果。{@code unfinishedAccepted} 是截止时间到时仍未完成的
     * 已受理任务数，包含排队与执行中；{@code discardedQueued} 是强制停止时
     * 未进入 handler 的排队数；{@code liveWorkers} 仅为返回时的活线程快照。
     * {@code clean=false} 时不能据此声称所有业务请求已完成或线程已回收。
     */
    public record DrainResult(
            boolean clean, int unfinishedAccepted, int discardedQueued, int liveWorkers) {
    }

    /** 同一把锁串行化执行器受理与关闭 gate，避免漏计队列中的 Exchange。 */
    private static final class RequestExecutor implements Executor {
        private static final AtomicInteger THREAD_IDS = new AtomicInteger();
        private final Object lock = new Object();
        private final ThreadPoolExecutor pool;
        private boolean accepting = true;
        private int pending;

        private RequestExecutor(int workers, int queueCapacity) {
            if (workers < 2 || workers > 16 || queueCapacity < 1 || queueCapacity > 1024) {
                throw new IllegalArgumentException("HTTP executor bounds are invalid");
            }
            pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity), task -> {
                        Thread thread = new Thread(task,
                                "meeting-http-request-" + THREAD_IDS.incrementAndGet());
                        thread.setDaemon(false);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
        }

        @Override
        public void execute(Runnable task) {
            Objects.requireNonNull(task, "task");
            synchronized (lock) {
                if (!accepting) {
                    throw new RejectedExecutionException("HTTP request gate is closed");
                }
                pending++;
                try {
                    pool.execute(() -> {
                        try {
                            task.run();
                        } finally {
                            synchronized (lock) {
                                pending--;
                                lock.notifyAll();
                            }
                        }
                    });
                } catch (RuntimeException | Error failure) {
                    pending--;
                    lock.notifyAll();
                    throw failure;
                }
            }
        }

        private void closeGate() {
            synchronized (lock) {
                accepting = false;
            }
        }

        private void awaitEmpty(long deadline) throws InterruptedException {
            synchronized (lock) {
                while (pending > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                }
            }
        }

        private int pending() {
            synchronized (lock) {
                return pending;
            }
        }

        private int queued() { return pool.getQueue().size(); }

        private void orderlyStop() { pool.shutdown(); }

        private int forceStop() { return pool.shutdownNow().size(); }

        private boolean awaitTermination(long deadline) throws InterruptedException {
            return pool.awaitTermination(Math.max(0, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
        }

        private int liveWorkers() { return pool.getActiveCount(); }

        private boolean terminated() { return pool.isTerminated(); }
    }
}
