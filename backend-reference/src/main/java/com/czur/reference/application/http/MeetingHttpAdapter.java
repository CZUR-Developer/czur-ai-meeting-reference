package com.czur.reference.application.http;

import com.czur.reference.application.command.CreateMeetingCommand;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingCommandService;
import com.czur.reference.application.command.StopMeetingCommand;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;

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
 * <p>本参考切片明确不在 adapter 中解析或验证 Bearer，不实现权限、计费、权益
 * 预占、能力规划、Manifest、事件、Artifact 或 Worker。GET/DELETE 要求调用方
 * 提供已经验证的 {@link MeetingCommandContext}；create/stop 目前只能调用旧的
 * {@link MeetingCommandService} 未认证参考入口，因此不能宣称已经满足 OpenAPI
 * 的生产鉴权要求。正式部署应在本类外完成身份 Provider、权限检查和相应拥有者
 * application service 装配。</p>
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

    private final MeetingCommandService commandService;
    private final MeetingQueryPort queryPort;
    private final MeetingDeletionPort deletionPort;
    private final RequestDecoder decoder;
    private final HttpErrorMapper errors;

    /** 使用随机 opaque diagnosticId 的默认错误映射器。 */
    public MeetingHttpAdapter(
            MeetingCommandService commandService,
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder) {
        this(commandService, queryPort, deletionPort, decoder, new HttpErrorMapper());
    }

    /** 测试或运行时可以注入诊断 ID 生成器已经固定的错误映射器。 */
    public MeetingHttpAdapter(
            MeetingCommandService commandService,
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors) {
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.deletionPort = Objects.requireNonNull(deletionPort, "deletionPort");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /**
     * 处理一个请求并返回 transport-neutral 响应。
     *
     * @param request 已由 HTTP runtime 约束过的请求值对象
     * @param context 外层身份 Provider 验证出的上下文；create/stop 参考入口不使用，
     *                其余路由缺失时固定返回 401
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
            // 这是当前命令服务的 transport 参考入口；Bearer/权限不在此处验证。
            String idempotencyKey = requireIdempotencyKey(request);
            requireCreateBody(request);
            validateOptionalRequestId(request);
            CreateMeetingCommand command = decoder.decodeCreate(request);
            requireDecodedIdempotencyKey(idempotencyKey, command.idempotencyKey());
            return errors.command(commandService.create(command));
        }

        Matcher stop = STOP_PATH.matcher(path);
        if ("POST".equals(method) && stop.matches()) {
            String idempotencyKey = requireIdempotencyKey(request);
            validateOptionalRequestId(request);
            StopMeetingCommand command = decoder.decodeStop(request);
            requireDecodedIdempotencyKey(idempotencyKey, command.idempotencyKey());
            requirePathMeetingId(stop.group(1), command.meetingId());
            return errors.command(commandService.stop(command));
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

    /** 创建请求在 OpenAPI 中必须携带 JSON body；字段/schema 校验由 decoder 执行。 */
    private static void requireCreateBody(HttpRequest request) {
        if (request.body().isBlank()) {
            throw new IllegalArgumentException("create body is required");
        }
    }

    private static void validateOptionalRequestId(HttpRequest request) {
        String requestId = request.header("x-request-id");
        if (requestId != null && requestId.length() > 128) {
            throw new IllegalArgumentException("x-request-id is too long");
        }
    }

    private static void requireDecodedIdempotencyKey(String header, String decoded) {
        if (decoded == null || !header.equals(decoded)) {
            throw new IllegalArgumentException("decoded idempotency-key does not match header");
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
     * 由具体 HTTP runtime 或 JSON codec 提供的命令解码边界。
     *
     * <p>实现可以读取请求头中的幂等键、请求体和服务端时间，但必须自己执行
     * OpenAPI body/schema 校验并生成规范摘要；adapter 不把原始 JSON、Bearer
     * 或供应商字段复制进领域端口。delete 使用 adapter 已检查过的认证 context
     * 生成 {@link DeleteRequest}，不得从客户端 body 覆盖其部署或主体。</p>
     */
    public interface RequestDecoder {
        /** 解码并校验 create JSON；返回的幂等键必须与请求头完全一致。 */
        CreateMeetingCommand decodeCreate(HttpRequest request);

        /** 解码可选 stop JSON；路径和幂等键一致性由 adapter 再次确认。 */
        StopMeetingCommand decodeStop(HttpRequest request);

        /** 依据已验证 context 解码删除输入，不得接受客户端覆盖的归属字段。 */
        DeleteRequest decodeDelete(HttpRequest request, MeetingCommandContext context);
    }
}
