package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.StopMeetingIntent;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会议控制面最小的 transport-neutral HTTP 适配器。
 *
 * <p>本类只识别当前 OpenAPI 切片的 HTTP 方法、路径和 query 参数，把已经由
 * 外层身份/JSON 解码器准备好的命令交给现有 application port，并把稳定结果
 * 交给 {@link HttpErrorMapper}。它不依赖 Servlet、Netty、JDK HttpServer 或
 * 任何连接生命周期；具体 runtime 只需把 {@link HttpRequest} 传入，再写出
 * {@link HttpResponse}。</p>
 *
 * <p>本类本身不验证 Bearer、权限、计费或能力；显式开启 Create 时使用专用严格
 * 解码器，并调用已认证的完整应用服务。GET/DELETE 要求调用方提供已经验证的
 * {@link MeetingCommandContext}。Create 默认关闭并先返回 501；Stop 只有注入
 * 认证应用服务后才开放。两者均不回退旧的未认证命令服务。</p>
 *
 * <p>日志只记录固定 operation 名和状态码，不记录原始 path、meetingId、Token、
 * request body、receipt 或异常消息。未知路径和当前未实现的后续 OpenAPI 路由
 * 返回统一 404；适配器不会伪造业务成功响应。</p>
 */
public final class MeetingHttpAdapter {
    private static final Logger LOGGER = Logger.getLogger(MeetingHttpAdapter.class.getName());
    private static final String MEETINGS_PATH = "/v1/meetings";
    private static final Pattern DETAIL_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)$");
    private static final Pattern STOP_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+):stop$");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MeetingQueryPort queryPort;
    private final MeetingDeletionPort deletionPort;
    private final RequestDecoder decoder;
    private final HttpErrorMapper errors;
    private final AuthenticatedMeetingStopApplicationService stopService;
    private final AuthenticatedCompleteMeetingApplicationService createService;
    private final CreateMeetingHttpRequestDecoder createDecoder;
    private final boolean createEnabled;

    /** 使用随机 opaque diagnosticId 的默认错误映射器。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder) {
        this(queryPort, deletionPort, decoder, new HttpErrorMapper());
    }

    /** 测试或运行时可以注入诊断 ID 生成器已经固定的错误映射器。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.deletionPort = Objects.requireNonNull(deletionPort, "deletionPort");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.stopService = null;
        this.createService = null;
        this.createDecoder = null;
        this.createEnabled = false;
    }

    /** 装配完整认证 Stop application service 的构造器。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.deletionPort = Objects.requireNonNull(deletionPort, "deletionPort");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.stopService = Objects.requireNonNull(stopService, "stopService");
        this.createService = null;
        this.createDecoder = null;
        this.createEnabled = false;
    }

    /**
     * 显式装配完整 Create；开关默认由其它构造器保持关闭。
     *
     * <p>启用时必须同时传入已认证的 Create 应用服务和专用严格解码器。关闭时允许
     * 保留这两个依赖以便灰度装配，但 Create 分支在调用任一依赖前就返回 501。
     * 新装配必须同时提供原有 Stop 服务，避免出现仅能创建、无法停止的产品配置。</p>
     */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.deletionPort = Objects.requireNonNull(deletionPort, "deletionPort");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.stopService = Objects.requireNonNull(stopService, "stopService");
        if (createEnabled) {
            Objects.requireNonNull(createService, "createService");
            Objects.requireNonNull(createDecoder, "createDecoder");
        }
        this.createService = createService;
        this.createDecoder = createDecoder;
        this.createEnabled = createEnabled;
    }

    /** 与 runtime 常见的 service-before-mapper 装配顺序兼容。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            AuthenticatedMeetingStopApplicationService stopService,
            HttpErrorMapper errors) {
        this(queryPort, deletionPort, decoder, errors, stopService);
    }

    /** 使用默认错误映射器装配认证 Stop。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            AuthenticatedMeetingStopApplicationService stopService) {
        this(queryPort, deletionPort, decoder, new HttpErrorMapper(), stopService);
    }

    /**
     * 供同包运行时装配复用本适配器的错误映射器。
     *
     * <p>该访问器不暴露任何请求、凭据或业务状态；保持包级可见，避免把
     * transport 组装细节扩大为公共业务 API。</p>
     */
    HttpErrorMapper errorMapper() {
        return errors;
    }

    /**
     * 处理一个请求并返回 transport-neutral 响应。
     *
     * @param request 已由 HTTP runtime 约束过的请求值对象
     * @param context 外层身份 Provider 验证出的上下文；GET/DELETE 路由缺失时固定返回 401
     * @return 成功 payload 或统一 ErrorEnvelope；不会抛出端口运行时异常
     */
    public HttpResponse handle(HttpRequest request, MeetingCommandContext context) {
        HttpResponse response;
        String operation = "request";
        try {
            Objects.requireNonNull(request, "request");
            operation = operationOf(request);
            response = dispatch(request, context);
        } catch (RuntimeException failure) {
            response = errors.exception(failure);
        }
        LOGGER.log(Level.INFO, "meeting HTTP operation={0} status={1}",
                new Object[]{operation, response.status()});
        return response;
    }

    private HttpResponse dispatch(HttpRequest request, MeetingCommandContext context) {
        String method = request.method();
        String path = request.path();

        if ("POST".equals(method) && MEETINGS_PATH.equals(path)) {
            if (!createEnabled) {
                // 即使请求头/body 无效，也要先关闭式拒绝，且不能触发任何解码或业务端口。
                return errors.notImplemented();
            }
            try (CreateMeetingHttpRequestDecoder.Decoded decoded = createDecoder.decode(request)) {
                try {
                    return errors.create(createService.create(
                            decoded.verificationRequest(), decoded.requestFact()));
                } catch (RuntimeException unexpected) {
                    // 解码已成功；此后异常来自应用端口或公开响应投影，不能把
                    // IllegalArgumentException 误判成客户端 400。try-with-resources
                    // 仍负责清理凭据，且响应/日志均不包含异常正文。
                    return errors.internalFailure();
                }
            }
        }

        Matcher stop = STOP_PATH.matcher(path);
        if ("POST".equals(method) && stop.matches()) {
            if (stopService == null) {
                return errors.notImplemented();
            }
            String meetingId = stop.group(1);
            String idempotencyKey = requireIdempotencyKey(request);
            validateEmptyStopBody(request);
            StopRequest decoded = decoder.decodeStop(request, meetingId, idempotencyKey);
            Objects.requireNonNull(decoded, "decoded stop request");
            try {
                requirePathMeetingId(meetingId, decoded.intent().meetingId());
                requireDecodedIdempotencyKey(idempotencyKey, decoded.intent().idempotencyKey());
            } catch (RuntimeException mismatch) {
                // 解码器已经创建凭据但尚未交给 application service，拒绝时也要清除。
                decoded.verificationRequest().credential().close();
                throw mismatch;
            }
            return errors.stop(stopService.stop(decoded.verificationRequest(), decoded.intent()));
        }

        if ("GET".equals(method) && MEETINGS_PATH.equals(path)) {
            if (context == null) {
                return errors.missingContext();
            }
            return errors.list(queryPort.list(new MeetingQueryPort.ListRequest(
                    context, request.query().get("pageToken"), pageSize(request))));
        }

        Matcher detail = DETAIL_PATH.matcher(path);
        if (detail.matches() && context == null
                && ("GET".equals(method) || "DELETE".equals(method))) {
            return errors.missingContext();
        }
        if ("GET".equals(method) && detail.matches()) {
            return errors.query(queryPort.get(new MeetingQueryPort.GetRequest(
                    context, detail.group(1))));
        }
        if ("DELETE".equals(method) && detail.matches()) {
            String meetingId = detail.group(1);
            String idempotencyKey = requireIdempotencyKey(request);
            DeleteRequest delete = decoder.decodeDelete(request, context);
            requireDecodedIdempotencyKey(idempotencyKey, delete.idempotencyKey());
            requirePathMeetingId(meetingId, delete.meetingId());
            if (!context.equals(delete.context())) {
                throw new IllegalArgumentException("delete context does not match request context");
            }
            return errors.deletion(deletionPort.delete(delete));
        }
        return errors.invalidRoute();
    }

    private static int pageSize(HttpRequest request) {
        String raw = request.query().get("pageSize");
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("pageSize is invalid");
        }
    }

    private static void requirePathMeetingId(String pathMeetingId, String commandMeetingId) {
        if (commandMeetingId == null || !pathMeetingId.equals(commandMeetingId)) {
            throw new IllegalArgumentException("path meetingId does not match command");
        }
    }

    /** 校验 OpenAPI 要求的幂等头；具体 scope/digest 仍由 decoder/端口负责。 */
    private static String requireIdempotencyKey(HttpRequest request) {
        String value = request.header("idempotency-key");
        if (value == null || value.isBlank() || value.length() < 16 || value.length() > 128) {
            throw new IllegalArgumentException("idempotency-key is invalid");
        }
        return value;
    }

    private static void requireDecodedIdempotencyKey(String header, String decoded) {
        if (decoded == null || !header.equals(decoded)) {
            throw new IllegalArgumentException("decoded idempotency-key does not match header");
        }
    }

    /**
     * Stop 的请求体可省略；若提供，只接受 UTF-8 JSON 空对象。
     *
     * <p>必须在调用解码器（及创建凭据）前执行。空白正文不是“省略”；未知字段
     * 和尾随内容不得静默忽略。错误描述固定，不拼接正文或 Content-Type。</p>
     */
    private static void validateEmptyStopBody(HttpRequest request) {
        String body = request.body();
        if (body.isEmpty()) {
            return;
        }
        String contentType = request.header("content-type");
        if (contentType == null || !contentType.matches(
                "(?i)application/json(?:[ \\t]*;[ \\t]*charset=utf-8)?")) {
            throw new IllegalArgumentException("stop content-type is invalid");
        }
        try {
            JsonNode node = JSON.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (node == null || !node.isObject() || node.size() != 0) {
                throw new IllegalArgumentException("stop body must be an empty object");
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("stop body is invalid JSON");
        }
    }

    private static String operationOf(HttpRequest request) {
        if ("POST".equals(request.method()) && MEETINGS_PATH.equals(request.path())) {
            return "create";
        }
        if ("POST".equals(request.method()) && STOP_PATH.matcher(request.path()).matches()) {
            return "stop";
        }
        if ("GET".equals(request.method()) && MEETINGS_PATH.equals(request.path())) {
            return "list";
        }
        if (DETAIL_PATH.matcher(request.path()).matches()) {
            return switch (request.method()) {
                case "GET" -> "get";
                case "DELETE" -> "delete";
                default -> "route";
            };
        }
        return "route";
    }

    /**
     * 由具体 HTTP runtime 或 JSON codec 提供的删除请求解码边界。
     *
     * <p>实现可以读取请求头中的幂等键和服务端时间，但必须生成规范摘要；adapter
     * 不把原始 JSON、Bearer 或供应商字段复制进领域端口。delete 使用 adapter
     * 已检查过的认证 context 生成 {@link DeleteRequest}，不得从客户端 body 覆盖
     * 其部署或主体。完整 Create 使用独立的严格解码器，不进入这个兼容旧路由的
     * 接口；Stop 解码只在完整认证 application service 装配时使用。</p>
     */
    public interface RequestDecoder {
        /** 依据已验证 context 解码删除输入，不得接受客户端覆盖的归属字段。 */
        DeleteRequest decodeDelete(HttpRequest request, MeetingCommandContext context);

        /** 解码 Stop 的认证凭据与最小业务意图；不得把 body 未支持字段静默丢弃。 */
        default StopRequest decodeStop(HttpRequest request, String pathMeetingId,
                String idempotencyKey) {
            return decodeStop(request, pathMeetingId);
        }

        /** 兼容只需要路径校验输入的 runtime decoder。 */
        default StopRequest decodeStop(HttpRequest request, String pathMeetingId) {
            return decodeStop(request);
        }

        /** 最小 Stop 解码边界；实现负责从可信 HTTP 入口生成身份请求。 */
        default StopRequest decodeStop(HttpRequest request) {
            throw new IllegalArgumentException("stop decoder is not configured");
        }
    }

    /** HTTP runtime 提供的认证请求与 Stop 意图的脱敏组合。 */
    record StopRequest(
            IdentityVerificationRequest verificationRequest,
            StopMeetingIntent intent) {
        public StopRequest {
            Objects.requireNonNull(verificationRequest, "verificationRequest");
            Objects.requireNonNull(intent, "intent");
        }
    }
}
