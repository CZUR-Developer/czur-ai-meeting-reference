package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.command.AuthenticatedMeetingStopResult;
import com.czur.reference.application.error.IdempotencyConflictException;
import com.czur.reference.application.error.MeetingAlreadyExistsException;
import com.czur.reference.application.error.MeetingNotFoundException;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
