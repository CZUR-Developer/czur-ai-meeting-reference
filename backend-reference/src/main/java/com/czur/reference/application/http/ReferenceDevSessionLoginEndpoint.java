package com.czur.reference.application.http;

import com.czur.reference.application.identity.referencedev.ReferenceDevLoginConfig;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 仅供参考部署使用的预置账号登录 HTTP 端口。
 *
 * <p>此端口只交换一个预置用户名/密码与固定演示 Bearer Token。会议 Create、Stop、
 * 查询和模型会话仍走现有身份 Provider；合作伙伴可替换整个参考身份实现，
 * 无需依赖这里的账号配置。没有自助注册、后台管理、刷新或撤销功能。</p>
 */
public final class ReferenceDevSessionLoginEndpoint {
    /** 与参考 Android App 约定的唯一登录路径。 */
    public static final String PATH = "/v1/reference/session:login";
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceDevSessionLoginEndpoint.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReferenceDevLoginConfig config;
    private final HttpErrorMapper errors;
    private final Clock clock;

    /** 使用同一部署的预置身份配置、错误映射器和时钟。 */
    public ReferenceDevSessionLoginEndpoint(ReferenceDevLoginConfig config,
            HttpErrorMapper errors, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 处理 POST 登录；只记录固定状态，不记录账号、密码或返回的 Token。
     * 请求必须是 JSON 对象，包含非空字符串 {@code username} 与 {@code password}。
     */
    public HttpResponse handle(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        if (!"POST".equals(request.method()) || !PATH.equals(request.path())
                || !request.query().isEmpty()) {
            return errors.invalidRoute();
        }
        final JsonNode body;
        try {
            body = JSON.readTree(request.body());
        } catch (Exception malformed) {
            return errors.exception(new IllegalArgumentException("invalid login JSON"));
        }
        if (body == null || !body.isObject() || body.size() != 2
                || !text(body.get("username")) || !text(body.get("password"))) {
            return errors.exception(new IllegalArgumentException("invalid login body"));
        }
        Instant now = clock.instant();
        if (!config.matches(body.get("username").textValue(),
                body.get("password").textValue())
                || now.isBefore(config.issuedAt()) || !now.isBefore(config.expiresAt())) {
            LOGGER.log(Level.FINE, "reference-dev 登录拒绝");
            return errors.identityVerificationFailure(new IdentityProviderError(
                    IdentityProviderError.Code.INVALID_CREDENTIAL, false));
        }
        try {
            String payload = JSON.writeValueAsString(Map.of(
                    "accessToken", config.accessToken(),
                    "expiresAt", config.expiresAt().toString()));
            LOGGER.log(Level.INFO, "reference-dev 登录成功");
            return HttpResponse.json(200, payload);
        } catch (Exception encodingFailure) {
            return errors.internalFailure();
        }
    }

    private static boolean text(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }
}
