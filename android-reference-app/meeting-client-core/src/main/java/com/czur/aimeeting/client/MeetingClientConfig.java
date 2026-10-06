package com.czur.aimeeting.client;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 组合会议的部署配置。访问令牌由 App 登录流程提供，模型 endpoint 不在本配置中。
 * callbackExecutor 用于类型化业务通知；Android App 通常提供主线程 Executor。
 * 本类不自动输出字段，避免配置日志泄露访问令牌。
 */
public final class MeetingClientConfig {
    private final String serverUrl;
    private final String accessToken;
    private final ClientIdentity clientIdentity;
    private final MeetingProfile profile;
    private final Executor callbackExecutor;

    public MeetingClientConfig(String serverUrl, String accessToken, ClientIdentity clientIdentity,
            MeetingProfile profile, Executor callbackExecutor) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.clientIdentity = Objects.requireNonNull(clientIdentity, "clientIdentity");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    /** 旧参考 App 的薄转发适配；伙伴应显式配置自己的身份和档位。 */
    public static MeetingClientConfig reference(String serverUrl, String accessToken) {
        return new MeetingClientConfig(serverUrl, accessToken, ClientIdentity.reference(),
                MeetingProfile.reference(), Runnable::run);
    }
    public String serverUrl() { return serverUrl; }
    public String accessToken() { return accessToken; }
    public ClientIdentity clientIdentity() { return clientIdentity; }
    public MeetingProfile profile() { return profile; }
    public Executor callbackExecutor() { return callbackExecutor; }
}
