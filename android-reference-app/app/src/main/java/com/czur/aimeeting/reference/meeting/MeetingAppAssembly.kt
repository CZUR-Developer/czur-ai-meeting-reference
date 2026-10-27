package com.czur.aimeeting.reference.meeting

import android.content.Context
import android.util.Log
import com.czur.aimeeting.client.ClientIdentity
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.HttpMeetingArtifactPublisher
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
import com.czur.aimeeting.client.identity.LoginIdentityProvider
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider
import com.czur.aimeeting.reference.identity.PersistentReferenceIdentityProvider
import com.czur.aimeeting.reference.identity.AppLoginEntry
import com.czur.aimeeting.reference.identity.DefaultAppLoginEntry
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * 参考 App 集中装配身份、模型、结果动作和页面/窗口工厂；默认只开放 Web。
 * 身份 Provider 由 Activity 和 Service 共用，伙伴替换 Provider 即可，不必改 SDK 编排。
 * 密码不保存，会话保存由身份插件负责；默认地址随 APK 编译配置注入。
 * 登录交互入口与身份数据来源分开替换，模型端点继续由后端下发。
 */
object MeetingAppAssembly {
    private const val TAG = "MeetingAppAssembly"
    private var serverUrl = ""
    private const val PLACEHOLDER_BACKEND_HOST = "replace-with-your-backend"
    private const val PLACEHOLDER_RESULT_WEB_HOST = "replace-with-your-result-web"
    private var resultWebUrl = ""
    private var identityProvider: IdentityProvider = ReferenceIdentityProvider()
    private var loginEntry: AppLoginEntry = DefaultAppLoginEntry
    private var preferences: android.content.SharedPreferences? = null
    private var initialized = false
    private var modelAdapters: ModelAdapterRegistry? = null
    private var resultActions: List<AppResultActionPlugin> = listOf(WebResultActionPlugin())
    private var resultRegistry = ResultActionRegistry(resultActions)
    private var pendingMeeting: PreparedMeeting? = null
    private val pendingResultUi = ArrayDeque<AppResultActionPlugin.UiOperation>()
    private var resultUiObserver: Runnable? = null
    private var overlayFactory: MeetingOverlayFactory = OverlayViewFactory()
    private var pageFactory: MeetingPageViews.Factory = MeetingPageViews.DefaultFactory()
    private var applicationContext: Context? = null
    private var recoveryStore: ReferenceMeetingRecoveryStore? = null
    /**
     * 当前进程里正在进行的会议；从开始保留到 READY／终态销毁，收尾中也算本进程所有。
     * 页面重进触发扫描时据此跳过同一场，不得另起一条续办链。
     */
    @Volatile private var activeMeetingId: String? = null
    private val recoveryScanning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var stoppedStore: ReferenceStoppedMeetingStore? = null

