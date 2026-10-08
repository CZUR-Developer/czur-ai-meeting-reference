package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.ClientIdentity;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingProfile;
import com.czur.aimeeting.client.MeetingDiscovery;
import com.czur.aimeeting.client.ModelAdapterRegistry;
import com.czur.aimeeting.client.result.ResultActionRegistry;
import com.czur.aimeeting.client.result.ResultActionPlugin;
import com.czur.aimeeting.client.result.ResultOptions;
import com.czur.aimeeting.reference.AppResultActionPlugin;
import com.czur.aimeeting.reference.WebResultActionPlugin;
import com.czur.aimeeting.reference.MeetingPageViews;
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory;
import com.czur.aimeeting.reference.ui.OverlayViewFactory;
import com.czur.aimeeting.client.identity.IdentityProvider;
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 参考App的集中装配入口：身份、模型协议、结果动作和页面／窗口工厂，默认只开放网页查看。
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
    private static String resultWebUrl = "";
    private static IdentityProvider identityProvider = new ReferenceIdentityProvider();
    // 运行工厂首次取用时才创建默认表，单独装配页面／窗口不提前加载模型协议类。
    private static ModelAdapterRegistry modelAdapters;
    // 替换提示例：List.of(new CompletionNoticeAction())；PDF／邮件也需在此显式注册。
    private static List<AppResultActionPlugin> resultActions = List.of(new WebResultActionPlugin());
    private static ResultActionRegistry resultRegistry = new ResultActionRegistry(resultActions);
    private static PreparedMeeting pendingMeeting;
    private static MeetingOverlayFactory overlayFactory = new OverlayViewFactory();
    private static MeetingPageViews.Factory pageFactory = new MeetingPageViews.DefaultFactory();

    private MeetingAppAssembly() {}

    /**
     * 构建期装配模型协议注册表；在首次创建会前／Service客户端前完成登记。
     * Registry保存工厂，每次创建须返回新的Adapter；同ID可替换默认工厂。
     * 注册能力只表示可解析格式，模型地址、材料及本场授权继续由对应后端下发。
     * 替换装配只影响随后创建的客户端，已有客户端保留原注册表；会中不得修改该表。
     */
    public static synchronized void registerModelAdapters(ModelAdapterRegistry adapters) {
        modelAdapters = Objects.requireNonNull(adapters, "adapters");
    }

    /** 会前与Service运行工厂共用此表；未注册时创建内置默认表，不创建某场Adapter。 */
    public static synchronized ModelAdapterRegistry modelAdapters() {
        if (modelAdapters == null) modelAdapters = new ModelAdapterRegistry();
        return modelAdapters;
    }

    /** 会前登记App页面工厂；只替换View，新Activity继续绑定同一身份、能力及Service链。 */
    public static synchronized void registerPageFactory(MeetingPageViews.Factory factory) {
        pageFactory = Objects.requireNonNull(factory, "factory");
    }
    public static synchronized MeetingPageViews.Factory pageFactory() { return pageFactory; }

    /** 本进程部署配置；Web插件在点击开始时冻结，已准备会议保留原同场执行闭包。 */
    public static synchronized void configureResultWeb(String url) {
        resultWebUrl = Objects.requireNonNull(url, "url");
    }
    public static synchronized String resultWebUrl() { return resultWebUrl; }

    /**
     * 构建期替换窗口内容／策略，例如有 UI AAR 时注册 new SingleFormOverlayFactory()。
     * 在首次显示前调用；已经创建的窗口继续使用自己的工厂句柄，不重开会议或音源。
     * 工厂不应持有 Activity 或会议凭据，提醒、权限和前台 Service 仍归 App。
     */
    public static synchronized void registerOverlayFactory(MeetingOverlayFactory factory) {
        overlayFactory = Objects.requireNonNull(factory, "factory");
    }

    /** 默认来自当前 basic-ui／meeting-ui 构建的工厂，不反射加载闭源类。 */
    public static synchronized MeetingOverlayFactory overlayFactory() { return overlayFactory; }

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

    /** 构建期替换完整开放清单；会中不动态增减。已准备会议继续使用自己的冻结闭包。 */
    public static synchronized void registerResultActions(List<? extends AppResultActionPlugin> plugins) {
        resultActions = List.copyOf(plugins);
        resultRegistry = new ResultActionRegistry(resultActions);
    }

    public static synchronized List<AppResultActionPlugin> resultActions() { return resultActions; }
    public static synchronized ResultActionRegistry resultRegistry() { return resultRegistry; }

    /**
     * 只由会前Hook调用：检查当前构建清单／部署服务，冻结设置插件产生的执行闭包。
     * preparedPlugin不含View；通用装配不解析插件自己的参数或接收者状态。
     */
    public static synchronized StartPreparation prepare(MeetingSetup setup, MeetingDiscovery discovery,
            ResultActionPlugin preparedPlugin) {
        if (setup.resultOptions().policy() == ResultOptions.Policy.NO_ACTIVE_DELIVERY)
            return new StartPreparation(new PreparedMeeting(setup, null,
                    ResultActionRegistry.serviceCapabilities(discovery)), "");
        String id = setup.resultOptions().actionId();
        if (preparedPlugin == null || !id.equals(preparedPlugin.id())
                || resultActions.stream().noneMatch(plugin -> plugin.id().equals(id)))
            return new StartPreparation(null, "当前应用未装配所选结果动作");
        var action = new ResultActionRegistry(List.of(preparedPlugin)).prepare(id, setup.toOptions(), discovery);
        return action.allowed() ? new StartPreparation(new PreparedMeeting(setup, action.action(),
                ResultActionRegistry.serviceCapabilities(discovery)), "") : new StartPreparation(null, action.message());
    }

    /** 本场快照只在当前进程交接，不保存Activity、平台View、凭据或进程恢复状态。 */
    public record PreparedMeeting(MeetingSetup setup, ResultActionRegistry.PreparedAction action,
            Set<String> serviceCapabilities) {
        public PreparedMeeting { serviceCapabilities = Set.copyOf(serviceCapabilities); }
    }
    public record StartPreparation(PreparedMeeting meeting, String message) {
        public boolean allowed() { return meeting != null; }
    }

    /** 屏幕授权返回后交同一对象，Service只消费一次，不再次prepare。 */
    public static synchronized void handoff(PreparedMeeting meeting) { pendingMeeting = meeting; }
    public static synchronized PreparedMeeting consumePreparedMeeting() {
        PreparedMeeting meeting = pendingMeeting;
        pendingMeeting = null;
        return meeting;
    }
    /** 取消授权／启动失败时清除尚未消费的单场交接。 */
    public static synchronized void clearPreparedMeeting() { pendingMeeting = null; }

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
