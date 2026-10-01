package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.VerifiedIdentity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * GET/list/DELETE 的窄身份上下文解析器；不监听网络、不处理会议业务。
 *
 * <p>audience、clientId、deploymentScope 均由可信服务端装配提供，不读取
 * URL、Header 或 body 中同名值。Bearer 先按已冻结的 RFC 6750 形状检查，
 * 再由 {@link IdentityProvider} 真正验证；凭据在返回或失败前关闭并清零临时
 * 字节数组。结果只携带已验证身份或统一脱敏 HTTP 拒绝响应，不携带 Token。</p>
 *
 * <p>读取入口只建立身份，不凭空增加未冻结的 read 权限码。删除入口在返回
 * context 前要求 {@link MeetingPermissions#DELETE}；会议归属仍由已有删除端口
 * 按数据库权威事实检查。本类尚未连接 JDK HTTP server；网络接线留 15-02-03b。
 * 不支持 OpenAPI 中可选的 resultAccessAuth 安全方案。</p>
 */
public final class AuthenticatedMeetingHttpContextResolver {
    private final String deploymentScope;
    private final String audience;
    private final String clientId;
    private final IdentityProvider identityProvider;
    private final HttpErrorMapper errors;

    /**
     * 创建单部署参考入口；配置值必须来自服务端，不能来自本次请求。
     *
     * @throws IllegalArgumentException 任一可信配置为空时启动即失败
     */
    public AuthenticatedMeetingHttpContextResolver(
            String deploymentScope, String audience, String clientId,
            IdentityProvider identityProvider, HttpErrorMapper errors) {
        this.deploymentScope = requireTrusted(deploymentScope);
        this.audience = requireTrusted(audience);
        this.clientId = requireTrusted(clientId);
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** 验证 GET/list 凭据并返回可信上下文；无业务查询、会议归属或结果访问逻辑。 */
    public Resolution forRead(HttpRequest request) {
        return resolve(request, "GET", false);
    }

    /** 验证 DELETE 凭据和 {@code meeting:delete} 权限；尚不调用删除端口。 */
    public Resolution forDelete(HttpRequest request) {
        return resolve(request, "DELETE", true);
    }

    private Resolution resolve(HttpRequest request, String method, boolean requireDelete) {
        Objects.requireNonNull(request, "request");
        if (!method.equals(request.method())) {
            return new Rejected(errors.exception(
                    new IllegalArgumentException("HTTP method is invalid")));
        }
        String token = BearerAuthorization.tokenOrNull(request.header("authorization"));
        if (token == null) {
            return new Rejected(errors.missingContext());
        }
        byte[] temporary = token.getBytes(StandardCharsets.US_ASCII);
        try {
            try (SecretCredential credential = SecretCredential.copyOf(temporary)) {
                IdentityVerificationRequest verification = new IdentityVerificationRequest(
                        IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                        credential, audience, clientId);
                IdentityProviderResult<VerifiedIdentity> result = Objects.requireNonNull(
                        identityProvider.verifyCredential(verification), "identity result");
                if (!result.isSuccess()) {
                    return new Rejected(errors.identityVerificationFailure(result.errorOrThrow()));
                }
                VerifiedIdentity identity = Objects.requireNonNull(
                        result.valueOrThrow(), "verified identity");
                if (requireDelete && !identity.hasPermission(MeetingPermissions.DELETE)) {
                    return new Rejected(errors.identityVerificationFailure(
                            new IdentityProviderError(
                                    IdentityProviderError.Code.PERMISSION_DENIED, false)));
                }
                return new Ready(new MeetingCommandContext(deploymentScope, identity));
            }
        } catch (RuntimeException unexpected) {
            // Provider 异常或非法结果均不传播动态正文；try-with-resources 已关凭据。
            return new Rejected(errors.internalFailure());
        } finally {
            Arrays.fill(temporary, (byte) 0);
        }
    }

    private static String requireTrusted(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trusted identity setting is invalid");
        }
        return value;
    }

    /** 解析结果只能是可信上下文或公开错误响应，不包含凭据引用。 */
    public sealed interface Resolution permits Ready, Rejected {
    }

    /** 身份与必要权限已验证；归属和业务校验仍由后续端口执行。 */
    public record Ready(MeetingCommandContext context) implements Resolution {
        public Ready {
            Objects.requireNonNull(context, "context");
        }
    }

    /** 统一错误 envelope，调用方不得继续访问会议端口。 */
    public record Rejected(HttpResponse response) implements Resolution {
        public Rejected {
            Objects.requireNonNull(response, "response");
        }
    }
}