    /**
     * 页面首次启动时调用一次，以 ReferenceDeployment 的代码配置装配后端和结果 Web。
     *
     * 旧 backend_url 只用于判断已保存身份属于哪个后端，不能覆盖编译值；同一后端
     * 保留有效登录，切换后端时清除旧会话。旧的结果页设置完全不参与装配，单独更换
     * 结果页不会注销账号。伙伴可在初始化后通过 registerIdentityProvider 等开发者
     * 接口替换装配，终端用户不需要也没有手填 URL 的界面。
     */
    @JvmStatic @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val application = context.applicationContext
        applicationContext = application
        recoveryStore = ReferenceMeetingRecoveryStore(application)
        stoppedStore = ReferenceStoppedMeetingStore(application)
        preferences = application.getSharedPreferences("czur_reference_settings", Context.MODE_PRIVATE)
        identityProvider = PersistentReferenceIdentityProvider(application)
        applyBackend(ReferenceDeployment.BACKEND_URL, preferences?.getString(KEY_BACKEND_URL, "").orEmpty())
        resultWebUrl = ReferenceDeployment.RESULT_WEB_URL
        initialized = true
    }

    /** 会前构建期登记工厂，每场返回新的 Adapter，不在会中修改注册表。 */
    @JvmStatic @Synchronized fun registerModelAdapters(adapters: ModelAdapterRegistry) { modelAdapters = adapters }
    @JvmStatic @Synchronized fun modelAdapters(): ModelAdapterRegistry = modelAdapters ?: ModelAdapterRegistry().also { modelAdapters = it }
    @JvmStatic @Synchronized fun registerPageFactory(factory: MeetingPageViews.Factory) { pageFactory = factory }
    @JvmStatic @Synchronized fun pageFactory() = pageFactory
    /** 开发者装配／测试接缝；默认 App 使用编译值，本接口不保存终端用户地址偏好。 */
    @JvmStatic @Synchronized fun configureResultWeb(url: String) { resultWebUrl = url }
    @JvmStatic @Synchronized fun resultWebUrl() = resultWebUrl

    /** 只替换显示工厂，不持有 Activity，不重开音源或会议。 */
    @JvmStatic @Synchronized fun registerOverlayFactory(factory: MeetingOverlayFactory) { overlayFactory = factory }
    @JvmStatic @Synchronized fun overlayFactory() = overlayFactory

    /**
     * 开发者在创建客户端前替换后端的装配接缝，参考页面没有运行时配置入口。
     * 默认登录插件切换后端时清旧会话；只读身份插件仍自行负责账号生命周期。
     */
    @JvmStatic @Synchronized fun configureBackend(backend: String) { applyBackend(backend, serverUrl) }

    /** 仅比较账号归属时忽略尾斜杠；请求地址和结果页的 path/query/fragment 不作改写。 */
    private fun applyBackend(backend: String, previousBackend: String) {
        if (previousBackend.trimEnd('/') != backend.trimEnd('/'))
            (identityProvider as? LoginIdentityProvider)?.clearSession()
        serverUrl = backend
        preferences?.edit()?.putString(KEY_BACKEND_URL, backend)?.apply()
    }
    @JvmStatic @Synchronized fun registerIdentityProvider(provider: IdentityProvider) { identityProvider = provider }
    /** 由 App 装配登录交互；只保存入口，不保存 Activity 或默认表单回调。 */
    @JvmStatic @Synchronized fun registerLoginEntry(entry: AppLoginEntry) { loginEntry = entry }
    @JvmStatic @Synchronized fun loginEntry() = loginEntry
    @JvmStatic @Synchronized fun serverUrl() = serverUrl

    /**
     * 只读页面（历史会议等）统一从这里取身份，不再自己 new 一个 Provider。
     * 伙伴替换的 IdentityProvider 因此对历史回看同样生效。
     */
    @JvmStatic @Synchronized fun currentIdentity(): IdentityResult =
        identityProvider?.currentIdentity() ?: IdentityResult.loginRequired("请先登录")

    /** 占位地址等同于未配置：构建脚本会警告，页面也要给出可执行的提示而不是普通网络错误。 */
    @JvmStatic @Synchronized fun isBackendConfigured(): Boolean =
        serverUrl.isNotBlank() && !serverUrl.contains(PLACEHOLDER_BACKEND_HOST)

    /** 结果页地址同样是部署配置；缺了它要开会前就提示，而不是等点击结果才发现。 */
    @JvmStatic @Synchronized fun isResultWebConfigured(): Boolean =
        resultWebUrl.isNotBlank()
            && (resultWebUrl.startsWith("http://") || resultWebUrl.startsWith("https://"))
            && !resultWebUrl.contains(PLACEHOLDER_RESULT_WEB_HOST)

    /**
     * 只有全部已注册结果动作都依赖网页地址时，缺失 resultWebUrl 才阻止登录。
     * 本地 PDF、本地 HTML、纯提示等自定义动作不依赖该地址。
     */
    @JvmStatic @Synchronized fun isResultWebRequired(): Boolean =
        resultActions.isNotEmpty() && resultActions.all { it.requiresResultWebUrl() }

    /**
     * 部署状态统一检查：返回 {@link DeploymentIssue#BACKEND}／{@link DeploymentIssue#RESULT_WEB}，
     * 都正常时返回 null。页面据此给出同一条可执行提示，不再各自判断其中一项。
     */
    @JvmStatic @Synchronized fun deploymentIssue(): DeploymentIssue? = when {
        !isBackendConfigured() -> DeploymentIssue.BACKEND
        isResultWebRequired() && !isResultWebConfigured() -> DeploymentIssue.RESULT_WEB
        else -> null
    }

    enum class DeploymentIssue { BACKEND, RESULT_WEB }
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
    /** 宿主只决定何时恢复可见页面，具体结果动作仍完全归插件。 */
    @JvmStatic @Synchronized fun hasPendingResultUi(): Boolean = pendingResultUi.isNotEmpty()
    @JvmStatic @Synchronized fun observeResultUi(notify: Runnable) { resultUiObserver = notify }
    @JvmStatic @Synchronized fun unobserveResultUi(notify: Runnable) { if (resultUiObserver === notify) resultUiObserver = null }

    /** 页面和 Service 共用身份来源，分别创建自己的客户端与串行回调队列。 */
    @JvmStatic @Synchronized fun clientConfig(callbacks: Executor): MeetingClientConfig {
        var config = MeetingClientConfig(serverUrl, identityProvider,
            ClientIdentity.reference(), MeetingProfile.reference(), callbacks)
            .withArtifactPublisher(HttpMeetingArtifactPublisher())
        // 未初始化时保持旧行为；初始化后同一份现场用于开会落盘和启动续办。
        stoppedStore?.let { config = config.withStoppedMeetingStore(it) }
        recoveryStore?.let { config = config.withMeetingRecoveryStore(it) }
        return config
    }

    /**
     * 启动时对未确认停止的原场逐场续办：按停止记录先区分待收尾与已 READY，
     * 待收尾场次续办发布／Stop／READY，已 READY 场次只清本地残留，只有恢复现场
     * 的场次交给 recoverInterrupted；成功后删除现场，失败保留等下次启动。
     *
     * <p>本进程 Service 仍在进行或收尾的同一场整场跳过，避免两条链同时生成 genTime。</p>
     *
     * <p>调用方负责放在后台线程；未登录时 SDK 拒绝并保留现场，登录后再次调用即可。
     * 参考实现只在页面启动触发一次，伙伴可以改到开机广播或工作队列。</p>
     */
    @JvmStatic fun resumeInterruptedMeetings(onFailure: (String) -> Unit = {}) {
        if (!recoveryScanning.compareAndSet(false, true)) {
            Log.i(TAG, "原场续办扫描已在进行，跳过重复触发")
            return
        }
        try {
            resumeInterruptedMeetingsOnce(onFailure)
        } finally {
            recoveryScanning.set(false)
        }
    }

    /**
     * 由会议 Service 在开始／结束会议时调用；新进程里为空，异常现场才允许续办。
     * 从会议开始一直保留到 READY／终态销毁，扫描据此跳过仍在收尾的同一场。
     */
    @JvmStatic fun markActiveMeeting(meetingId: String?) { activeMeetingId = meetingId }

    /** 本进程 Service 持有的场次；会议进行中和收尾中都算所有权，扫描不得另起续办。 */
    private fun ownedMeetingIds(): Set<String> = activeMeetingId?.let { setOf(it) } ?: emptySet()

    private fun resumeInterruptedMeetingsOnce(onFailure: (String) -> Unit) {
        val store: ReferenceMeetingRecoveryStore
        val stopped: ReferenceStoppedMeetingStore
        val context: Context
        synchronized(MeetingAppAssembly::class.java) {
            store = recoveryStore ?: return
            stopped = stoppedStore ?: return
            context = applicationContext ?: return
        }
        val config = clientConfig { it.run() }
        MeetingRecoveryScan(store, stopped, ::ownedMeetingIds,
            { MeetingRuntimeFactory.create(context, config) }, onFailure).run()
    }

    @JvmStatic @Synchronized fun clearReferenceSession() { (identityProvider as? LoginIdentityProvider)?.clearSession() }

    private const val KEY_BACKEND_URL = "backend_url"
}
