package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.AuthenticatedRealtimeTokenRenewApplicationService;
import com.czur.reference.application.command.StopMeetingIntent;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.model.BailianSummarySessionIssuer;
import com.czur.reference.application.model.ModelSessionLanguageLookup;
import com.czur.reference.application.model.TingwuModelSessionIssuer;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import com.czur.reference.application.port.model.SpeakerReference;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Base64;
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
 * 认证应用服务后才开放。实时 Token 续期仅显式注入认证服务时开放，默认先返回
 * 501；这些路由均不回退旧的未认证命令服务。</p>
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
    private static final Pattern CLIENT_START_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+):startClient$");
    private static final Pattern MODEL_SESSION_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+):modelSession$");
    private static final Pattern SUMMARY_SESSION_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+):summarySession$");
    private static final Pattern CLIENT_APPEND_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/events:append$");
    private static final Pattern PRESENTATION_APPEND_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/presentation-captures:append$");
    private static final Pattern SUMMARY_SUBMIT_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/summary:submit$");
    private static final Pattern RENEW_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+):renewRealtimeToken$");
    private static final Pattern MANIFEST_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/manifest$");
    private static final Pattern EVENTS_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/events$");
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "^/v1/artifacts/(art_[A-Za-z0-9_-]+)$");
    private static final Pattern GEN_TIME = Pattern.compile("(?:-1|0|[1-9][0-9]*)");
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
    private final MeetingManifestPort manifestPort;
    private final MeetingArtifactContentPort artifactPort;
    private final AuthenticatedRealtimeTokenRenewApplicationService renewService;
    private final MeetingEventLogPort eventLogPort;
    private final ClientMeetingInputPort clientInputPort;
    private final ModelSessionIssuer modelSessions;
    private final ModelSessionLanguageLookup modelSessionLanguages;
    private final BailianSummarySessionIssuer summarySessions;

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
        this.manifestPort = null;
        this.artifactPort = null;
        this.renewService = null;
        this.eventLogPort = null;
        this.clientInputPort = null;
        this.modelSessions = null;
        this.modelSessionLanguages = null;
        this.summarySessions = null;
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
        this.manifestPort = null;
        this.artifactPort = null;
        this.renewService = null;
        this.eventLogPort = null;
        this.clientInputPort = null;
        this.modelSessions = null;
        this.modelSessionLanguages = null;
        this.summarySessions = null;
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
        this(queryPort, deletionPort, decoder, errors, stopService,
                createService, createDecoder, createEnabled, null);
    }

    /** 显式接入匿名结果查询；null 端口使新路由在解析输入前返回 501。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort) {
        this(queryPort, deletionPort, decoder, errors, stopService,
                createService, createDecoder, createEnabled, manifestPort, null);
    }

    /**
     * 仅显式注入认证续期服务时开放公开续期路由。null 保持 501 关态，
     * 且在校验 Bearer、body、幂等键和访问 V014 前即返回。
     */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
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

    /** 显式接入匿名 Artifact 正文端口；null 保持旧 501 关态。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
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

    /** 显式接入认证事件补拉；旧装配保持 501，事件仍只从既有持久端口读取。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService,
            MeetingArtifactContentPort artifactPort,
            MeetingEventLogPort eventLogPort) {
        this(queryPort, deletionPort, decoder, errors, stopService, createService,
                createDecoder, createEnabled, manifestPort, renewService, artifactPort,
                eventLogPort, null);
    }

    /** 显式接入 App 直连模型后的标准事件输入；旧构造器不开放新路由。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService,
            MeetingArtifactContentPort artifactPort,
            MeetingEventLogPort eventLogPort,
            ClientMeetingInputPort clientInputPort) {
        this(queryPort, deletionPort, decoder, errors, stopService, createService,
                createDecoder, createEnabled, manifestPort, renewService, artifactPort,
                eventLogPort, clientInputPort, null);
    }

    /** P5 模型会话独立装配，不复用旧 WSS Token 或首次 Create 响应事实。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService,
            MeetingArtifactContentPort artifactPort,
            MeetingEventLogPort eventLogPort,
            ClientMeetingInputPort clientInputPort,
            ModelSessionIssuer modelSessions) {
        this(queryPort, deletionPort, decoder, errors, stopService, createService,
                createDecoder, createEnabled, manifestPort, renewService, artifactPort,
                eventLogPort, clientInputPort, modelSessions, null);
    }

    /** 本场模型会话从首次 Create 事实取源语言，不读取会话请求 body。 */
    public MeetingHttpAdapter(
            MeetingQueryPort queryPort,
            MeetingDeletionPort deletionPort,
            RequestDecoder decoder,
            HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder,
            boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService,
            MeetingArtifactContentPort artifactPort,
            MeetingEventLogPort eventLogPort,
            ClientMeetingInputPort clientInputPort,
            ModelSessionIssuer modelSessions,
            ModelSessionLanguageLookup modelSessionLanguages) {
        this(queryPort, deletionPort, decoder, errors, stopService, createService,
                createDecoder, createEnabled, manifestPort, renewService, artifactPort,
                eventLogPort, clientInputPort, modelSessions, modelSessionLanguages, null);
    }

    public MeetingHttpAdapter(
            MeetingQueryPort queryPort, MeetingDeletionPort deletionPort,
            RequestDecoder decoder, HttpErrorMapper errors,
            AuthenticatedMeetingStopApplicationService stopService,
            AuthenticatedCompleteMeetingApplicationService createService,
            CreateMeetingHttpRequestDecoder createDecoder, boolean createEnabled,
            MeetingManifestPort manifestPort,
            AuthenticatedRealtimeTokenRenewApplicationService renewService,
            MeetingArtifactContentPort artifactPort, MeetingEventLogPort eventLogPort,
            ClientMeetingInputPort clientInputPort, ModelSessionIssuer modelSessions,
            ModelSessionLanguageLookup modelSessionLanguages,
            BailianSummarySessionIssuer summarySessions) {
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
        this.manifestPort = manifestPort;
        this.artifactPort = artifactPort;
        this.renewService = renewService;
        this.eventLogPort = eventLogPort;
        this.clientInputPort = clientInputPort;
        this.modelSessions = modelSessions;
        this.modelSessionLanguages = modelSessionLanguages;
        this.summarySessions = summarySessions;
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
     * 供网络入口复用当前 REST 路由的身份要求，避免另写一套路由正则。
     *
     * <p>这里只判断方法和路径，不提前校验 query、body 或幂等头；这些仍由
     * adapter 的原有分支处理。未知路由、Create 和自带认证流程的 Stop 均不由
     * 外层查询/删除解析器验证。</p>
     */
    static AuthenticationRoute authenticationRoute(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        if ("GET".equals(request.method()) && MEETINGS_PATH.equals(request.path())) {
            return AuthenticationRoute.READ;
        }
        if (DETAIL_PATH.matcher(request.path()).matches()) {
            return switch (request.method()) {
                case "GET" -> AuthenticationRoute.READ;
                case "DELETE" -> AuthenticationRoute.DELETE;
                default -> AuthenticationRoute.NONE;
            };
        }
        if ("GET".equals(request.method())
                && EVENTS_PATH.matcher(request.path()).matches()) {
            return AuthenticationRoute.READ;
        }
        if ("POST".equals(request.method())
                && (CLIENT_START_PATH.matcher(request.path()).matches()
                || CLIENT_APPEND_PATH.matcher(request.path()).matches()
                || PRESENTATION_APPEND_PATH.matcher(request.path()).matches()
                || SUMMARY_SUBMIT_PATH.matcher(request.path()).matches())) {
            return AuthenticationRoute.CLIENT_WRITE;
        }
        if ("POST".equals(request.method())
                && (MODEL_SESSION_PATH.matcher(request.path()).matches()
                || SUMMARY_SESSION_PATH.matcher(request.path()).matches())) {
            return AuthenticationRoute.CLIENT_WRITE;
        }
        return AuthenticationRoute.NONE;
    }

    enum AuthenticationRoute {
        READ, DELETE, CLIENT_WRITE, NONE
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

        Matcher artifact = ARTIFACT_PATH.matcher(path);
        if ("GET".equals(method) && artifact.matches()) {
            if (artifactPort == null) {
                return errors.notImplemented();
            }
            if (!request.body().isEmpty() || request.query().size() != 1) {
                throw new IllegalArgumentException("artifact request is invalid");
            }
            return errors.artifact(artifactPort.get(new MeetingArtifactContentPort.Request(
                    artifact.group(1), parseGenTime(request.query().get("genTime")))));
        }

        Matcher manifest = MANIFEST_PATH.matcher(path);
        if ("GET".equals(method) && manifest.matches()) {
            if (manifestPort == null) {
                return errors.notImplemented();
            }
            if (!request.body().isEmpty() || request.query().size() != 1) {
                throw new IllegalArgumentException("manifest request is invalid");
            }
            return errors.manifest(manifestPort.get(
                    new MeetingManifestPort.Request(
                            manifest.group(1), parseGenTime(request.query().get("genTime")))));
        }

        Matcher events = EVENTS_PATH.matcher(path);
        if ("GET".equals(method) && events.matches()) {
            if (eventLogPort == null) {
                return errors.notImplemented();
            }
            if (context == null) {
                return errors.missingContext();
            }
            // 读取身份由网络入口验证；端口在同一持久快照下再次校验会议归属、
            // 删除撤权与历史事实，HTTP 不复制授权或事件存储逻辑。
            if (!request.body().isEmpty() || request.query().size() > 2
                    || request.query().keySet().stream().anyMatch(
                            key -> !"afterSequence".equals(key) && !"limit".equals(key))) {
                throw new IllegalArgumentException("events request is invalid");
            }
            MeetingEventLogPort.Request eventRequest = new MeetingEventLogPort.Request(
                    context, events.group(1), parseAfterSequence(request.query().get("afterSequence")),
                    parseEventLimit(request.query().get("limit")));
            try {
                return errors.events(eventLogPort.read(eventRequest));
            } catch (RuntimeException unexpected) {
                // 参数已校验；此后异常来自持久端口或出站投影，不再解释为请求 400。
                return errors.internalFailure();
            }
        }

        Matcher clientStart = CLIENT_START_PATH.matcher(path);
        Matcher modelSession = MODEL_SESSION_PATH.matcher(path);
        Matcher summarySession = SUMMARY_SESSION_PATH.matcher(path);
        if ("POST".equals(method) && modelSession.matches()) {
            if (modelSessions == null || modelSessionLanguages == null) return errors.notImplemented();
            if (context == null) return errors.missingContext();
            if (!request.query().isEmpty() || !request.body().isEmpty()) {
                throw new IllegalArgumentException("model session must have no body or query");
            }
            MeetingQueryPort.Result meeting = queryPort.get(new MeetingQueryPort.GetRequest(
                    context, modelSession.group(1)));
            if (!(meeting instanceof MeetingQueryPort.Found found)) {
                return errors.query(meeting);
            }
            if (found.meeting().state() != com.czur.reference.domain.meeting.MeetingState.CREATED
                    && found.meeting().state() != com.czur.reference.domain.meeting.MeetingState.RUNNING) {
                return errors.modelSessionInvalidState();
            }
            try {
                var sessionRequest = modelSessionLanguages.load(context, modelSession.group(1));
                return errors.modelSession(modelSession.group(1),
                        modelSessions.issue(sessionRequest));
            } catch (TingwuModelSessionIssuer.SessionIssueException | IllegalStateException unavailable) {
                return errors.modelSessionUnavailable();
            }
        }
        if ("POST".equals(method) && summarySession.matches()) {
            if (summarySessions == null || modelSessionLanguages == null) {
                return errors.notImplemented();
            }
            if (context == null) return errors.missingContext();
            if (!request.query().isEmpty() || !request.body().isEmpty()) {
                throw new IllegalArgumentException("summary session must have no body or query");
            }
            String meetingId = summarySession.group(1);
            MeetingQueryPort.Result meeting = queryPort.get(new MeetingQueryPort.GetRequest(
                    context, meetingId));
            if (!(meeting instanceof MeetingQueryPort.Found found)) {
                return errors.query(meeting);
            }
            if (found.meeting().state() != com.czur.reference.domain.meeting.MeetingState.STARTING
                    && found.meeting().state()
                    != com.czur.reference.domain.meeting.MeetingState.RUNNING) {
                return errors.summarySessionInvalidState();
            }
            try {
                var eligibility = modelSessionLanguages.summaryEligibility(context, meetingId);
                if (!eligibility.clientStarted()) {
                    return errors.summarySessionInvalidState();
                }
                if (!eligibility.granted()) {
                    return errors.summaryNotGranted();
                }
                return errors.summarySession(meetingId, summarySessions.issue());
            } catch (BailianSummarySessionIssuer.SessionIssueException
                    | IllegalStateException unavailable) {
                return errors.summarySessionUnavailable();
            }
        }
        if ("POST".equals(method) && clientStart.matches()) {
            if (clientInputPort == null) return errors.notImplemented();
            if (context == null) return errors.missingContext();
            if (!request.query().isEmpty() || !request.body().isEmpty()) {
                throw new IllegalArgumentException("client start must have no body or query");
            }
            return errors.clientStart(clientStart.group(1),
                    clientInputPort.start(context, clientStart.group(1)));
        }

        Matcher clientAppend = CLIENT_APPEND_PATH.matcher(path);
        if ("POST".equals(method) && clientAppend.matches()) {
            if (clientInputPort == null) return errors.notImplemented();
            if (context == null) return errors.missingContext();
            if (!request.query().isEmpty()) {
                throw new IllegalArgumentException("client event query is invalid");
            }
            ClientMeetingInputPort.ClientEvent event = decodeClientEvent(request);
            return errors.clientAppend(clientInputPort.append(
                    context, clientAppend.group(1), event));
        }

        Matcher presentationAppend = PRESENTATION_APPEND_PATH.matcher(path);
        if ("POST".equals(method) && presentationAppend.matches()) {
            if (clientInputPort == null) return errors.notImplemented();
            if (context == null) return errors.missingContext();
            if (!request.query().isEmpty()) {
                throw new IllegalArgumentException("presentation query is invalid");
            }
            return errors.presentationAppend(clientInputPort.appendPresentation(context,
                    presentationAppend.group(1), decodePresentationChunk(request)));
        }

        Matcher summarySubmit = SUMMARY_SUBMIT_PATH.matcher(path);
        if ("POST".equals(method) && summarySubmit.matches()) {
            if (clientInputPort == null) return errors.notImplemented();
            if (context == null) return errors.missingContext();
            if (!request.query().isEmpty()) {
                throw new IllegalArgumentException("summary query is invalid");
            }
            return errors.summarySubmit(summarySubmit.group(1),
                    clientInputPort.submitSummary(context, summarySubmit.group(1),
                            decodeSummary(request)));
        }

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

        Matcher renew = RENEW_PATH.matcher(path);
        if ("POST".equals(method) && renew.matches()) {
            if (renewService == null) {
                return errors.notImplemented();
            }
            if (!request.query().isEmpty() || !request.body().isEmpty()) {
                throw new IllegalArgumentException("renew request must have no body or query");
            }
            String meetingId = renew.group(1);
            MeetingIdPolicy.requireValid(meetingId);
            String idempotencyKey = requireIdempotencyKey(request);
            var verification = decoder.decodeRenewRealtimeToken(
                    request, meetingId, idempotencyKey);
            try {
                return errors.renew(renewService.renew(
                        Objects.requireNonNull(verification, "renew verification"),
                        meetingId, idempotencyKey));
            } catch (RuntimeException unexpected) {
                // 身份请求已生成后，服务或成功投影的异常不是客户端输入错误。
                // 服务正常会关闭凭据；在尚未接手等异常窗口再次关闭也安全。
                if (verification != null) verification.credential().close();
                return errors.internalFailure();
            }
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

    /** 只接受公开协议的无符号十进制游标，拒绝溢出及非规范表示。 */
    private static long parseAfterSequence(String raw) {
        if (raw == null) return 0;
        if (!raw.matches("(?:0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("afterSequence is invalid");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("afterSequence is invalid");
        }
    }

    /** OpenAPI 默认 200、允许 1～1000；分页上限属于公开输入契约。 */
    private static int parseEventLimit(String raw) {
        if (raw == null) return 200;
        if (!raw.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("limit is invalid");
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= 1 && value <= 1000) return value;
        } catch (NumberFormatException ignored) {
            // 超出 int 范围与超出公开上限均返回同一个稳定输入错误。
        }
        throw new IllegalArgumentException("limit is invalid");
    }

    private static void requirePathMeetingId(String pathMeetingId, String commandMeetingId) {
        if (commandMeetingId == null || !pathMeetingId.equals(commandMeetingId)) {
            throw new IllegalArgumentException("path meetingId does not match command");
        }
    }

    /** 校验 OpenAPI 要求的幂等头；具体 scope/digest 仍由 decoder/端口负责。 */
    private static String requireIdempotencyKey(HttpRequest request) {
        String value = request.header("idempotency-key");
        if (value == null || value.isBlank() || value.length() < 16 || value.length() > 128
                || value.codePoints().anyMatch(Character::isISOControl)) {
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

    /**
     * 仅接收一条标准字幕或译文；模型原始响应、服务端 Envelope 字段及混合 body
     * 均不在客户端请求中。译文的本场能力许可由持久输入端口核验。
     */
    private static ClientMeetingInputPort.ClientEvent decodeClientEvent(
            HttpRequest request) {
        String contentType = request.header("content-type");
        if (contentType == null || !contentType.matches(
                "(?i)application/json(?:[ \\t]*;[ \\t]*charset=utf-8)?")) {
            throw new IllegalArgumentException("client event content-type is invalid");
        }
        try {
            JsonNode root = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(request.body());
            if (root != null && root.has("translationUpsert")) {
                requireFields(root, Set.of("clientEventId", "clientSequence", "translationUpsert"));
                JsonNode item = root.get("translationUpsert");
                requireFields(item, Set.of("translationId", "sourceSegmentId", "revision",
                        "operation", "sourceLanguage", "targetLanguage", "text", "final",
                        "supersedesTranslationIds"));
                JsonNode finalNode = item.get("final");
                JsonNode supersedes = item.get("supersedesTranslationIds");
                if (!finalNode.isBoolean() || !supersedes.isArray()) {
                    throw new IllegalArgumentException("client translation fields are invalid");
                }
                List<String> ids = new ArrayList<>();
                for (JsonNode id : supersedes) {
                    if (!id.isTextual() || id.asText().isBlank()) {
                        throw new IllegalArgumentException("client translation fields are invalid");
                    }
                    ids.add(id.asText());
                }
                var translation = new CanonicalTranslationEvent(
                        textField(item, "translationId"),
                        textField(item, "sourceSegmentId"), intField(item, "revision"),
                        upsertOperation(item), textField(item, "sourceLanguage"),
                        textField(item, "targetLanguage"), textField(item, "text"),
                        finalNode.booleanValue(), ids);
                return new ClientMeetingInputPort.ClientTranslation(
                        textField(root, "clientEventId"), longField(root, "clientSequence"),
                        translation);
            }
            requireFields(root, Set.of("clientEventId", "clientSequence", "transcriptUpsert"));
            JsonNode body = root.get("transcriptUpsert");
            Set<String> transcriptFields = body != null && body.has("speaker")
                    ? Set.of("segmentId", "revision", "operation", "startMs",
                            "endMs", "language", "text", "final", "speaker")
                    : Set.of("segmentId", "revision", "operation", "startMs",
                            "endMs", "language", "text", "final");
            requireFields(body, transcriptFields);
            JsonNode finalNode = body.get("final");
            if (!finalNode.isBoolean()) {
                throw new IllegalArgumentException("client event final is invalid");
            }
            SpeakerReference speaker = null;
            if (body.has("speaker")) {
                JsonNode value = body.get("speaker");
                requireFields(value, Set.of("sessionSpeakerId"));
                speaker = new SpeakerReference(textField(value, "sessionSpeakerId"), null, null);
            }
            CanonicalTranscriptEvent transcript = new CanonicalTranscriptEvent(
                    textField(body, "segmentId"), intField(body, "revision"), upsertOperation(body),
                    longField(body, "startMs"), longField(body, "endMs"),
                    textField(body, "language"), textField(body, "text"),
                    finalNode.booleanValue(), speaker, 1.0, java.util.List.of(),
                    java.util.List.of());
            return new ClientMeetingInputPort.ClientTranscript(
                    textField(root, "clientEventId"), longField(root, "clientSequence"),
                    transcript);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("client event JSON is invalid");
        }
    }

    private static ClientMeetingInputPort.PresentationChunk decodePresentationChunk(
            HttpRequest request) {
        String contentType = request.header("content-type");
        if (contentType == null || !contentType.matches(
                "(?i)application/json(?:[ \\t]*;[ \\t]*charset=utf-8)?")) {
            throw new IllegalArgumentException("presentation content-type is invalid");
        }
        try {
            JsonNode root = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(request.body());
            requireFields(root, Set.of("chunkSequence", "frames"));
            int sequence = intField(root, "chunkSequence");
            JsonNode frames = root.get("frames");
            if (!frames.isArray() || frames.isEmpty()) {
                throw new IllegalArgumentException("presentation frames are invalid");
            }
            var canonical = JSON.createObjectNode();
            canonical.put("schemaVersion", "presentation-capture/v1");
            canonical.put("chunkSequence", sequence);
            var outputFrames = canonical.putArray("frames");
            long previous = -1;
            for (JsonNode frame : frames) {
                requireFields(frame, Set.of("offsetMs", "jpegBase64"));
                long offset = longField(frame, "offsetMs");
                if (offset <= previous) {
                    throw new IllegalArgumentException("presentation frame time is invalid");
                }
                previous = offset;
                String encoded = textField(frame, "jpegBase64");
                byte[] jpeg = Base64.getDecoder().decode(encoded);
                if (jpeg.length < 4 || (jpeg[0] & 255) != 0xff || (jpeg[1] & 255) != 0xd8
                        || (jpeg[jpeg.length - 2] & 255) != 0xff
                        || (jpeg[jpeg.length - 1] & 255) != 0xd9) {
                    throw new IllegalArgumentException("presentation JPEG is invalid");
                }
                var item = outputFrames.addObject();
                item.put("offsetMs", offset);
                item.put("jpegBase64", Base64.getEncoder().encodeToString(jpeg));
            }
            byte[] content = JSON.writeValueAsBytes(canonical);
            if (content.length > HttpRequest.MAX_BODY_BYTES) {
                throw new IllegalArgumentException("presentation body is too large");
            }
            return new ClientMeetingInputPort.PresentationChunk(
                    sequence, frames.size(), content);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("presentation JSON is invalid");
        }
    }

    private static CanonicalTranscriptEvent.Operation upsertOperation(JsonNode body) {
        return switch (textField(body, "operation")) {
            case "UPSERT_OPERATION_UPSERT" -> CanonicalTranscriptEvent.Operation.UPSERT;
            case "UPSERT_OPERATION_REPLACE" -> CanonicalTranscriptEvent.Operation.REPLACE;
            default -> throw new IllegalArgumentException("client event operation is invalid");
        };
    }

    private static ClientMeetingInputPort.StructuredSummary decodeSummary(HttpRequest request) {
        String contentType = request.header("content-type");
        if (contentType == null || !contentType.matches(
                "(?i)application/json(?:[ \\t]*;[ \\t]*charset=utf-8)?")) {
            throw new IllegalArgumentException("summary content-type is invalid");
        }
        try {
            JsonNode root = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(request.body());
            requireFields(root, Set.of("language", "title", "overview", "keyPoints",
                    "actionItems"));
            return new ClientMeetingInputPort.StructuredSummary(
                    textField(root, "language"), textField(root, "title"),
                    textField(root, "overview"), summaryItems(root.get("keyPoints")),
                    summaryItems(root.get("actionItems")));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("summary JSON is invalid");
        }
    }

    private static List<String> summaryItems(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("summary items are invalid");
        }
        List<String> items = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("summary items are invalid");
            }
            items.add(item.textValue());
        });
        return items;
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) {
            throw new IllegalArgumentException("client event fields are invalid");
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!expected.contains(fields.next())) {
                throw new IllegalArgumentException("client event fields are invalid");
            }
        }
    }

    private static String textField(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("client event text field is invalid");
        }
        return value.textValue();
    }

    private static long longField(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("client event number field is invalid");
        }
        return value.longValue();
    }

    private static int intField(JsonNode node, String name) {
        long value = longField(node, name);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("client event revision is invalid");
        }
        return (int) value;
    }

    private static String operationOf(HttpRequest request) {
        if ("GET".equals(request.method())
                && ARTIFACT_PATH.matcher(request.path()).matches()) {
            return "artifact";
        }
        if ("GET".equals(request.method())
                && MANIFEST_PATH.matcher(request.path()).matches()) {
            return "manifest";
        }
        if ("GET".equals(request.method())
                && EVENTS_PATH.matcher(request.path()).matches()) {
            return "events";
        }
        if ("POST".equals(request.method())
                && MODEL_SESSION_PATH.matcher(request.path()).matches()) return "modelSession";
        if ("POST".equals(request.method())
                && SUMMARY_SESSION_PATH.matcher(request.path()).matches()) return "summarySession";
        if ("POST".equals(request.method())
                && CLIENT_START_PATH.matcher(request.path()).matches()) return "startClient";
        if ("POST".equals(request.method())
                && CLIENT_APPEND_PATH.matcher(request.path()).matches()) return "appendClientEvent";
        if ("POST".equals(request.method())
                && PRESENTATION_APPEND_PATH.matcher(request.path()).matches())
            return "appendPresentation";
        if ("POST".equals(request.method())
                && SUMMARY_SUBMIT_PATH.matcher(request.path()).matches()) return "submitSummary";
        if ("POST".equals(request.method()) && MEETINGS_PATH.equals(request.path())) {
            return "create";
        }
        if ("POST".equals(request.method()) && STOP_PATH.matcher(request.path()).matches()) {
            return "stop";
        }
        if ("POST".equals(request.method()) && RENEW_PATH.matcher(request.path()).matches()) {
            return "renewRealtimeToken";
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

    /** 两个匿名结果路由共享严格的十进制 genTime 表示。 */
    private static long parseGenTime(String raw) {
        if (raw == null || !GEN_TIME.matcher(raw).matches()) {
            throw new IllegalArgumentException("genTime is invalid");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("genTime is invalid");
        }
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

        /**
         * 续期只提交用户 Bearer 的身份请求；会议 ID 和幂等键由 adapter 的
         * 已匹配路径/头直接构造持久命令，解码器不能覆盖归属。
         */
        default IdentityVerificationRequest decodeRenewRealtimeToken(
                HttpRequest request, String pathMeetingId, String idempotencyKey) {
            throw new IllegalArgumentException("renewal decoder is not configured");
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
