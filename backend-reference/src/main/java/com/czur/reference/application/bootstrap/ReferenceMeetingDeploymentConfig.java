package com.czur.reference.application.bootstrap;

import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;
import com.czur.reference.application.identity.referencedev.ReferenceDevLoginConfig;
import java.util.Map;
import java.util.Optional;

/**
 * 参考应用的默认配置包装；只有此模块读取演示身份和预置登录设置。
 * 会议、数据库及模型配置全部复用共享核心，避免两份配置解析算法。
 */
public final class ReferenceMeetingDeploymentConfig extends MeetingDeploymentConfig {
    private final ReferenceDevIdentityConfig identity;
    private final ReferenceDevLoginConfig login;

    private ReferenceMeetingDeploymentConfig(MeetingDeploymentConfig core,
            ReferenceDevIdentityConfig identity, ReferenceDevLoginConfig login) {
        super(core);
        this.identity = identity;
        this.login = login;
    }

    /** 默认参考入口显式要求启用演示身份，并从同一环境快照读取登录配置。 */
    public static ReferenceMeetingDeploymentConfig fromEnvironment(Map<String, String> source) {
        Map<String, String> values = Map.copyOf(source);
        ReferenceDevIdentityConfig identity = ReferenceDevIdentityConfig.fromEnvironment(values);
        if (!identity.enabled()) {
            throw new IllegalArgumentException("invalid " + ReferenceDevIdentityConfig.ENABLED);
        }
        var core = MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(values,
                values.get(ReferenceDevIdentityConfig.AUDIENCE),
                values.get(ReferenceDevIdentityConfig.CLIENT_ID));
        return new ReferenceMeetingDeploymentConfig(core, identity,
                ReferenceDevLoginConfig.fromEnvironment(values, identity));
    }

    /** 兼容既有外部身份调用；生产宿主可直接使用中性的MeetingDeploymentConfig。 */
    public static ReferenceMeetingDeploymentConfig fromEnvironmentWithExternalIdentity(
            Map<String, String> source, String audience, String clientId) {
        return new ReferenceMeetingDeploymentConfig(
                MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(source, audience, clientId),
                ReferenceDevIdentityConfig.fromEnvironment(Map.of()), null);
    }

    public ReferenceDevIdentityConfig identity() { return identity; }
    public Optional<ReferenceDevLoginConfig> login() { return Optional.ofNullable(login); }
}
