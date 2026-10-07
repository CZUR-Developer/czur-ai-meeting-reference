package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.ClientIdentity;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingProfile;
import com.czur.aimeeting.client.identity.IdentityProvider;
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 参考App的集中装配入口。当前先装配身份来源，后续在这里接入结果动作、模型和窗口扩展。
 *
 * <p>默认内存Provider由Activity与会议Service共同使用。伙伴替换身份时调用
 * registerIdentityProvider，或直接替换默认初始化；实现只需返回自己后端接受的会话。
 * 登录页面、个人中心跳转和退出规则留App，不需要修改SDK里的会议编排。</p>
 *
 * <p>此处不保存密码／凭据到磁盘。进程退出后重新登录；Activity重建仍读取同一Provider。
 * Backend地址只是本次进程配置，模型端点继续由对应后端下发。</p>
 */
public final class MeetingAppAssembly {
    private static String serverUrl = "";
    private static IdentityProvider identityProvider = new ReferenceIdentityProvider();

    private MeetingAppAssembly() {}

    /**
     * 在创建会前客户端前配置部署地址。默认参考账号切换到不同后端时清除旧内存会话；
     * 自定义Provider自行决定自己的账号生命周期，通用装配不会退出系统账号。
     */
    public static synchronized void configureBackend(String backend) {
        if (!serverUrl.equals(backend) && identityProvider instanceof ReferenceIdentityProvider)
            ((ReferenceIdentityProvider) identityProvider).clearSession();
        serverUrl = Objects.requireNonNull(backend, "backend");
    }

    /** 构建期装配伙伴身份实现；在会前调用，进行中的会议继续使用其已冻结凭据。 */
    public static synchronized void registerIdentityProvider(IdentityProvider provider) {
        identityProvider = Objects.requireNonNull(provider, "provider");
    }

    public static synchronized String serverUrl() { return serverUrl; }

    /** 页面与Service共用同一个来源，但分别创建自己的客户端与串行回调队列。 */
    public static synchronized MeetingClientConfig clientConfig(Executor callbacks) {
        return new MeetingClientConfig(serverUrl, identityProvider,
                ClientIdentity.reference(), MeetingProfile.reference(), callbacks);
    }

    /** 默认参考页的“切换账号”；伙伴登录入口应实现自己的退出动作，此处不退出系统账号。 */
    public static synchronized void clearReferenceSession() {
        if (identityProvider instanceof ReferenceIdentityProvider)
            ((ReferenceIdentityProvider) identityProvider).clearSession();
    }
}
