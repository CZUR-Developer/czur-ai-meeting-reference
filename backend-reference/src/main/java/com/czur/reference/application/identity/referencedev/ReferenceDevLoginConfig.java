package com.czur.reference.application.identity.referencedev;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 参考部署的单个预置终端用户登录配置。
 *
 * <p>部署者同时配置用户名和密码才开放登录；未配置时不影响原有固定 Bearer Token
 * 接口。登录成功返回的就是同一部署已配置的演示 Token，不创建用户表或独立 Token。
 * 因为响应必须发放原文，本配置会在进程内持有该 Token；不得输出配置对象、
 * 请求正文或响应正文到日志，也不得把填写后的配置文件提交到仓库。</p>
 */
public final class ReferenceDevLoginConfig {
    public static final String USERNAME = "REFERENCE_DEV_LOGIN_USERNAME";
    public static final String PASSWORD = "REFERENCE_DEV_LOGIN_PASSWORD";

    private final String username;
    private final String password;
    private final String accessToken;
    private final Instant issuedAt;
    private final Instant expiresAt;

    private ReferenceDevLoginConfig(String username, String password, String accessToken,
            Instant issuedAt, Instant expiresAt) {
        this.username = username;
        this.password = password;
        this.accessToken = accessToken;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 读取与 {@link ReferenceDevIdentityConfig} 相同的环境快照。
     *
     * @return 未同时提供账号和密码时为 {@code null}；两者只配置一项则拒绝启动
     */
    public static ReferenceDevLoginConfig fromEnvironment(Map<String, String> environment,
            ReferenceDevIdentityConfig identity) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(identity, "identity");
        String username = environment.get(USERNAME);
        String password = environment.get(PASSWORD);
        if (username == null && password == null) return null;
        if (!identity.enabled() || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalArgumentException("reference-dev 预置登录配置不完整");
        }
        return new ReferenceDevLoginConfig(username, password,
                environment.get(ReferenceDevIdentityConfig.BEARER_TOKEN),
                Instant.parse(environment.get(ReferenceDevIdentityConfig.ISSUED_AT)),
                Instant.parse(environment.get(ReferenceDevIdentityConfig.EXPIRES_AT)));
    }

    /** 校验唯一预置账号；本类不记录账号或密码。 */
    public boolean matches(String candidateUsername, String candidatePassword) {
        return username.equals(candidateUsername) && password.equals(candidatePassword);
    }

    /** 返回既有演示身份 Provider 接受的同一个 Bearer Token。 */
    public String accessToken() { return accessToken; }

    /** 与既有演示身份凭据一致的生效时间。 */
    public Instant issuedAt() { return issuedAt; }

    /** 与既有演示身份凭据一致的失效时间。 */
    public Instant expiresAt() { return expiresAt; }

    @Override
    public String toString() { return "ReferenceDevLoginConfig[credential=REDACTED]"; }
}
