package com.czur.reference.application.http;

import java.util.Objects;

/**
 * 已解码会议请求的公共分发入口，由不同HTTP transport共享同一实例。
 *
 * <p>本类提取原JDK入口的业务分发顺序：可选结果邮件先使用既有客户端写入
 * 身份解析器，其余路由按{@link MeetingHttpAdapter#authenticationRoute(HttpRequest)}
 * 选择已有读取、删除或客户端写入上下文；Create／Stop继续由应用服务自行认证。
 * 不增加路由、权限、身份回退或会议状态机，不解析原始网络字节。</p>
 *
 * <p>运行时、解析器和可选邮件端口由外层装配提供，本对象不拥有其生命周期，
 * 不创建监听器、线程或连接池。JDK内部健康路径留在原listener；Servlet等宿主
 * 须负责自己的字节校验、健康入口以及先停请求接收、后关闭装配根的顺序。</p>
 */
public final class MeetingHttpDispatcher {
    private final MeetingHttpRuntime runtime;
    private final HttpErrorMapper errors;
    private final AuthenticatedMeetingHttpContextResolver contextResolver;
    private final ResultEmailEndpoint resultEmail;
    private final MeetingArtifactHttpEndpoint artifactEndpoint;
    private final SpeakerIdentityEnrollmentEndpoint speakerEnrollment;

    /** 无外层身份解析器；保留旧JDK构造器的空上下文及邮件关闭行为。 */
    public MeetingHttpDispatcher(MeetingHttpRuntime runtime, HttpErrorMapper errors) {
        this(runtime, errors, null, null, null);
    }

    /** 接入已有可信上下文解析器，结果邮件仍关闭。 */
    public MeetingHttpDispatcher(MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver) {
        this(runtime, errors, contextResolver, null, null);
    }

    /**
     * 由同一装配根提供业务依赖；解析器须使用与运行时相同的错误映射器。
     *
     * @param runtime 唯一会议请求运行时；调用方负责关闭
     * @param errors 运行时及transport共用的错误映射器
     * @param contextResolver 可选可信身份解析器；null不开放未经认证的业务读取
     * @param resultEmail 可选原结果邮件端口；null保持原501关闭行为
     */
    public MeetingHttpDispatcher(MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            ResultEmailEndpoint resultEmail) {
        this(runtime, errors, contextResolver, resultEmail, null);
    }

    /**
     * 共享 JDK／其它 transport 的媒体接线；不改变既有 JSON 路由和身份解析。
     */
    public MeetingHttpDispatcher(MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            ResultEmailEndpoint resultEmail, MeetingArtifactHttpEndpoint artifactEndpoint) {
        this(runtime, errors, contextResolver, resultEmail, artifactEndpoint, null);
    }

