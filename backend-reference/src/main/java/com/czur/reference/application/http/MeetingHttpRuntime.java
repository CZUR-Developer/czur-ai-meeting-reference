package com.czur.reference.application.http;

import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.AuthenticatedRealtimeTokenRenewApplicationService;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.command.MeetingCommandContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 参考后端的 transport-neutral HTTP 运行时装配边界。
 *
 * <p>本类只把已经由外层部署装配的查询端口、删除端口、认证 Stop 应用服务、
 * 可选显式开启的认证 Create 服务和请求解码器组合成一个
 * {@link MeetingHttpAdapter}，再提供一个明确的接收/关闭
 * 生命周期。它不启动 Servlet、Netty、JDK HttpServer 或 WebSocket，不创建
 * 数据库连接池，也不持有模型、存储、Worker 或 Gateway 逻辑；具体 HTTP server
 * 只需把解码后的 {@link HttpRequest} 交给 {@link #handle(HttpRequest,
 * MeetingCommandContext)}，并负责把 {@link HttpResponse} 写回网络。
 * </p>
 *
 * <p>数据库运行时、身份 Provider、请求解码器和 Worker 的关闭由它们各自的
 * 外层 owner 负责。本类的 {@link #close()} 只停止新的 HTTP 请求，保证关闭后
 * 不会再调用查询、删除或命令端口；已在 adapter 内执行的同步调用不被中途
 * 打断。这样可以在真实 server、测试 server 或未来框架适配器之间复用相同的
 * application 边界，而不会误称本参考切片已经提供了完整网络 server。</p>
 *
 * <p>既有构造器不开放 Create 或实时 Token 续期，继续返回 501；只有显式注入
 * 相应认证服务及解码器才开放路由。任何构造器均不接收
 * 旧的未认证 {@code MeetingCommandService}。</p>
 */
public final class MeetingHttpRuntime implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(MeetingHttpRuntime.class.getName());

    private final MeetingHttpAdapter adapter;
    private final HttpErrorMapper errors;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 由已装配端口创建参考 HTTP 运行时。
     *
     * @param queryPort 已绑定事实源的查询端口
     * @param deletionPort 已绑定事实源的删除受理端口
     * @param decoder 可信 HTTP 边界提供的请求解码器
     * @param errors 统一错误 envelope 映射器
     * @param stopService 已完成身份和权限校验的 Stop 应用服务；不得为 null
     */
    public MeetingHttpRuntime(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            MeetingHttpAdapter.RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService) {
        this(new MeetingHttpAdapter(
                Objects.requireNonNull(queryPort, "queryPort"),
                Objects.requireNonNull(deletionPort, "deletionPort"),
                Objects.requireNonNull(decoder, "decoder"),
                Objects.requireNonNull(errors, "errors"),
                Objects.requireNonNull(stopService, "stopService")), errors);
    }

    /**
     * 显式装配完整认证 Create；关闭开关时仍在解码或调用业务前返回 501。
     *
     * <p>此处只组装 transport-neutral 路由，不启动网络监听或数据库。真实服务端
     * 的部署开关及各依赖生命周期由外层装配负责；启用时缺少 Create 服务或
     * 解码器会在构造阶段失败，而不会处理请求后再回退旧路径。</p>
     */
    public MeetingHttpRuntime(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            MeetingHttpAdapter.RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled) {
        this(new MeetingHttpAdapter(
                queryPort, deletionPort, decoder, errors, stopService,
                createService, createDecoder, createEnabled), errors);
    }

    /** 在同一 adapter 内可选接入匿名 Manifest 端口；null 保留旧 501 行为。 */
    public MeetingHttpRuntime(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            MeetingHttpAdapter.RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort) {
        this(queryPort, deletionPort, decoder, errors, stopService, createService,
                createDecoder, createEnabled, manifestPort, null);
    }

    /** 显式装配认证续期服务；null 使该路由保持 501 关闭状态。 */
    public MeetingHttpRuntime(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            MeetingHttpAdapter.RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService) {
        this(queryPort, deletionPort, decoder, errors, stopService, createService,
                createDecoder, createEnabled, manifestPort, renewService, null);
    }

    /** 显式接入 Mock 最终转写正文；旧装配的 Artifact 路由仍为 501。 */
    public MeetingHttpRuntime(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            MeetingHttpAdapter.RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService,
            MeetingArtifactContentPort artifactPort) {
        this(queryPort, deletionPort, decoder, errors, stopService, createService,
                createDecoder, createEnabled, manifestPort, renewService, artifactPort, null);
    }

    /** 显式接入同一持久事件源的认证补拉路由；旧构造器仍保持 501。 */
    public MeetingHttpRuntime(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            MeetingHttpAdapter.RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService,
            MeetingArtifactContentPort artifactPort,
            MeetingEventLogPort eventLogPort) {
        this(new MeetingHttpAdapter(queryPort, deletionPort, decoder, errors, stopService,
                createService, createDecoder, createEnabled, manifestPort, renewService,
                artifactPort, eventLogPort), errors);
    }

    /** 使用默认错误映射器创建已认证 Stop 运行时。 */
    public MeetingHttpRuntime(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            MeetingHttpAdapter.RequestDecoder decoder,
            AuthenticatedMeetingStopApplicationService stopService) {
        this(queryPort, deletionPort, decoder, new HttpErrorMapper(), stopService);
    }

    /**
     * 注入已创建的 adapter；适用于外层已经统一装配错误 mapper 的 server 或测试。
     *
     * <p>调用方仍然必须把与 adapter 使用的同一个错误 mapper 传入，确保运行时
     * 关闭响应和 adapter 内的业务错误使用同一套 envelope 版本与诊断 ID 策略。</p>
     */
    public MeetingHttpRuntime(MeetingHttpAdapter adapter, HttpErrorMapper errors) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.errors = Objects.requireNonNull(errors, "errors");
        LOGGER.log(Level.INFO, "meeting HTTP 运行时装配完成");
    }

    /** 使用默认错误映射器注入 adapter。 */
    public MeetingHttpRuntime(MeetingHttpAdapter adapter) {
        this(Objects.requireNonNull(adapter, "adapter"), adapter.errorMapper());
    }

    /**
     * 把请求交给唯一 adapter；关闭后在进入任何业务端口前返回 503。
     *
     * @param request transport-neutral 请求值对象
     * @param context 外层认证后提供的查询/删除上下文；Stop 使用其自身凭据
     * @return adapter 响应，或运行时关闭时的统一 503 envelope
     */
    public HttpResponse handle(HttpRequest request, MeetingCommandContext context) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            LOGGER.log(Level.WARNING, "meeting HTTP 运行时拒绝请求 reason=RUNTIME_CLOSED");
            return errors.runtimeUnavailable();
        }
        return adapter.handle(request, context);
    }

    /** 返回当前运行时是否仍接受新请求。 */
    public boolean isLive() {
        return !closed.get();
    }

    /**
     * 幂等关闭请求入口。
     *
     * <p>本方法不关闭 DataSource、身份 Provider 或 Worker；这些对象由外层
     * 生命周期 owner 管理。关闭只改变接收状态，重复调用不会产生异常或重复
     * 资源释放。</p>
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            LOGGER.log(Level.INFO, "meeting HTTP 运行时已关闭");
        }
    }
}
