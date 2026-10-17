package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.speaker.SpeakerIdentityEnrollmentPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 可选会后人物注册 HTTP 入口；认证由公共 dispatcher 完成，业务归属由端口检查。
 *
 * <p>POST /v1/meetings/{meetingId}/speakers/{sessionSpeakerId}:enroll，正文仅提交
 * displayName。成功返回标准人物与新 Manifest revision。结果链接保持只读；
 * Web 需要经可替换登录入口获得 Bearer 后再调用本接口，不能使用 genTime 写入。</p>
 */
public final class SpeakerIdentityEnrollmentEndpoint {
    private static final Pattern PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/speakers/([^/:]+):enroll$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SpeakerIdentityEnrollmentPort port;
    private final HttpErrorMapper errors;

    public SpeakerIdentityEnrollmentEndpoint(SpeakerIdentityEnrollmentPort port, HttpErrorMapper errors) {
        this.port = Objects.requireNonNull(port, "port");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    public static boolean matches(HttpRequest request) {
        return "POST".equals(request.method()) && matchesPath(request.path());
    }

    /** 供参考网络预检复用同一路由形状，不执行认证或命名业务。 */
    static boolean matchesPath(String path) { return PATH.matcher(path).matches(); }

    public HttpResponse handle(HttpRequest request, MeetingCommandContext context) {
        try {
            var matcher = PATH.matcher(request.path());
            if (!matcher.matches() || !request.query().isEmpty()) return errors.invalidRoute();
            var body = JSON.readTree(request.body());
            if (body == null || !body.isObject() || body.size() != 1
                    || !body.path("displayName").isTextual() || body.path("displayName").asText().isBlank()
                    || body.path("displayName").asText().codePointCount(0,
                            body.path("displayName").asText().length()) > 128) {
                return errors.exception(new IllegalArgumentException("display name is invalid"));
            }
            var result = port.enroll(context, matcher.group(1), matcher.group(2),
                    body.path("displayName").asText().strip());
            if (result instanceof SpeakerIdentityEnrollmentPort.Enrolled enrolled) {
                return HttpResponse.json(200, JSON.writeValueAsString(Map.of(
                        "personId", enrolled.person().personId(),
                        "displayName", enrolled.person().displayName(),
                        "manifestRevision", enrolled.manifestRevision())));
            }
            return errors.speakerEnrollmentFailure(((SpeakerIdentityEnrollmentPort.Rejected) result).reason());
        } catch (Exception failure) {
            return errors.exception(failure);
        }
    }
}