    /** 显式接入可选会后人物注册；旧装配不开放该写入口。 */
    public MeetingHttpDispatcher(MeetingHttpRuntime runtime, HttpErrorMapper errors,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            ResultEmailEndpoint resultEmail, MeetingArtifactHttpEndpoint artifactEndpoint,
            SpeakerIdentityEnrollmentEndpoint speakerEnrollment) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.errors = Objects.requireNonNull(errors, "errors");
        if (contextResolver != null && contextResolver.errorMapper() != errors) {
            throw new IllegalArgumentException("HTTP error mapper must be shared");
        }
        this.contextResolver = contextResolver;
        this.resultEmail = resultEmail;
        this.artifactEndpoint = artifactEndpoint;
        this.speakerEnrollment = speakerEnrollment;
    }

    /**
     * 按原JDK业务顺序分发已约束的请求；未知路由不调用身份解析器。
     * 返回原端口响应，不将邮件或HTTP成功解释成会议已READY。
     */
    public HttpResponse handle(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        if (SpeakerIdentityEnrollmentEndpoint.matches(request)) {
            if (speakerEnrollment == null) return errors.notImplemented();
            if (contextResolver == null) return errors.missingContext();
            var resolution = contextResolver.forClientWrite(request);
            if (resolution instanceof AuthenticatedMeetingHttpContextResolver.Rejected rejected) {
                return rejected.response();
            }
            return speakerEnrollment.handle(request,
                    ((AuthenticatedMeetingHttpContextResolver.Ready) resolution).context());
        }
        if (ResultEmailEndpoint.matches(request)) {
            if (resultEmail == null) return errors.notImplemented();
            if (contextResolver == null) return errors.missingContext();
            var resolution = contextResolver.forClientWrite(request);
            if (resolution instanceof AuthenticatedMeetingHttpContextResolver.Rejected rejected) {
                return rejected.response();
            }
            return resultEmail.handle(request,
                    ((AuthenticatedMeetingHttpContextResolver.Ready) resolution).context());
        }
        if (contextResolver == null) {
            return runtime.handle(request, null);
        }
        MeetingHttpAdapter.AuthenticationRoute route =
                MeetingHttpAdapter.authenticationRoute(request);
        if (route == MeetingHttpAdapter.AuthenticationRoute.NONE) {
            return runtime.handle(request, null);
        }
        AuthenticatedMeetingHttpContextResolver.Resolution resolution = switch (route) {
            case READ -> contextResolver.forRead(request);
            case DELETE -> contextResolver.forDelete(request);
            case CLIENT_WRITE -> contextResolver.forClientWrite(request);
            case NONE -> throw new IllegalStateException("unreachable authentication route");
        };
        if (resolution instanceof AuthenticatedMeetingHttpContextResolver.Rejected rejected) {
            return rejected.response();
        }
        if (resolution instanceof AuthenticatedMeetingHttpContextResolver.Ready ready) {
            return runtime.handle(request, ready.context());
        }
        throw new IllegalStateException("unknown authentication resolution");
    }

    /** 原始录音上传的网络入口；正文只在公共输入端口中消费一次。 */
    public HttpResponse handle(BinaryHttpRequest request) {
        Objects.requireNonNull(request, "request");
        try (request) {
            if (artifactEndpoint == null || !artifactEndpoint.matchesUpload(request)) {
                return errors.notImplemented();
            }
            if (contextResolver == null) {
                return errors.missingContext();
            }
            var resolution = contextResolver.forClientWrite(request.metadata());
            if (resolution instanceof AuthenticatedMeetingHttpContextResolver.Rejected rejected) {
                return rejected.response();
            }
            return artifactEndpoint.upload(request,
                    ((AuthenticatedMeetingHttpContextResolver.Ready) resolution).context());
        } catch (java.io.IOException transportFailure) {
            return errors.exception(transportFailure);
        } catch (RuntimeException unexpected) {
            return errors.exception(unexpected);
        }
    }

    /** JDK transport 在 JSON 适配器前判断是否应直接回放二进制媒体。 */
    boolean handlesArtifactMedia(HttpRequest request) {
        return artifactEndpoint != null && artifactEndpoint.matchesDownload(request);
    }

    /** 打开已经由公共媒体端口核对的结果流。 */
    com.czur.reference.application.port.MeetingArtifactMediaPort.Result openArtifactMedia(
            HttpRequest request) {
        if (artifactEndpoint == null) {
            return new com.czur.reference.application.port.MeetingArtifactMediaPort.Unavailable(false);
        }
        return artifactEndpoint.openDownload(request);
    }

    /** 供 JDK transport 映射媒体错误，不暴露 dispatcher 的 mapper 所有权。 */
    HttpErrorMapper errorMapper() { return errors; }

    /**
     * 参考 Web 的两个跨源写入口；只读取装配状态，不解析 Bearer 或触发业务。
     * 伙伴自己的登录路径、建会、Stop、邮件等路由不随此接线开放跨源写入。
     */
    boolean supportsReferenceBrowserPost(String path) {
        return "/v1/reference/session:login".equals(path) && runtime.hasSessionLoginPath(path)
                || speakerEnrollment != null && SpeakerIdentityEnrollmentEndpoint.matchesPath(path);
    }

}
