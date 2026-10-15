package com.czur.aimeeting.client;

import java.util.Objects;
import java.util.Optional;
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
    private final MeetingArtifactPublisher artifactPublisher;
    private final StoppedMeetingStore stoppedMeetingStore;

    public MeetingClientConfig(String serverUrl, String accessToken, ClientIdentity clientIdentity,
            MeetingProfile profile, Executor callbackExecutor) {
        this(serverUrl, accessToken, new ReferenceIdentityProvider(accessToken), clientIdentity,
                profile, callbackExecutor, MeetingArtifactPublisher.none(), null);
    }

    /**
     * 注册具体部署的身份来源。App可以在进入页面／登录返回时刷新；会议开始时SDK再次读取。
     * 同一个Provider可由页面与Service共用；SDK不持有Activity或调用系统登录界面。
     */
    public MeetingClientConfig(String serverUrl, IdentityProvider identityProvider,
            ClientIdentity clientIdentity, MeetingProfile profile, Executor callbackExecutor) {
        this(serverUrl, "", identityProvider, clientIdentity, profile, callbackExecutor,
                MeetingArtifactPublisher.none(), null);
    }

    private MeetingClientConfig(String serverUrl, String accessToken, IdentityProvider identityProvider,
            ClientIdentity clientIdentity, MeetingProfile profile, Executor callbackExecutor,
            MeetingArtifactPublisher artifactPublisher, StoppedMeetingStore stoppedMeetingStore) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.clientIdentity = Objects.requireNonNull(clientIdentity, "clientIdentity");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.artifactPublisher = Objects.requireNonNull(artifactPublisher, "artifactPublisher");
        this.stoppedMeetingStore = stoppedMeetingStore;
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
    public MeetingArtifactPublisher artifactPublisher() { return artifactPublisher; }
    /** 未装配时保持原内存收尾路径，无主动结果交付的会议也不会写入停止记录。 */
    public Optional<StoppedMeetingStore> stoppedMeetingStore() {
        return Optional.ofNullable(stoppedMeetingStore);
    }

    /**
     * 创建新的不可变部署配置；不改变原配置，不启用 retainAudio，也不产生上传。
     * 发布器必须只使用收到的本场 Context，不能在收尾时重新向全局身份来源取凭据。
     */
    public MeetingClientConfig withArtifactPublisher(MeetingArtifactPublisher publisher) {
        return new MeetingClientConfig(serverUrl, accessToken, identityProvider,
                clientIdentity, profile, callbackExecutor, publisher, stoppedMeetingStore);
    }

    /**
     * 可选注册应用私有停止记录。仅主动交付、尾部全部 ACK 且有同源 sealed 音频的会议
     * 保存发布前候选；不为正在采音、未确认尾部或无痕会议建立恢复记录。
     * 不改变原配置、不启用录音、不执行文件／网络操作；原 Publisher 装配保持。
     */
    public MeetingClientConfig withStoppedMeetingStore(StoppedMeetingStore store) {
        return new MeetingClientConfig(serverUrl, accessToken, identityProvider,
                clientIdentity, profile, callbackExecutor, artifactPublisher,
                Objects.requireNonNull(store, "store"));
    }

    /** 仅协调器使用：冻结本场凭据，避免账号刷新改变进行中请求所用的身份。 */
    MeetingClientConfig forSession(IdentitySession session) {
        return new MeetingClientConfig(serverUrl, session.accessToken(), identityProvider,
                clientIdentity, profile, callbackExecutor, artifactPublisher, stoppedMeetingStore);
    }
}
