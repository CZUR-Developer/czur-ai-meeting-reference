package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
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
 * 提供已经验证的 {@link MeetingCommandContext}。create/stop 在本 slice 中明确
 * 关闭，返回 501 且不调用任何旧的未认证命令服务；正式部署必须另行装配完成
 * 身份、权限、权益和完整 OpenAPI response 的 command adapter。</p>
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

    private final MeetingQueryPort queryPort;
    private final MeetingDeletionPort deletionPort;
    private final RequestDecoder decoder;
    private final HttpErrorMapper errors;

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
            // 完整 create command/application service 尚未安全装配，必须拒绝而不是
            // 回退到旧的未认证 MeetingCommandService。
            return errors.notImplemented();
        }

        Matcher stop = STOP_PATH.matcher(path);
        if ("POST".equals(method) && stop.matches()) {
            // stop 与 create 同样等待拥有者/输入接管专用的正式命令 adapter。
            return errors.notImplemented();
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
     * 其部署或主体。create/stop 解码接口故意未暴露，避免误接入不完整的旧命令。
     * </p>
     */
    public interface RequestDecoder {
        /** 依据已验证 context 解码删除输入，不得接受客户端覆盖的归属字段。 */
        DeleteRequest decodeDelete(HttpRequest request, MeetingCommandContext context);
    }
}
