package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.command.AuthenticatedMeetingStopResult;
import com.czur.reference.application.command.AuthenticatedCompleteMeetingCreateResult;
import com.czur.reference.application.command.AuthenticatedRealtimeTokenRenewApplicationService;
import com.czur.reference.application.error.IdempotencyConflictException;
import com.czur.reference.application.error.MeetingAlreadyExistsException;
import com.czur.reference.application.error.MeetingNotFoundException;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.model.BailianSummarySessionIssuer;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.czur.reference.protocol.realtime.RealtimeTranslationEventMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 现有命令/查询/删除端口到统一 REST 错误 envelope 的唯一映射点。
 *
 * <p>状态码和错误码由稳定结果枚举决定，绝不通过异常 message、SQLState、
 * Provider 文本或请求路径推断。该类不执行认证、权限、计费或领域转换；调用方
 * 只需把端口结果交给本类，再把 {@link HttpResponse} 交给具体 HTTP runtime。
 * diagnosticId 由注入的生成器产生，默认是随机 UUID，不能从 meetingId、Token
 * 或 request body 派生。</p>
 */
public final class HttpErrorMapper {
    public static final String SPEC_VERSION = "0.1";
    private static final Logger LOGGER = Logger.getLogger(HttpErrorMapper.class.getName());
    private static final ObjectMapper EVENT_JSON = new ObjectMapper();

    private final Supplier<String> diagnosticIds;

    public HttpErrorMapper() {
        this(() -> UUID.randomUUID().toString());
    }

    /** 测试可注入固定 opaque ID；生产生成器不得拼接业务标识。 */
    public HttpErrorMapper(Supplier<String> diagnosticIds) {
        this.diagnosticIds = Objects.requireNonNull(diagnosticIds, "diagnosticIds");
    }

    public HttpResponse query(MeetingQueryPort.Result result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case MeetingQueryPort.Found found -> success(200, meetingStatus(found.meeting()));
            case MeetingQueryPort.NotFound ignored -> error(404,
                    HttpErrorEnvelope.Domain.SESSION, "MEETING_NOT_FOUND", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.meeting_not_found");
            case MeetingQueryPort.Conflict conflict -> error(409,
                    HttpErrorEnvelope.Domain.CONTRACT,
                    "QUERY_" + conflict.reason().name(), false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.query_conflict");
            case MeetingQueryPort.AccessDenied ignored -> error(403,
                    HttpErrorEnvelope.Domain.IDENTITY, "ACCESS_DENIED", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case MeetingQueryPort.Unavailable unavailable -> error(503,
                    HttpErrorEnvelope.Domain.STORAGE,
                    "QUERY_" + unavailable.reason().name(), unavailable.retryable(),
                    HttpErrorEnvelope.UserAction.RETRY,
                    "ai_meeting.storage.query_unavailable");
        };
    }

    /** App 输入使用原有错误 envelope，成功事件正文直接来自持久 ServerEnvelope。 */
    public HttpResponse clientStart(String meetingId, ClientMeetingInputPort.StartResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof ClientMeetingInputPort.Started started) {
            return HttpResponse.json(200, "{\"meetingId\":\"" + meetingId
                    + "\",\"state\":\"" + started.state().name()
                    + "\",\"inputSource\":\"CLIENT_EVENTS\"}");
        }
        return clientInputFailure(((ClientMeetingInputPort.Rejected) result).reason());
    }

    /** 模型材料只在已认证的成功响应中出现，日志和错误 envelope 不含 JoinUrl。 */
    public HttpResponse modelSession(String meetingId, ModelSessionIssuer.Session session) {
        return success(200, Map.of("meetingId", meetingId, "adapterId", session.adapterId(),
                "endpoint", session.endpoint(), "expiresAt", session.expiresAt().toString(),
                "initParams", session.initParams()));
    }

    public HttpResponse modelSessionInvalidState() {
        return error(409, HttpErrorEnvelope.Domain.SESSION, "MODEL_SESSION_INVALID_STATE",
                false, HttpErrorEnvelope.UserAction.NONE,
                "ai_meeting.session.model_session_invalid_state");
    }

    public HttpResponse modelSessionUnavailable() {
        return error(503, HttpErrorEnvelope.Domain.MODEL, "MODEL_SESSION_UNAVAILABLE",
                true, HttpErrorEnvelope.UserAction.RETRY,
                "ai_meeting.model.session_unavailable");
    }

    /** 永久 Key 不进入响应；只把本场短期材料交给已认证 App。 */
    public HttpResponse summarySession(String meetingId,
            BailianSummarySessionIssuer.Session session) {
        return success(200, Map.of("meetingId", meetingId, "adapterId", session.adapterId(),
                "endpoint", session.endpoint(), "expiresAt", session.expiresAt().toString(),
                "token", session.token()));
    }

    public HttpResponse summaryNotGranted() {
        return clientInputFailure(ClientMeetingInputPort.Reason.SUMMARY_NOT_GRANTED);
    }

    public HttpResponse summarySessionInvalidState() {
        return error(409, HttpErrorEnvelope.Domain.SESSION, "SUMMARY_SESSION_INVALID_STATE",
                false, HttpErrorEnvelope.UserAction.NONE,
                "ai_meeting.session.summary_session_invalid_state");
    }

    public HttpResponse summarySessionUnavailable() {
        return error(503, HttpErrorEnvelope.Domain.MODEL, "SUMMARY_SESSION_UNAVAILABLE",
                true, HttpErrorEnvelope.UserAction.RETRY,
                "ai_meeting.model.summary_session_unavailable");
    }

    public HttpResponse clientAppend(ClientMeetingInputPort.AppendResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof ClientMeetingInputPort.Appended appended) {
            return HttpResponse.json(appended.created() ? 201 : 200,
                    appended.event().canonicalPayloadJson());
        }
        return clientInputFailure(((ClientMeetingInputPort.Rejected) result).reason());
    }

