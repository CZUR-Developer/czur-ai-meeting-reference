package com.czur.reference.application.identity.referencedev;

import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.VerifiedIdentity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 仅供本地演示的固定 Bearer Token 身份 Provider。
 *
 * <p>该实现没有用户库、登录接口、Token 刷新、管理员后台或生产信任链。它只在
 * {@link ReferenceDevIdentityConfig} 显式启用后，把一个固定 Token 映射到配置中
 * 独立声明的稳定主体和权限，用于参考后端后续编排的可重复测试。</p>
 *
 * <p>验证顺序固定为：Token 摘要、audience/client、签发时间、过期时间。错误
 * Token 永远只得到 {@code INVALID_CREDENTIAL}，不会通过返回码或日志判断配置的
 * audience、client 或时间窗。每次调用创建独立 SHA-256 实例，并使用
 * {@link MessageDigest#isEqual(byte[], byte[])} 比较摘要；调用结束后清零本次摘要
 * 和配置摘要副本。Provider 不关闭调用方持有的 SecretCredential。</p>
 */
public final class ReferenceDevIdentityProvider implements IdentityProvider {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceDevIdentityProvider.class.getName());

    private final ReferenceDevIdentityConfig config;
    private final Clock clock;

    /** 使用系统 UTC 时钟创建 Provider。测试和确定性装配应优先使用时钟构造器。 */
    public ReferenceDevIdentityProvider(ReferenceDevIdentityConfig config) {
        this(config, Clock.systemUTC());
    }

    /**
     * 使用显式时钟创建 Provider。
     *
     * @param config 已完成 fail-fast 校验的配置，也可以是默认禁用配置
     * @param clock 只用于判断配置凭据的生效和过期边界
     */
    public ReferenceDevIdentityProvider(ReferenceDevIdentityConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        LOGGER.log(Level.INFO, "reference-dev 身份 Provider 已创建 enabled={0}", config.enabled());
    }

    @Override
    public IdentityProviderResult<VerifiedIdentity> verifyCredential(
            IdentityVerificationRequest request) {
        if (!config.enabled()) {
            return reject(IdentityProviderError.Code.IDENTITY_DISABLED, "PROVIDER_DISABLED");
        }
        if (request == null) {
            return reject(IdentityProviderError.Code.INVALID_REQUEST, "REQUEST_MISSING");
        }
        if (request.credentialType()
                != IdentityVerificationRequest.CredentialType.BEARER_TOKEN) {
            return reject(IdentityProviderError.Code.INVALID_REQUEST, "CREDENTIAL_TYPE_UNSUPPORTED");
        }

        TokenCheck tokenCheck = verifyToken(request);
        if (tokenCheck == TokenCheck.INVALID) {
            return reject(IdentityProviderError.Code.INVALID_CREDENTIAL, "TOKEN_MISMATCH");
        }
        if (tokenCheck == TokenCheck.INTERNAL_ERROR) {
            return reject(IdentityProviderError.Code.INTERNAL, "DIGEST_UNAVAILABLE");
        }

        // 只有通过固定 Token 校验后才检查下面的声明，避免错误 Token 探测有效期
        // 或受信 audience/client。日志只保留稳定原因码，不记录请求字段值。
        if (!config.audience().equals(request.audience())) {
            return reject(IdentityProviderError.Code.INVALID_CREDENTIAL, "AUDIENCE_MISMATCH");
        }
        if (!config.clientId().equals(request.clientId())) {
            return reject(IdentityProviderError.Code.INVALID_CREDENTIAL, "CLIENT_MISMATCH");
        }

        Instant now = clock.instant();
        if (now.isBefore(config.issuedAt())) {
            return reject(IdentityProviderError.Code.INVALID_CREDENTIAL, "CREDENTIAL_NOT_ACTIVE");
        }
        if (!now.isBefore(config.expiresAt())) {
            return reject(IdentityProviderError.Code.CREDENTIAL_EXPIRED, "CREDENTIAL_EXPIRED");
        }

        VerifiedIdentity identity = new VerifiedIdentity(
                config.subject(),
                config.roles(),
                config.permissions(),
                config.issuedAt(),
                config.expiresAt(),
                config.credentialId());
        LOGGER.log(Level.FINE, "reference-dev 身份验证成功 provider=reference-dev");
        return IdentityProviderResult.success(identity);
    }

    private TokenCheck verifyToken(IdentityVerificationRequest request) {
        byte[] expectedDigest = config.copyCredentialDigest();
        try {
            return request.credential().useBytes(rawCredential -> {
                byte[] actualDigest = null;
                try {
                    actualDigest = MessageDigest.getInstance("SHA-256").digest(rawCredential);
                    return MessageDigest.isEqual(expectedDigest, actualDigest)
                            ? TokenCheck.MATCH
                            : TokenCheck.INVALID;
                } catch (NoSuchAlgorithmException impossible) {
                    return TokenCheck.INTERNAL_ERROR;
                } finally {
                    if (actualDigest != null) {
                        Arrays.fill(actualDigest, (byte) 0);
                    }
                }
            });
        } catch (IllegalStateException destroyedCredential) {
            return TokenCheck.INVALID;
        } finally {
            Arrays.fill(expectedDigest, (byte) 0);
        }
    }

    private static <T> IdentityProviderResult<T> reject(
            IdentityProviderError.Code code, String reasonCode) {
        LOGGER.log(Level.FINE, "reference-dev 身份验证拒绝 reason={0}", reasonCode);
        return IdentityProviderResult.failure(new IdentityProviderError(code, false));
    }

    private enum TokenCheck {
        MATCH,
        INVALID,
        INTERNAL_ERROR
    }
}
