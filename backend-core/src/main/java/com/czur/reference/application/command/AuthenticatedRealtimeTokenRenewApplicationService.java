package com.czur.reference.application.command;

import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.VerifiedIdentity;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 用终端用户 Bearer 续期其自有会议的短期实时凭据。
 *
 * <p>身份由服务端配置的 Provider 验证；会议归属、V013 首次事实、状态和同键
 * 重放由原子持久化端口再次核对。此处不接受 WSS sessionToken 代替用户 Bearer，
 * 不调用计费、模型或存储，也不缓存已签发凭据。输入凭据在所有分支关闭。</p>
 */
public final class AuthenticatedRealtimeTokenRenewApplicationService {
    private static final Logger LOGGER = Logger.getLogger(
            AuthenticatedRealtimeTokenRenewApplicationService.class.getName());

    private final String deploymentScope;
    private final IdentityProvider identityProvider;
    private final RenewRealtimeTokenUnitOfWork renewal;

    /** 三项依赖均由可信装配根注入；本类不持有数据库或网络生命周期。 */
    public AuthenticatedRealtimeTokenRenewApplicationService(String deploymentScope,
            IdentityProvider identityProvider, RenewRealtimeTokenUnitOfWork renewal) {
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope is required");
        }
        this.deploymentScope = deploymentScope;
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.renewal = Objects.requireNonNull(renewal, "renewal");
    }

    /**
     * @param verification 由 HTTP 解码器生成的用户 Bearer 请求，调用后凭据必被关闭
     * @param meetingId 仅取自已匹配的路径
     * @param idempotencyKey 仅取自已校验的请求头
     * @return 稳定的身份失败、持久化结果或内部故障；绝不返回内存候选 Token
     */
    public Result renew(IdentityVerificationRequest verification,
            String meetingId, String idempotencyKey) {
        Objects.requireNonNull(verification, "verification");
        try {
            IdentityProviderResult<VerifiedIdentity> verified;
            try {
                verified = Objects.requireNonNull(
                        identityProvider.verifyCredential(verification), "identity result");
            } catch (RuntimeException failure) {
                return internal("IDENTITY");
            }
            if (!verified.isSuccess()) {
                return new IdentityFailure(verified.errorOrThrow());
            }
            try {
                MeetingCommandContext context = new MeetingCommandContext(
                        deploymentScope, verified.valueOrThrow());
                RenewRealtimeTokenUnitOfWork.Result result = Objects.requireNonNull(
                        renewal.renew(new RenewRealtimeTokenUnitOfWork.Request(
                                context, meetingId, idempotencyKey)), "renewal result");
                return new Renewal(result);
            } catch (RuntimeException failure) {
                return internal("RENEWAL");
            }
        } finally {
            verification.credential().close();
        }
    }

    private static InternalFailure internal(String stage) {
        LOGGER.log(Level.WARNING, "实时 Token 续期应用故障 stage={0}", stage);
        return new InternalFailure();
    }

    /** 不在身份失败或内部故障分支携带 Token。 */
    public sealed interface Result permits IdentityFailure, Renewal, InternalFailure { }

    /** Provider 的稳定错误，不携带用户 Bearer。 */
    public record IdentityFailure(IdentityProviderError error) implements Result {
        public IdentityFailure { Objects.requireNonNull(error, "error"); }
    }

    /** 仅封装已由持久事务端口返回的结果。 */
    public record Renewal(RenewRealtimeTokenUnitOfWork.Result result) implements Result {
        public Renewal { Objects.requireNonNull(result, "result"); }
    }

    /** 异常正文被丢弃后的固定故障。 */
    public record InternalFailure() implements Result { }
}
