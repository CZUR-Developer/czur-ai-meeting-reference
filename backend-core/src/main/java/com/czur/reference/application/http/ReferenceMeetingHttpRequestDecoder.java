package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.command.StopMeetingIntent;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 参考 REST Stop/Delete/实时 Token 续期的最小请求解码器，不验证身份或授权。
 *
 * <p>Stop 的 Bearer 只作形状检查，凭据交给既有认证应用服务验证并关闭；
 * audience/clientId 只能由服务端可信配置构造，不允许从请求中读取。Stop body
 * 必须先由 {@link MeetingHttpAdapter} 按公开空对象协议检查。Delete 只接受外层
 * 已验证的 {@link MeetingCommandContext}；调用方还必须先验证
 * {@code meeting:delete} 权限，不能把此解码器或非空 context 当作授权证明。
 * 15-02-03 完成网络认证接线前，真实网络 GET/DELETE 继续关闭式返回 401。</p>
 *
 * <p>失败仅使用固定文本，不拼接路径、Token、幂等键或正文。该类不写日志，
 * 不保留任何请求、凭据或主体引用。</p>
 */
public final class ReferenceMeetingHttpRequestDecoder implements MeetingHttpAdapter.RequestDecoder {
    private static final String PATH_PREFIX = "/v1/meetings/";

    private final String audience;
    private final String clientId;

    /** 使用可信服务端的 Token 受众和身份客户端标识创建无状态解码器。 */
    public ReferenceMeetingHttpRequestDecoder(String audience, String clientId) {
        this.audience = requireTrusted(audience);
        this.clientId = requireTrusted(clientId);
    }

    /**
     * 从 Stop 路由参数和头构造最小意图，并交付短生命周期凭据。
     *
     * <p>调用方（adapter）必须先校验 body 为省略或 JSON 空对象，并在服务调用
     * 异常时关闭返回凭据。本方法先完成所有非秘密参数校验，再创建凭据；若
     * 凭据创建后有运行时异常，会立即清除已分配的 SecretCredential。</p>
     *
     * @throws AuthenticationFailure 缺失或畸形 Bearer；统一映射为 401
     * @throws IllegalArgumentException 路由、query 或幂等头不一致；统一映射为 400
     */
    @Override
    public MeetingHttpAdapter.StopRequest decodeStop(
            HttpRequest request, String pathMeetingId, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        if (!"POST".equals(request.method()) || pathMeetingId == null
                || !request.path().equals(PATH_PREFIX + pathMeetingId + ":stop")
                || !request.query().isEmpty()) {
            throw invalid();
        }
        requireMatchingKey(request, idempotencyKey);
        StopMeetingIntent intent;
        try {
            intent = new StopMeetingIntent(pathMeetingId, idempotencyKey);
        } catch (RuntimeException invalidIntent) {
            throw invalid();
        }
        return new MeetingHttpAdapter.StopRequest(
                decodeUserBearer(request), intent);
    }

    /**
     * 仅把已匹配续期路由的用户 Bearer 转为短生命周期身份请求。
     * adapter 已先拒绝非空 body/query 和无效幂等键；本方法再次核对方法、路径
     * 与头，避免未来调用方绕过路由门禁。它不读取或接受 WSS sessionToken。
     */
    @Override
    public IdentityVerificationRequest decodeRenewRealtimeToken(
            HttpRequest request, String pathMeetingId, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        if (!"POST".equals(request.method()) || pathMeetingId == null
                || !request.path().equals(PATH_PREFIX + pathMeetingId + ":renewRealtimeToken")
                || !request.query().isEmpty() || !request.body().isEmpty()) {
            throw invalid();
        }
        requireMatchingKey(request, idempotencyKey);
        return decodeUserBearer(request);
    }

    /** Stop 与续期共用的用户 Bearer 形状与清零规则。 */
    private IdentityVerificationRequest decodeUserBearer(HttpRequest request) {
        String token = BearerAuthorization.tokenOrNull(request.header("authorization"));
        if (token == null) {
            throw new AuthenticationFailure();
        }
        byte[] temporary = token.getBytes(StandardCharsets.US_ASCII);
        SecretCredential credential = null;
        try {
            credential = SecretCredential.copyOf(temporary);
            return new IdentityVerificationRequest(
                    IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                    credential, audience, clientId);
        } catch (RuntimeException failure) {
            if (credential != null) {
                credential.close();
            }
            throw invalid();
        } finally {
            Arrays.fill(temporary, (byte) 0);
        }
    }

    /**
     * 解码已验证主体的删除请求；不验证 credential、权限或会议归属。
     *
     * <p>调用方必须先校验 {@code meeting:delete} 权限；数据库端口继续做会议
     * 归属复核。仅允许没有 body/query 的 DELETE，meetingId 和幂等键只取自
     * adapter 已匹配的路径与请求头，不能被正文覆盖。摘要独立于幂等键和主体。</p>
     */
    @Override
    public DeleteRequest decodeDelete(HttpRequest request, MeetingCommandContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        if (!"DELETE".equals(request.method()) || !request.path().startsWith(PATH_PREFIX)
                || !request.query().isEmpty() || !request.body().isEmpty()) {
            throw invalid();
        }
        String meetingId = request.path().substring(PATH_PREFIX.length());
        String key = requireKey(request.header("idempotency-key"));
        try {
            return new DeleteRequest(context, meetingId, key,
                    MeetingRequestCanonicalizer.deleteDigest(meetingId));
        } catch (RuntimeException invalidInput) {
            throw invalid();
        }
    }

    private static void requireMatchingKey(HttpRequest request, String expected) {
        if (!requireKey(request.header("idempotency-key")).equals(expected)) {
            throw invalid();
        }
    }

    private static String requireKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value;
    }

    private static String requireTrusted(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trusted identity setting is invalid");
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid meeting command request");
    }

    /** 固定文本的认证形状错误，供统一错误映射器区分 401 和业务输入 400。 */
    static final class AuthenticationFailure extends IllegalArgumentException {
        private AuthenticationFailure() {
            // 保持既有 Stop 解码器异常契约；HTTP 公共响应只使用固定 401。
            super("invalid Stop authentication");
        }
    }
}
