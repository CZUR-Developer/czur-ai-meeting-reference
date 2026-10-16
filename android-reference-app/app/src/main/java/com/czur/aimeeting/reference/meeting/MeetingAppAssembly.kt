package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.ClientIdentity
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingProfile
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.ModelAdapterRegistry
import com.czur.aimeeting.client.result.ResultActionRegistry
import com.czur.aimeeting.client.result.ResultActionPlugin
import com.czur.aimeeting.client.result.ResultOptions
import com.czur.aimeeting.reference.AppResultActionPlugin
import com.czur.aimeeting.reference.WebResultActionPlugin
import com.czur.aimeeting.reference.MeetingPageViews
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory
import com.czur.aimeeting.reference.ui.OverlayViewFactory
import com.czur.aimeeting.client.identity.IdentityProvider
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * 参考 App 集中装配身份、模型、结果动作和页面/窗口工厂；默认只开放 Web。
 * 身份 Provider 由 Activity 和 Service 共用，伙伴替换 Provider 即可，不必改 SDK 编排。
 * 密码和凭据不写磁盘；后端地址只配置当前进程，模型端点继续由后端下发。
 */
object MeetingAppAssembly {
    private var serverUrl = ""
    private var resultWebUrl = ""
    private var identityProvider: IdentityProvider = ReferenceIdentityProvider()
    private var modelAdapters: ModelAdapterRegistry? = null
    private var resultActions: List<AppResultActionPlugin> = listOf(WebResultActionPlugin())
    private var resultRegistry = ResultActionRegistry(resultActions)
    private var pendingMeeting: PreparedMeeting? = null
    private val pendingResultUi = ArrayDeque<AppResultActionPlugin.UiOperation>()
    private var resultUiObserver: Runnable? = null
    private var overlayFactory: MeetingOverlayFactory = OverlayViewFactory()
    private var pageFactory: MeetingPageViews.Factory = MeetingPageViews.DefaultFactory()

    /** 会前构建期登记工厂，每场返回新的 Adapter，不在会中修改注册表。 */
    @JvmStatic @Synchronized fun registerModelAdapters(adapters: ModelAdapterRegistry) { modelAdapters = adapters }
    @JvmStatic @Synchronized fun modelAdapters(): ModelAdapterRegistry = modelAdapters ?: ModelAdapterRegistry().also { modelAdapters = it }
    @JvmStatic @Synchronized fun registerPageFactory(factory: MeetingPageViews.Factory) { pageFactory = factory }
    @JvmStatic @Synchronized fun pageFactory() = pageFactory
    @JvmStatic @Synchronized fun configureResultWeb(url: String) { resultWebUrl = url }
    @JvmStatic @Synchronized fun resultWebUrl() = resultWebUrl

    /** 只替换显示工厂，不持有 Activity，不重开音源或会议。 */
    @JvmStatic @Synchronized fun registerOverlayFactory(factory: MeetingOverlayFactory) { overlayFactory = factory }
    @JvmStatic @Synchronized fun overlayFactory() = overlayFactory

    /** 默认内存账号切换后端时清除旧会话，自定义 Provider 自行决定账号生命周期。 */
    @JvmStatic @Synchronized fun configureBackend(backend: String) {
        if (serverUrl != backend) (identityProvider as? ReferenceIdentityProvider)?.clearSession()
        serverUrl = backend
    }
    @JvmStatic @Synchronized fun registerIdentityProvider(provider: IdentityProvider) { identityProvider = provider }
    @JvmStatic @Synchronized fun serverUrl() = serverUrl
    @JvmStatic @Synchronized fun registerResultActions(plugins: List<AppResultActionPlugin>) {
        resultActions = java.util.List.copyOf(plugins)
        resultRegistry = ResultActionRegistry(resultActions)
    }
    @JvmStatic @Synchronized fun resultActions() = resultActions
    @JvmStatic @Synchronized fun resultRegistry() = resultRegistry

    /**
     * 唯一会前 Hook 检查开放清单与后端服务，并冻结插件执行闭包。
     * 通用装配不解释插件参数，不持有设置 View。
     */
    @JvmStatic @Synchronized fun prepare(setup: MeetingSetup, discovery: MeetingDiscovery,
        preparedPlugin: ResultActionPlugin?
    ): StartPreparation {
        if (setup.resultOptions().policy() == ResultOptions.Policy.NO_ACTIVE_DELIVERY) {
            return StartPreparation(PreparedMeeting(setup, null, ResultActionRegistry.serviceCapabilities(discovery)), "")
        }
        val id = setup.resultOptions().actionId()
        if (preparedPlugin == null || id != preparedPlugin.id() || resultActions.none { it.id() == id })
            return StartPreparation(null, "当前应用未装配所选结果动作")
        val action = ResultActionRegistry(listOf(preparedPlugin)).prepare(id, setup.toOptions(), discovery)
        return if (action.allowed()) StartPreparation(PreparedMeeting(setup, action.action(),
            ResultActionRegistry.serviceCapabilities(discovery)), "") else StartPreparation(null, action.message())
    }

    /** 只在进程内交接本场准备结果，不持有 View、凭据或恢复数据。 */
    class PreparedMeeting(private val setup: MeetingSetup, private val action: ResultActionRegistry.PreparedAction?,
        serviceCapabilities: Set<String>) {
        private val serviceCapabilities = java.util.Set.copyOf(serviceCapabilities)
        fun setup() = setup
        fun action() = action
        fun serviceCapabilities() = serviceCapabilities
    }
    data class StartPreparation(private val meeting: PreparedMeeting?, private val message: String) {
        fun meeting() = meeting
        fun message() = message
        fun allowed() = meeting != null
    }

    @JvmStatic @Synchronized fun handoff(meeting: PreparedMeeting) { pendingMeeting = meeting }
    @JvmStatic @Synchronized fun consumePreparedMeeting(): PreparedMeeting? = pendingMeeting.also { pendingMeeting = null }
    @JvmStatic @Synchronized fun clearPreparedMeeting() { pendingMeeting = null }

    /**
     * 主线程暂存已准备的平台动作，Service 退出不清队列。通知只负责唤起可见消费者；
     * 消费时先移出再执行，避免重复恢复重复打开；进程退出不保留。
     */
    @JvmStatic fun queueResultUiOperation(operation: AppResultActionPlugin.UiOperation) {
        val notify = synchronized(MeetingAppAssembly::class.java) { pendingResultUi.add(operation); resultUiObserver }
        notify?.run()
    }
    @JvmStatic @Synchronized fun consumeResultUiOperation(): Runnable? = pendingResultUi.poll()
    @JvmStatic @Synchronized fun observeResultUi(notify: Runnable) { resultUiObserver = notify }
    @JvmStatic @Synchronized fun unobserveResultUi(notify: Runnable) { if (resultUiObserver === notify) resultUiObserver = null }

    /** 页面和 Service 共用身份来源，分别创建自己的客户端与串行回调队列。 */
    @JvmStatic @Synchronized fun clientConfig(callbacks: Executor) = MeetingClientConfig(serverUrl, identityProvider,
        ClientIdentity.reference(), MeetingProfile.reference(), callbacks)
    @JvmStatic @Synchronized fun clearReferenceSession() { (identityProvider as? ReferenceIdentityProvider)?.clearSession() }
}
