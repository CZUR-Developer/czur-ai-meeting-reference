package com.czur.aimeeting.client;

import java.util.Objects;
import java.util.concurrent.Executor;
import com.czur.aimeeting.client.identity.IdentityProvider;
import com.czur.aimeeting.client.identity.IdentitySession;
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider;

/**
 * 组合会议的部署配置。身份来源由App注入，模型endpoint不在本配置中。
 * 每次开始时从identityProvider读取当前会话，再形成只供本场使用的访问凭据快照。
 * 旧accessToken构造器仍可用，自动包装成参考内存身份；不接受模型地址／短期材料。
 * callbackExecutor 必须串行执行，用于类型化业务通知与 beforeStop 衔接；
 * Android App 通常提供主线程 Executor，不使用线程池造成结果/状态重排。
 * 本类不自动输出字段，避免配置日志泄露访问令牌。
 */
public final class MeetingClientConfig {
    private final String serverUrl;
    private final String accessToken;
    private final IdentityProvider identityProvider;
    private final ClientIdentity clientIdentity;
    private final MeetingProfile profile;
    private final Executor callbackExecutor;

    public MeetingClientConfig(String serverUrl, String accessToken, ClientIdentity clientIdentity,
            MeetingProfile profile, Executor callbackExecutor) {
        this(serverUrl, accessToken, new ReferenceIdentityProvider(accessToken), clientIdentity,
                profile, callbackExecutor);
    }

    /**
     * 注册具体部署的身份来源。App可以在进入页面／登录返回时刷新；会议开始时SDK再次读取。
     * 同一个Provider可由页面与Service共用；SDK不持有Activity或调用系统登录界面。
     */
    public MeetingClientConfig(String serverUrl, IdentityProvider identityProvider,
            ClientIdentity clientIdentity, MeetingProfile profile, Executor callbackExecutor) {
        this(serverUrl, "", identityProvider, clientIdentity, profile, callbackExecutor);
    }

    private MeetingClientConfig(String serverUrl, String accessToken, IdentityProvider identityProvider,
            ClientIdentity clientIdentity, MeetingProfile profile, Executor callbackExecutor) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
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
    /** 旧构造器的初始token或本场内部快照；新调用方通过refreshIdentity读取身份。 */
    public String accessToken() { return accessToken; }
    public IdentityProvider identityProvider() { return identityProvider; }
    public ClientIdentity clientIdentity() { return clientIdentity; }
    public MeetingProfile profile() { return profile; }
    public Executor callbackExecutor() { return callbackExecutor; }

    /** 仅协调器使用：冻结本场凭据，避免账号刷新改变进行中请求所用的身份。 */
    MeetingClientConfig forSession(IdentitySession session) {
        return new MeetingClientConfig(serverUrl, session.accessToken(), identityProvider,
                clientIdentity, profile, callbackExecutor);
    }
}