    public HttpResponse presentationAppend(ClientMeetingInputPort.CaptureAppendResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof ClientMeetingInputPort.CaptureAppended appended) {
            return success(appended.created() ? 201 : 200, Map.of(
                    "artifactId", appended.artifactId(),
                    "chunkSequence", appended.chunkSequence(),
                    "frameCount", appended.frameCount()));
        }
        return clientInputFailure(((ClientMeetingInputPort.Rejected) result).reason());
    }

    public HttpResponse summarySubmit(String meetingId,
            ClientMeetingInputPort.SummarySubmitResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof ClientMeetingInputPort.SummarySubmitted submitted) {
            return success(submitted.created() ? 201 : 200,
                    Map.of("meetingId", meetingId, "accepted", true));
        }
        return clientInputFailure(((ClientMeetingInputPort.Rejected) result).reason());
    }

    /** 首次冻结与同值重放返回同一累计值；价格／扣费规则不进入公共响应。 */
    public HttpResponse usageSubmit(String meetingId,
            ClientMeetingInputPort.UsageSubmitResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof ClientMeetingInputPort.UsageSubmitted submitted) {
            return success(submitted.created() ? 201 : 200, Map.of(
                    "meetingId", meetingId, "audioDurationMs", submitted.audioDurationMs(),
                    "accepted", true));
        }
        return clientInputFailure(((ClientMeetingInputPort.Rejected) result).reason());
    }

    private HttpResponse clientInputFailure(ClientMeetingInputPort.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> error(404, HttpErrorEnvelope.Domain.SESSION,
                    "MEETING_NOT_FOUND", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.session.meeting_not_found");
            case ACCESS_DENIED -> error(403, HttpErrorEnvelope.Domain.IDENTITY,
                    "ACCESS_DENIED", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case INVALID_STATE, INPUT_SOURCE_CONFLICT, EVENT_CONFLICT -> error(409,
                    HttpErrorEnvelope.Domain.CONTRACT, reason.name(), false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.input_conflict");
            case PRESENTATION_NOT_REQUESTED -> error(422, HttpErrorEnvelope.Domain.CONTRACT,
                    "PRESENTATION_NOT_REQUESTED", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.contract.presentation_not_requested");
            case CAPABILITY_NOT_GRANTED -> error(422, HttpErrorEnvelope.Domain.ENTITLEMENT,
                    "TRANSLATION_NOT_GRANTED", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.entitlement.translation_not_granted");
            case SPEAKER_NOT_GRANTED -> error(422, HttpErrorEnvelope.Domain.ENTITLEMENT,
                    "SPEAKER_NOT_GRANTED", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.entitlement.speaker_not_granted");
            case SUMMARY_NOT_GRANTED -> error(422, HttpErrorEnvelope.Domain.ENTITLEMENT,
                    "SUMMARY_NOT_GRANTED", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.entitlement.summary_not_granted");
            case STORAGE_UNAVAILABLE -> error(503, HttpErrorEnvelope.Domain.STORAGE,
                    "CLIENT_INPUT_UNAVAILABLE", true, HttpErrorEnvelope.UserAction.RETRY,
                    "ai_meeting.storage.input_unavailable");
        };
    }

    /** 匿名结果查询只映射既有端口的稳定结果，不推断 JDBC 或策略异常消息。 */
    public HttpResponse manifest(MeetingManifestPort.Result result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case MeetingManifestPort.Pending pending ->
                    success(202, meetingStatus(pending.meetingStatus()));
            case MeetingManifestPort.Found found ->
                    HttpResponse.json(200, MeetingManifestJson.encode(found.manifest()));
            case MeetingManifestPort.NotFound ignored -> error(404,
                    HttpErrorEnvelope.Domain.SESSION, "MANIFEST_NOT_FOUND", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.manifest_not_found");
            case MeetingManifestPort.Conflict conflict -> error(409,
                    HttpErrorEnvelope.Domain.CONTRACT,
                    "MANIFEST_" + conflict.reason().name(), false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.manifest_conflict");
            case MeetingManifestPort.AccessDenied ignored -> error(403,
                    HttpErrorEnvelope.Domain.IDENTITY, "ACCESS_DENIED", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case MeetingManifestPort.Unavailable unavailable -> error(
                    unavailable.retryable() ? 503 : 500,
                    unavailable.retryable()
                            ? HttpErrorEnvelope.Domain.STORAGE : HttpErrorEnvelope.Domain.INTERNAL,
                    "MANIFEST_" + unavailable.reason().name(), unavailable.retryable(),
                    unavailable.retryable() ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.storage.manifest_unavailable");
        };
    }

    /** 仅输出已经核对的 canonical Mock JSON；状态语义与匿名 Manifest 一致。 */
    public HttpResponse artifact(MeetingArtifactContentPort.Result result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case MeetingArtifactContentPort.Found found ->
                    HttpResponse.json(200, new String(found.content(), StandardCharsets.UTF_8));
            case MeetingArtifactContentPort.Pending pending ->
                    success(202, meetingStatus(pending.meetingStatus()));
            case MeetingArtifactContentPort.NotFound ignored -> error(404,
                    HttpErrorEnvelope.Domain.SESSION, "ARTIFACT_NOT_FOUND", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.artifact_not_found");
            case MeetingArtifactContentPort.Conflict ignored -> error(409,
                    HttpErrorEnvelope.Domain.CONTRACT, "ARTIFACT_INCONSISTENT", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.artifact_conflict");
            case MeetingArtifactContentPort.AccessDenied ignored -> error(403,
                    HttpErrorEnvelope.Domain.IDENTITY, "ACCESS_DENIED", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case MeetingArtifactContentPort.Unavailable unavailable -> error(
                    unavailable.retryable() ? 503 : 500,
                    unavailable.retryable()
                            ? HttpErrorEnvelope.Domain.STORAGE : HttpErrorEnvelope.Domain.INTERNAL,
                    "ARTIFACT_UNAVAILABLE", unavailable.retryable(),
                    unavailable.retryable() ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.storage.artifact_unavailable");
        };
    }

    public HttpResponse list(MeetingQueryPort.ListResult result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case MeetingQueryPort.ListPage page -> success(200, listPage(page));
            case MeetingQueryPort.ListNotFound ignored -> error(404,
                    HttpErrorEnvelope.Domain.SESSION, "MEETING_LIST_NOT_FOUND", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.meeting_list_not_found");
            case MeetingQueryPort.ListConflict conflict -> error(409,
                    HttpErrorEnvelope.Domain.CONTRACT,
                    "LIST_" + conflict.reason().name(), false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.list_conflict");
            case MeetingQueryPort.ListAccessDenied ignored -> error(403,
                    HttpErrorEnvelope.Domain.IDENTITY, "ACCESS_DENIED", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case MeetingQueryPort.ListUnavailable unavailable -> error(503,
                    HttpErrorEnvelope.Domain.STORAGE,
                    "LIST_" + unavailable.reason().name(), unavailable.retryable(),
                    HttpErrorEnvelope.UserAction.RETRY,
                    "ai_meeting.storage.list_unavailable");
        };
    }

    /**
     * 将同一持久端口的认证事件页映射为公开 Protobuf JSON Mapping。
     *
     * <p>P2 仅发布 {@code transcriptUpsert}。每条事件先验证完整 Envelope 与
     * 数据库列事实相同，才输出已保存的规范 JSON；坏事实或超出 HTTP 响应上限
     * 关闭式返回 500，绝不伪装成保留窗口过期的 410。</p>
     */
    public HttpResponse events(MeetingEventLogPort.Result result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case MeetingEventLogPort.Page page -> eventPage(page.page());
            case MeetingEventLogPort.NotFound ignored -> error(404,
                    HttpErrorEnvelope.Domain.SESSION, "MEETING_NOT_FOUND", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.meeting_not_found");
            case MeetingEventLogPort.AccessDenied ignored -> error(403,
                    HttpErrorEnvelope.Domain.IDENTITY, "ACCESS_DENIED", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case MeetingEventLogPort.Conflict conflict ->
                    conflict.reason() == MeetingEventLogPort.ConflictReason.INVALID_CURSOR
                            ? error(409, HttpErrorEnvelope.Domain.CONTRACT,
                                    "INVALID_CURSOR", false, HttpErrorEnvelope.UserAction.NONE,
                                    "ai_meeting.contract.invalid_cursor")
                            : error(410, HttpErrorEnvelope.Domain.SESSION,
                                    "EVENT_REPLAY_WINDOW_EXPIRED", false,
                                    HttpErrorEnvelope.UserAction.NONE,
                                    "ai_meeting.session.event_replay_window_expired");
            case MeetingEventLogPort.Unavailable unavailable -> error(
                    unavailable.retryable() ? 503 : 500,
                    unavailable.retryable() ? HttpErrorEnvelope.Domain.STORAGE
                            : HttpErrorEnvelope.Domain.INTERNAL,
                    "EVENT_LOG_UNAVAILABLE", unavailable.retryable(),
                    unavailable.retryable() ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.storage.event_log_unavailable");
        };
    }

    private HttpResponse eventPage(com.czur.reference.application.port.MeetingEventPage page) {
        try {
            ObjectNode body = EVENT_JSON.createObjectNode();
            ArrayNode items = body.putArray("items");
            for (var event : page.items()) {
                // 验证正文、oneof、序号、类型和外层列；只解析已验证的规范 JSON。
                if (RealtimeTranslationEventMapper.EVENT_TYPE.equals(event.eventType())) {
                    RealtimeTranslationEventMapper.fromStored(event);
                } else {
                    RealtimeTranscriptEventMapper.fromStored(event);
                }
                items.add(EVENT_JSON.readTree(event.canonicalPayloadJson()));
            }
            body.put("lastSequence", page.lastSequence());
            body.put("hasMore", page.hasMore());
            return HttpResponse.json(200, EVENT_JSON.writeValueAsString(body));
        } catch (JsonProcessingException | RuntimeException invalidFact) {
            // 禁止把不可信事件正文、解析异常或体积超限信息反射到客户端/日志。
            return internalFailure();
        }
    }

    public HttpResponse deletion(DeleteResult result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case DeleteResult.Accepted accepted -> success(202, meetingStatus(accepted.meeting()));
            case DeleteResult.Replayed replayed -> success(202, meetingStatus(replayed.meeting()));
            case DeleteResult.NotFound ignored -> error(404,
                    HttpErrorEnvelope.Domain.SESSION, "MEETING_NOT_FOUND", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.meeting_not_found");
            case DeleteResult.Conflict conflict -> error(409,
                    HttpErrorEnvelope.Domain.CONTRACT,
                    "DELETE_" + conflict.reason().name(), false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.delete_conflict");
            case DeleteResult.AccessDenied ignored -> error(403,
                    HttpErrorEnvelope.Domain.IDENTITY, "ACCESS_DENIED", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case DeleteResult.Unavailable unavailable -> error(
                    unavailable.retryable() ? 503 : 500,
                    unavailable.retryable()
                            ? HttpErrorEnvelope.Domain.STORAGE : HttpErrorEnvelope.Domain.INTERNAL,
                    "DELETE_" + unavailable.reason().name(), unavailable.retryable(),
                    unavailable.retryable()
                            ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.storage.delete_unavailable");
        };
    }

    /** 将认证 Stop 的稳定结果映射为严格的 OpenAPI StopResponse。 */
    public HttpResponse stop(AuthenticatedMeetingStopResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof AuthenticatedMeetingStopResult.Success success) {
            int status = success.meeting().inputState()
                    == com.czur.reference.application.port.InputState.INPUT_ACCEPTED
                    ? 200 : 202;
            return success(status, stopStatus(success.meeting()));
        }
        if (result instanceof AuthenticatedMeetingStopResult.IdentityFailure failure) {
            return identityFailure(failure.error().code());
        }
        if (result instanceof AuthenticatedMeetingStopResult.StopFailure failure) {
            return stopFailure(failure.code());
        }
        if (result instanceof AuthenticatedMeetingStopResult.QueryFailure failure) {
            return switch (failure.code()) {
                case NOT_FOUND -> error(404, HttpErrorEnvelope.Domain.SESSION,
                        "MEETING_NOT_FOUND", false, HttpErrorEnvelope.UserAction.NONE,
                        "ai_meeting.session.meeting_not_found");
                case ACCESS_DENIED -> error(403, HttpErrorEnvelope.Domain.IDENTITY,
                        "ACCESS_DENIED", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                        "ai_meeting.identity.access_denied");
                case CONFLICT -> error(409, HttpErrorEnvelope.Domain.CONTRACT,
                        "STOP_QUERY_CONFLICT", false, HttpErrorEnvelope.UserAction.NONE,
                        "ai_meeting.contract.query_conflict");
                case UNAVAILABLE -> error(failure.retryable() ? 503 : 500,
                        failure.retryable() ? HttpErrorEnvelope.Domain.STORAGE
                                : HttpErrorEnvelope.Domain.INTERNAL,
                        "STOP_QUERY_UNAVAILABLE", failure.retryable(),
                        failure.retryable() ? HttpErrorEnvelope.UserAction.RETRY
                                : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                        "ai_meeting.storage.query_unavailable");
            };
        }
        return error(500, HttpErrorEnvelope.Domain.INTERNAL,
                "INTERNAL_ERROR", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                "ai_meeting.internal.error");
    }

    /**
     * 续期只输出 V014 事务已经持久确认的 Grant JSON 原文，不重新编码 Token。
     * 身份和事务错误仅依据稳定枚举映射；未知提交结果保持可重试 503。
     */
    public HttpResponse renew(AuthenticatedRealtimeTokenRenewApplicationService.Result result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof AuthenticatedRealtimeTokenRenewApplicationService.IdentityFailure
                identity) {
            return identityVerificationFailure(identity.error());
        }
        if (result instanceof AuthenticatedRealtimeTokenRenewApplicationService.InternalFailure) {
            return internalFailure();
        }
        RenewRealtimeTokenUnitOfWork.Result persisted =
                ((AuthenticatedRealtimeTokenRenewApplicationService.Renewal) result).result();
        if (persisted instanceof RenewRealtimeTokenUnitOfWork.Success success) {
            return HttpResponse.json(200, success.persistedGrantJson());
        }
        RenewRealtimeTokenUnitOfWork.ErrorCode code =
                ((RenewRealtimeTokenUnitOfWork.Failure) persisted).code();
        return switch (code) {
            case NOT_FOUND -> error(404, HttpErrorEnvelope.Domain.SESSION,
                    "MEETING_NOT_FOUND", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.session.meeting_not_found");
            case ACCESS_DENIED -> error(403, HttpErrorEnvelope.Domain.IDENTITY,
                    "ACCESS_DENIED", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case STATE_CONFLICT -> error(409, HttpErrorEnvelope.Domain.SESSION,
                    "INVALID_MEETING_STATE", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.session.invalid_meeting_state");
            case FACT_INCONSISTENT -> error(500, HttpErrorEnvelope.Domain.INTERNAL,
                    "REALTIME_FACT_INCONSISTENT", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.internal.realtime_fact_inconsistent");
            case STORAGE_UNAVAILABLE, OUTCOME_UNKNOWN -> error(503,
                    HttpErrorEnvelope.Domain.STORAGE, "REALTIME_" + code.name(), true,
                    HttpErrorEnvelope.UserAction.RETRY,
                    "ai_meeting.storage.realtime_unavailable");
            case INTERNAL -> internalFailure();
        };
    }

    /**
     * 将完整 Create 应用结果映射为公开协议；成功只投影 V013 已提交或只读回查的事实。
     *
     * <p>首次和同键重放均为 201，均从应用结果携带的原始响应事实投影。失败只按
     * 封闭结果类型和稳定枚举映射，不读取 Provider/数据库异常正文；没有
     * retryable 位的结果不会被猜测为可重试。此方法不调用身份或创建端口。</p>
     */
    public HttpResponse create(AuthenticatedCompleteMeetingCreateResult result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case AuthenticatedCompleteMeetingCreateResult.Success success ->
                    HttpResponse.json(201, CreateMeetingPublicResponseJson.encode(
                            success.response()));
            case AuthenticatedCompleteMeetingCreateResult.IdentityFailure failure ->
                    createIdentityFailure(failure.error());
            case AuthenticatedCompleteMeetingCreateResult.EntitlementFailure failure ->
                    createEntitlementFailure(failure.error());
            case AuthenticatedCompleteMeetingCreateResult.Conflict ignored ->
                    error(409, HttpErrorEnvelope.Domain.CONTRACT, "IDEMPOTENCY_CONFLICT", false,
                            HttpErrorEnvelope.UserAction.NONE,
                            "ai_meeting.contract.idempotency_conflict");
            case AuthenticatedCompleteMeetingCreateResult.Inconsistent ignored ->
                    error(500, HttpErrorEnvelope.Domain.INTERNAL, "CREATE_FACT_INCONSISTENT",
                            false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                            "ai_meeting.internal.create_inconsistent");
            case AuthenticatedCompleteMeetingCreateResult.Unavailable ignored ->
                    error(503, HttpErrorEnvelope.Domain.INTERNAL, "CREATE_DEPENDENCY_UNAVAILABLE",
                            false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                            "ai_meeting.internal.create_unavailable");
            case AuthenticatedCompleteMeetingCreateResult.PreparationFailure failure ->
                    createPreparationFailure(failure.code());
            case AuthenticatedCompleteMeetingCreateResult.CreationFailure failure ->
                    createCommitFailure(failure);
        };
    }

    private HttpResponse createIdentityFailure(
            com.czur.reference.application.port.identity.IdentityProviderError failure) {
        boolean retryable = failure.retryable();
        return switch (failure.code()) {
            case INVALID_REQUEST -> error(400, HttpErrorEnvelope.Domain.CONTRACT,
                    "INVALID_REQUEST", retryable, retryable
                            ? HttpErrorEnvelope.UserAction.RETRY : HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.contract.invalid_request");
            case PERMISSION_DENIED -> error(403, HttpErrorEnvelope.Domain.IDENTITY,
                    "PERMISSION_DENIED", retryable, retryable
                            ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.permission_denied");
            case PROVIDER_UNAVAILABLE -> error(503, HttpErrorEnvelope.Domain.IDENTITY,
                    "IDENTITY_PROVIDER_UNAVAILABLE", retryable, retryable
                            ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.provider_unavailable");
            case INTERNAL -> error(500, HttpErrorEnvelope.Domain.INTERNAL,
                    "INTERNAL_ERROR", retryable, retryable
                            ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.internal.error");
            case INVALID_CREDENTIAL, CREDENTIAL_EXPIRED, IDENTITY_DISABLED -> error(401,
                    HttpErrorEnvelope.Domain.IDENTITY, "AUTHENTICATION_FAILED", retryable,
                    retryable ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.LOGIN,
                    "ai_meeting.identity.authentication_failed");
        };
    }

    private HttpResponse createEntitlementFailure(
            com.czur.reference.application.port.entitlement.EntitlementProviderError failure) {
        var code = failure.code();
        boolean retryable = failure.retryable();
        return switch (code) {
            case INVALID_REQUEST -> error(400, HttpErrorEnvelope.Domain.CONTRACT,
                    "ENTITLEMENT_INVALID_REQUEST", retryable,
                    retryable ? HttpErrorEnvelope.UserAction.RETRY : HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.entitlement.invalid_request");
            case PERMISSION_DENIED -> error(403, HttpErrorEnvelope.Domain.IDENTITY,
                    "ENTITLEMENT_PERMISSION_DENIED", retryable,
                    retryable ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.entitlement.permission_denied");
            case ENTITLEMENT_DENIED, CAPABILITY_NOT_GRANTED, GRANT_EXPIRED -> error(422,
                    HttpErrorEnvelope.Domain.ENTITLEMENT, "ENTITLEMENT_" + code.name(),
                    retryable, retryable ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.entitlement." + code.name().toLowerCase(Locale.ROOT));
            case RESERVATION_EXPIRED, RESERVATION_NOT_FOUND, RESERVATION_SCOPE_MISMATCH,
                    RESERVATION_STATE_CONFLICT, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT ->
                    error(409, HttpErrorEnvelope.Domain.ENTITLEMENT,
                            "ENTITLEMENT_" + code.name(), retryable,
                            retryable ? HttpErrorEnvelope.UserAction.RETRY
                                    : HttpErrorEnvelope.UserAction.NONE,
                            "ai_meeting.entitlement." + code.name().toLowerCase(Locale.ROOT));
            case PROVIDER_UNAVAILABLE -> error(503, HttpErrorEnvelope.Domain.ENTITLEMENT,
                    "ENTITLEMENT_PROVIDER_UNAVAILABLE", retryable,
                    retryable ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.entitlement.provider_unavailable");
            case INTERNAL -> error(500, HttpErrorEnvelope.Domain.INTERNAL, "INTERNAL_ERROR",
                    retryable, retryable ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.internal.error");
        };
    }

    private HttpResponse createPreparationFailure(
            com.czur.reference.application.command.CompleteMeetingResponseAssembler.FailureCode code) {
        return switch (code) {
            case WINNER_INCONSISTENT, PROFILE_MISMATCH -> error(500,
                    HttpErrorEnvelope.Domain.INTERNAL, "CREATE_PREPARATION_INCONSISTENT", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.internal.create_inconsistent");
            case CAPABILITY_UNAVAILABLE -> error(422, HttpErrorEnvelope.Domain.CONTRACT,
                    "CREATE_CAPABILITY_UNAVAILABLE", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.contract.capability_unavailable");
            case AUDIO_UNSUPPORTED -> error(422, HttpErrorEnvelope.Domain.AUDIO,
                    "CREATE_AUDIO_UNSUPPORTED", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.audio.unsupported");
            case SESSION_UNAVAILABLE -> error(503, HttpErrorEnvelope.Domain.SESSION,
                    "CREATE_SESSION_UNAVAILABLE", false,
                    HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.session.create_unavailable");
        };
    }

    private HttpResponse createCommitFailure(
            AuthenticatedCompleteMeetingCreateResult.CreationFailure failure) {
        var code = failure.code();
        return switch (code) {
            case IDEMPOTENCY_CONFLICT, RESERVATION_NOT_FOUND, RESERVATION_EXPIRED,
                    RESERVATION_STATE_CONFLICT, VERSION_CONFLICT, MEETING_CONFLICT ->
                    error(409, HttpErrorEnvelope.Domain.CONTRACT, "CREATE_" + code.name(),
                            failure.retryable(), failure.retryable()
                                    ? HttpErrorEnvelope.UserAction.RETRY
                                    : HttpErrorEnvelope.UserAction.NONE,
                            "ai_meeting.contract.create_conflict");
            case STORAGE_UNAVAILABLE, OUTCOME_UNKNOWN -> error(503,
                    HttpErrorEnvelope.Domain.STORAGE, "CREATE_" + code.name(),
                    failure.retryable(), failure.retryable()
                            ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.storage.create_unavailable");
            case INTERNAL -> error(500, HttpErrorEnvelope.Domain.INTERNAL, "INTERNAL_ERROR",
                    failure.retryable(), failure.retryable()
                            ? HttpErrorEnvelope.UserAction.RETRY
                            : HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.internal.error");
        };
    }

    private HttpResponse identityFailure(
            com.czur.reference.application.port.identity.IdentityProviderError.Code code) {
        return switch (code) {
            case PERMISSION_DENIED -> error(403, HttpErrorEnvelope.Domain.IDENTITY,
                    "PERMISSION_DENIED", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.permission_denied");
            case PROVIDER_UNAVAILABLE -> error(503, HttpErrorEnvelope.Domain.IDENTITY,
                    "IDENTITY_PROVIDER_UNAVAILABLE", true, HttpErrorEnvelope.UserAction.RETRY,
                    "ai_meeting.identity.provider_unavailable");
            case INTERNAL -> error(500, HttpErrorEnvelope.Domain.INTERNAL,
                    "INTERNAL_ERROR", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.internal.error");
            case INVALID_REQUEST -> error(400, HttpErrorEnvelope.Domain.CONTRACT,
                    "INVALID_REQUEST", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.contract.invalid_request");
            default -> error(401, HttpErrorEnvelope.Domain.IDENTITY,
                    "AUTHENTICATION_FAILED", false, HttpErrorEnvelope.UserAction.LOGIN,
                    "ai_meeting.identity.authentication_failed");
        };
    }

    /**
     * 已验证用户的读取/删除入口使用与 Stop 一致的稳定身份错误映射。
     *
     * <p>只读取 Provider 的枚举错误码，不接收 Token、远端错误正文或异常消息。
     * 缺失/畸形 Bearer 由调用方使用 {@link #missingContext()}。</p>
     */
    public HttpResponse identityVerificationFailure(
            com.czur.reference.application.port.identity.IdentityProviderError error) {
        return identityFailure(Objects.requireNonNull(error, "error").code());
    }

    private HttpResponse stopFailure(OwnedMeetingStopUnitOfWork.ErrorCode code) {
        return switch (code) {
            case MEETING_NOT_FOUND -> error(404, HttpErrorEnvelope.Domain.SESSION,
                    "MEETING_NOT_FOUND", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.session.meeting_not_found");
            case ACCESS_DENIED -> error(403, HttpErrorEnvelope.Domain.IDENTITY,
                    "ACCESS_DENIED", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.identity.access_denied");
            case IDEMPOTENCY_CONFLICT -> error(409, HttpErrorEnvelope.Domain.CONTRACT,
                    "IDEMPOTENCY_CONFLICT", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.contract.idempotency_conflict");
            case INVALID_MEETING_STATE -> error(409, HttpErrorEnvelope.Domain.SESSION,
                    "INVALID_MEETING_STATE", false, HttpErrorEnvelope.UserAction.NONE,
                    "ai_meeting.session.invalid_meeting_state");
            case STORAGE_UNAVAILABLE, OUTCOME_UNKNOWN -> error(503,
                    HttpErrorEnvelope.Domain.STORAGE, code.name(), true,
                    HttpErrorEnvelope.UserAction.RETRY,
                    "ai_meeting.storage.stop_unavailable");
            case INTERNAL -> error(500, HttpErrorEnvelope.Domain.INTERNAL,
                    "INTERNAL_ERROR", false, HttpErrorEnvelope.UserAction.CONTACT_ADMIN,
                    "ai_meeting.internal.error");
        };
    }

    /** 创建/停止命令成功只投影稳定 meetingId/state，能力扩展由后续协议切片提供。 */
    public HttpResponse command(MeetingCommandResult result) {
        Objects.requireNonNull(result, "result");
        int status = result.operation() == MeetingCommandResult.Operation.CREATED ? 201 : 200;
        return success(status, meetingCommand(result));
    }

    /** 捕获边界异常时仅使用白名单类型；其它异常固定为 INTERNAL。 */
    public HttpResponse exception(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof CreateMeetingHttpRequestDecoder.DecodeFailure decodeFailure
                && decodeFailure.kind()
                        == CreateMeetingHttpRequestDecoder.DecodeFailure.Kind.AUTHENTICATION) {
            return missingContext();
        }
        if (failure instanceof ReferenceMeetingHttpRequestDecoder.AuthenticationFailure) {
            return missingContext();
        }
        if (failure instanceof IdempotencyConflictException) {
            return error(409, HttpErrorEnvelope.Domain.CONTRACT, "IDEMPOTENCY_CONFLICT", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.idempotency_conflict");
        }
        if (failure instanceof MeetingAlreadyExistsException) {
            return error(409, HttpErrorEnvelope.Domain.SESSION, "MEETING_ALREADY_EXISTS", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.meeting_already_exists");
        }
        if (failure instanceof MeetingNotFoundException) {
            return error(404, HttpErrorEnvelope.Domain.SESSION, "MEETING_NOT_FOUND", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.session.meeting_not_found");
        }
        if (failure instanceof IllegalArgumentException) {
            return error(400, HttpErrorEnvelope.Domain.CONTRACT, "INVALID_REQUEST", false,
                    HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.invalid_request");
        }
        return error(500, HttpErrorEnvelope.Domain.INTERNAL, "INTERNAL_ERROR", false,
                HttpErrorEnvelope.UserAction.CONTACT_ADMIN, "ai_meeting.internal.error");
    }

    public HttpResponse invalidRoute() {
        return error(404, HttpErrorEnvelope.Domain.CONTRACT, "ROUTE_NOT_FOUND", false,
                HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.route_not_found");
    }

    public HttpResponse missingContext() {
        return error(401, HttpErrorEnvelope.Domain.IDENTITY, "AUTHENTICATION_REQUIRED", false,
                HttpErrorEnvelope.UserAction.LOGIN, "ai_meeting.identity.authentication_required");
    }

    /**
     * 当前 transport slice 尚未装配安全的命令 application service 时的关闭式响应。
     *
     * <p>该状态不是业务成功，也不暗示稍后会自动完成请求；调用方应等待支持
     * create/stop 的正式 adapter slice。响应不携带旧命令服务的异常或正文。</p>
     */
    public HttpResponse notImplemented() {
        return error(501, HttpErrorEnvelope.Domain.CONTRACT, "ROUTE_NOT_IMPLEMENTED", false,
                HttpErrorEnvelope.UserAction.NONE, "ai_meeting.contract.route_not_implemented");
    }

    /**
     * 参考后端 HTTP 运行时已经关闭或尚未接收请求时的关闭式响应。
     *
     * <p>该响应与数据库端口、模型端口和业务状态无关，只表示当前入口不再
     * 接受请求。它使用可重试的 503，而不是把关闭状态伪装成 404 或业务错误；
     * 调用方可以据此等待新的运行时实例。错误 envelope 不携带运行时对象、
     * 数据库地址或关闭异常正文。</p>
     */
    public HttpResponse runtimeUnavailable() {
        return error(503, HttpErrorEnvelope.Domain.INTERNAL, "RUNTIME_NOT_READY", true,
                HttpErrorEnvelope.UserAction.RETRY, "ai_meeting.runtime.not_ready");
    }

    /** Create 解码成功后应用/投影意外失败的固定 500；不接收或输出异常正文。 */
    public HttpResponse internalFailure() {
        return error(500, HttpErrorEnvelope.Domain.INTERNAL, "INTERNAL_ERROR", false,
                HttpErrorEnvelope.UserAction.CONTACT_ADMIN, "ai_meeting.internal.error");
    }

    private HttpResponse error(
            int status,
            HttpErrorEnvelope.Domain domain,
            String code,
            boolean retryable,
            HttpErrorEnvelope.UserAction userAction,
            String messageKey) {
        String diagnosticId = Objects.requireNonNull(diagnosticIds.get(), "diagnosticId");
        HttpErrorEnvelope envelope = new HttpErrorEnvelope(SPEC_VERSION,
                new HttpErrorEnvelope.Error(
                        domain, code, HttpErrorEnvelope.Severity.FATAL, retryable,
                        retryable ? 1_000 : null, userAction, messageKey, diagnosticId));
        LOGGER.log(Level.WARNING,
                "meeting HTTP error mapped status={0} domain={1} code={2} diagnosticId={3}",
                new Object[]{status, domain, code, diagnosticId});
        return HttpResponse.json(status, envelope.toJson());
    }

    private static HttpResponse success(int status, Map<String, Object> body) {
        try {
            return HttpResponse.json(status,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("HTTP success encoding failed", impossible);
        }
    }

    private static Map<String, Object> meetingCommand(MeetingCommandResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meetingId", result.meeting().meetingId());
        body.put("state", result.meeting().state().name());
        return body;
    }

    private static Map<String, Object> meetingStatus(
            com.czur.reference.application.port.MeetingStatus meeting) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meetingId", meeting.meetingId());
        body.put("state", meeting.state().name());
        body.put("inputState", meeting.inputState().name());
        body.put("finalizationReceipt", meeting.finalizationReceipt());
        body.put("manifestState", meeting.manifestState().name());
        body.put("updatedAt", meeting.updatedAt().toString());
        return body;
    }

    private static Map<String, Object> stopStatus(
            com.czur.reference.application.port.MeetingStatus meeting) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meetingId", meeting.meetingId());
        body.put("state", meeting.state().name());
        body.put("inputState", meeting.inputState().name());
        body.put("finalizationReceipt", meeting.finalizationReceipt());
        body.put("manifestState", meeting.manifestState().name());
        return body;
    }

    private static Map<String, Object> listPage(MeetingQueryPort.ListPage page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("meetingId", item.meetingId());
            value.put("title", item.title());
            value.put("state", item.state().name());
            value.put("inputState", item.inputState().name());
            value.put("manifestState", item.manifestState().name());
            value.put("createdAt", item.createdAt().toString());
            value.put("updatedAt", item.updatedAt().toString());
            return value;
        }).toList());
        body.put("nextPageToken", page.nextPageToken());
        return body;
    }
}
