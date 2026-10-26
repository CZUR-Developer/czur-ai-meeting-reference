package com.czur.aimeeting.client

import com.czur.aimeeting.client.display.MeetingDisplayState
import com.czur.aimeeting.client.display.BilingualLanguageResolver
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.identity.LoginIdentityProvider
import com.czur.aimeeting.client.result.ResultActionAccess
import com.czur.aimeeting.client.result.ResultOptions
import com.czur.aimeeting.client.server.*
import com.czur.aimeeting.model.*
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.ArrayDeque
import java.util.OptionalLong
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Function
import java.util.function.LongSupplier
import java.util.function.Supplier
import java.util.logging.Logger
import java.util.regex.Pattern

/**
 * 平台无关的唯一正常会议编排。状态／显示／deadline 只在 state 队列，HTTP／总结在 io，
 * start／stop／close 在 modelControl 保序，使挂起 HTTP 不拖停音源。SDK／Mock 只注入运行时。
 * 单客户端最多一场会议；未确认标准事件仅内存保留原包，由后续事件／停止补交。
 * 停止记录只覆盖尾部确认后的发布／同键 Stop／实际 READY；装配原场现场端口时，
 * 采音起点另落一份 CapturingMeeting，供进程异常退出后由宿主逐场调用 recoverInterrupted，
 * 本类不自行扫描目录或定时恢复。
 */
class MeetingCoordinator(
    private val config: MeetingClientConfig,
    private val adapters: ModelAdapterRegistry,
    private val runtime: MeetingRuntimePort,
    private val servers: Function<MeetingClientConfig, MeetingServerClient>,
    private val monotonicMs: LongSupplier,
    private val wallClockMs: LongSupplier,
) : MeetingClient {
    constructor(config: MeetingClientConfig, adapters: ModelAdapterRegistry, runtime: MeetingRuntimePort) : this(
        config, adapters, runtime, Function(::HttpMeetingServerClient), LongSupplier(runtime::elapsedRealtimeMs),
        LongSupplier(System::currentTimeMillis))
    /** 定向测试注入可控时钟和端口，生产使用同一 HTTP 装配。 */
    constructor(config: MeetingClientConfig, adapters: ModelAdapterRegistry, runtime: MeetingRuntimePort,
        servers: Supplier<MeetingServerClient>, monotonicMs: LongSupplier, wallClockMs: LongSupplier) : this(
        config, adapters, runtime, Function { servers.get() }, monotonicMs, wallClockMs)

    private val state = Executors.newSingleThreadScheduledExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val modelControl = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var current: Session? = null
    /** 仅客户端级互斥，续办不新建 Session，不阻塞回调队列等待网络。 */
    private var resumingFinalization = false
    /** 原场现场续办互斥；与新建会议、停止续办互斥，避免同一场被两条流程同时收口。 */
    private var recovering = false

    private fun setupCapabilities(): Set<String> = if (runtime.usesServerModelMaterials()) adapters.setupCapabilities()
        else java.util.Set.copyOf(runtime.localCapabilities())
    private fun canPublishRetainedAudio(): Boolean =
        runtime.supportsRetainedAudio() && config.artifactPublisher().supportsAudioPublication()

    /**
     * 实时／总结沿用本地工厂交集；跨会议身份由部署端 Provider 处理，不放进实时白名单。
     * 此处只表示本机具备本场发言人、同源录音与发布条件，尚未取得本场授权。
     * retainAudio 的用户选择在 Create 请求冻结时再核对，实际模型工厂在领取材料后再核对。
     */
    private fun availableDeploymentCapabilities(deployment: DeploymentOptions): Set<String> {
        val available = HashSet(deployment.capabilities())
        available.retainAll(setupCapabilities())
        available.remove(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING)
        if (deployment.capabilities().contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING) &&
            available.contains(MeetingCapabilityState.SPEAKER) && canPublishRetainedAudio())
            available.add(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING)
        return java.util.Set.copyOf(available)
    }
    override fun discover(callback: MeetingClient.Callback<MeetingDiscovery>) {
        requireOpen()
        io.execute {
            try {
                LOG.info("部署能力发现开始")
                val deployment = servers.apply(config).discover()
                val available = availableDeploymentCapabilities(deployment)
                LOG.info("部署能力发现完成")
                notifyClient { callback.onSuccess(MeetingDiscovery(deployment, available)) }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("部署能力发现", failure)
                notifyClient { callback.onError(MeetingError(MeetingErrorCode.DISCOVERY_FAILED)) }
            }
        }
    }
    override fun refreshIdentity(callback: MeetingClient.Callback<IdentityResult>) {
        requireOpen()
        io.execute {
            val identity = readIdentity()
            LOG.fine("当前身份读取完成 state=${identity.status()}")
            notifyClient { callback.onSuccess(identity) }
        }
    }
    private fun readIdentity(): IdentityResult = try { config.identityProvider().currentIdentity() }
        catch (failure: Exception) {
            if (failure !is IOException && failure !is RuntimeException) throw failure
            logFailure("身份读取", failure)
            IdentityResult.readFailed()
        }
    override fun login(username: String, password: String, callback: MeetingClient.Callback<ReferenceLoginSession>) {
        requireOpen()
        io.execute {
            val identityProvider = config.identityProvider()
            if (identityProvider !is LoginIdentityProvider) {
                notifyClient { callback.onError(MeetingError(MeetingErrorCode.IDENTITY_ENTRY_MISMATCH)) }
                return@execute
            }
            try {
                LOG.info("终端用户登录开始")
                val login = HttpMeetingSessionClient(config.serverUrl()).login(username, password)
                identityProvider.updateSession(IdentitySession(login.accessToken(), username), login.expiresAt())
                LOG.info("终端用户登录完成")
                notifyClient { callback.onSuccess(login) }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("终端用户登录", failure)
                notifyClient { callback.onError(MeetingError(MeetingErrorCode.LOGIN_FAILED)) }
            }
        }
    }
    @Synchronized override fun start(options: MeetingOptions, listener: MeetingListener): MeetingSession {
        requireOpen()
        check(!resumingFinalization && !recovering && (current == null || current!!.done)) { "已有会议或原场收尾正在进行" }
        val session = Session(options, listener)
        current = session
        enqueue { session.begin() }
        return session
    }
    @Synchronized override fun resumeFinalization(checkpointId: String, callback: MeetingClient.Callback<MeetingResult>) {
        requireOpen()
        val store = config.stoppedMeetingStore()
        if (store.isEmpty) { notifyClient { callback.onError(MeetingError(MeetingErrorCode.STOP_STORE_MISSING)) }; return }
        if (resumingFinalization || recovering || current != null && !current!!.done) {
            notifyClient { callback.onError(MeetingError(MeetingErrorCode.BUSY)) }
            return
        }
        resumingFinalization = true
        io.execute {
            var result: MeetingResult? = null
            var errorCode: MeetingErrorCode? = null
            try {
                if (closed) return@execute
                var checkpoint = store.get().read(checkpointId).orElseThrow { IOException("原场停止记录不存在") }
                checkpoint = renewFinalizationIdentity(checkpoint, store.get())
                val original = checkpoint.context()
                // 原场事实仍来自 Store；身份扩展已核同账号，不重新发现／建会／调用模型。
                val frozenConfig = MeetingClientConfig(original.serverUrl(), original.accessToken(),
                    original.clientIdentity(), config.profile(), config.callbackExecutor())
                val server = servers.apply(frozenConfig)
                LOG.info("原场停止收尾续办开始 phase=${checkpoint.phase()}")
                if (checkpoint.phase() == StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING) {
                    // 恢复流程先写停止记录、后提交冻结用量；若在这之间被打断，续办时快照会
                    // 一直缺用量并 409。这里补交同场同值事实，正常停止已提交过时由服务端幂等处理。
                    LOG.info("原场停止收尾补交冻结采音用量 durationMs=${checkpoint.context().frozenAudioDurationMs()}")
                    server.submitUsage(checkpoint.checkpointId(), checkpoint.context().frozenAudioDurationMs())
                }
                result = finishSavedFinalization(checkpoint, store.get(), server, config.artifactPublisher(),
                    BooleanSupplier { closed }, wallClockMs, Runnable {})
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("续办原场停止收尾", failure)
                errorCode = MeetingErrorCode.FINALIZATION_FAILED
            } finally { synchronized(this) { resumingFinalization = false } }
            // 先释放互斥，再回调，允许宿主在回调中开始下一场。
            val actual = result
            val code = errorCode
            if (actual != null) notifyClient { callback.onSuccess(actual) }
            else if (code != null) notifyClient { callback.onError(MeetingError(code)) }
        }
    }
    @Synchronized override fun recoverInterrupted(meetingId: String, callback: MeetingClient.Callback<RecoveryResult>) {
        requireOpen()
        val recoveryStore = config.meetingRecoveryStore()
        val stoppedStore = config.stoppedMeetingStore()
        if (recoveryStore.isEmpty || stoppedStore.isEmpty) {
            notifyClient { callback.onError(MeetingError(MeetingErrorCode.RECOVERY_STORE_MISSING)) }
            return
        }
        if (resumingFinalization || recovering || current != null && !current!!.done) {
            notifyClient { callback.onError(MeetingError(MeetingErrorCode.BUSY)) }
            return
        }
        recovering = true
        io.execute {
            var outcome: RecoveryResult? = null
            var errorCode: MeetingErrorCode? = null
            try {
                if (closed) return@execute
                val record = recoveryStore.get().read(meetingId)
                    .orElseThrow { IOException("原场现场不存在或原账号已改变") }
                if (stoppedStore.get().read(meetingId).isPresent)
                    throw IOException("原场已有停止记录，应由停止续办收口")
                val identity = config.identityProvider().currentIdentity()
                if (identity.status() != IdentityResult.Status.LOGGED_IN) throw IOException("原场账号身份尚未就绪")
                val sessionConfig = config.forSession(identity.session()!!)
                if (record.serverUrl() != sessionConfig.serverUrl()) throw IOException("原场服务地址已改变")
                val server = servers.apply(sessionConfig)
                // 录音只为续办读取文件持久前缀；估算时长不冒充正常停止的计数门原值。
                val audio = RecoveredAudioFacts.sealedArtifact(meetingId, record.audioFile())
                // 发布快照要求本场冻结用量已提交；正常停止在发布前提交，续办必须补同一事实。
                LOG.info("原场冻结用量提交开始 durationMs=${audio.durationMs()}")
                server.submitUsage(meetingId, audio.durationMs())
                LOG.info("原场冻结用量已确认")
                // 纪要必须在写停止记录之前补交：一旦停止记录落盘，下次会走 resumeFinalization，
                // 那条路径只补用量、不会再补纪要，失败一次就会永久缺纪要。
                submitRecoveredSummary(record, server)
                var checkpoint = stoppedStore.get().save(StoppedMeetingCheckpoint.pending(
                    MeetingArtifactPublisher.Context(meetingId, audio, audio.durationMs(), sessionConfig)))
                LOG.info("原场现场续办开始 phase=${checkpoint.phase()}")
                val result = finishSavedFinalization(checkpoint, stoppedStore.get(), server,
                    sessionConfig.artifactPublisher(), BooleanSupplier { closed }, wallClockMs,
                    Runnable {}, record.activeDelivery())
                recoveryStore.get().delete(meetingId)
                record.journalFile()?.let { journal -> if (journal.exists() && !journal.delete()) LOG.warning("原场字幕日志删除失败") }
                outcome = RecoveryResult(meetingId, result)
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("原场现场续办", failure)
                errorCode = MeetingErrorCode.RECOVERY_FAILED
            } finally { synchronized(this) { recovering = false } }
            // 先释放互斥，再回调，允许宿主在回调中处理下一场。
            val actual = outcome
            val code = errorCode
            if (actual != null) notifyClient { callback.onSuccess(actual) }
            else if (code != null) notifyClient { callback.onError(MeetingError(code)) }
        }
    }
    /**
     * 用本机字幕日志补交标准纪要；日志缺失或为空时不伪造，按静音／短会继续收口。
     * 模型材料与正常停止同一入口，不新增第二套总结路径。
     */
    private fun submitRecoveredSummary(record: CapturingMeeting, server: MeetingServerClient) {
        if (!record.summaryEnabled()) return
        val journal = record.journalFile() ?: return
        val input = TranscriptJournal(journal).summaryInput(record.sourceLanguage()) ?: return
        LOG.info("原场纪要重跑开始")
        val grant = if (runtime.usesServerModelMaterials()) server.requestSummarySession(record.meetingId()) else null
        val adapter = if (grant == null) null else adapters.createSummary(grant.adapterId())
        try {
            server.submitSummary(record.meetingId(), runtime.summarize(grant, adapter, input))
            LOG.info("原场纪要已提交")
        } catch (conflict: MeetingHttpStatusException) {
            if (conflict.status != 409) throw conflict
            LOG.warning("原场纪要已存在且正文不同，保留服务端版本继续收尾")
        }
    }
    /** 每次续办只调用一次同账号身份扩展，先持久保存新材料，再允许外部发布／Stop。 */
    private fun renewFinalizationIdentity(checkpoint: StoppedMeetingCheckpoint, store: StoppedMeetingStore): StoppedMeetingCheckpoint {
        if (checkpoint.phase() == StoppedMeetingCheckpoint.Phase.READY) return checkpoint
        val original = IdentitySession(checkpoint.context().accessToken(), "")
        val identity = config.identityProvider().identityForFinalization(checkpoint.checkpointId(), original)
        if (identity.status() != IdentityResult.Status.LOGGED_IN) throw IOException("原场账号身份尚未就绪")
        val accessToken = identity.session()!!.accessToken()
        if (accessToken == original.accessToken()) return checkpoint
        val refreshed = store.save(checkpoint.withAccessToken(accessToken))
        LOG.info("原场停止收尾访问凭据已更新，原会议及确认进度保持")
        return refreshed
    }
    /** 已确认尾部共用唯一流程，真实 ACK 先保存；READY 也读实际清单，不由本地阶段伪造。 */
    private fun finishSavedFinalization(initial: StoppedMeetingCheckpoint, store: StoppedMeetingStore,
        server: MeetingServerClient, publisher: MeetingArtifactPublisher, aborted: BooleanSupplier,
        acceptedGenTime: LongSupplier, onStopAccepted: Runnable, deliverResult: Boolean = true): MeetingResult? {
        if (aborted.asBoolean) return null
        var checkpoint = store.save(initial)
        if (aborted.asBoolean) return null
        if (checkpoint.phase() == StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING) {
            if (!publisher.supportsAudioPublication()) throw IOException("停止记录续办需要原场产物发布器")
            LOG.info("原场封口音频发布开始")
            publisher.publish(checkpoint.context())
            checkpoint = store.save(checkpoint.published())
            LOG.info("原场封口音频发布已确认并保存")
        }
        if (aborted.asBoolean) return null
        if (checkpoint.phase() == StoppedMeetingCheckpoint.Phase.PUBLISHED) {
            LOG.info("后端原键 Stop 开始")
            if (!server.stopMeeting(checkpoint.checkpointId(), checkpoint.stableStopKey()).accepted())
                throw IOException("后端尚未受理本场停止")
            checkpoint = store.save(checkpoint.stopAccepted(acceptedGenTime.asLong))
            LOG.info("后端 Stop 已受理并保存，结果尚需确认")
        }
        if (aborted.asBoolean) return null
        onStopAccepted.run()
        if (!deliverResult) {
            // NO_ACTIVE_DELIVERY 只收口到 ENDED，不读清单；本地阶段仍记录到 Stop 已受理。
            if (checkpoint.phase() != StoppedMeetingCheckpoint.Phase.READY) store.save(checkpoint.ready())
            LOG.info("原场已停止，未主动交付结果")
            return null
        }
        LOG.info("读取原场实际 READY 清单开始")
        val result = server.readResult(checkpoint.checkpointId(), checkpoint.genTime()!!, false)
        if (checkpoint.phase() != StoppedMeetingCheckpoint.Phase.READY) store.save(checkpoint.ready())
        return result
    }
    private fun requireOpen() { check(!closed) { "会议客户端已关闭" } }
    private fun enqueue(task: Runnable) {
        try { state.execute(task) } catch (rejected: RejectedExecutionException) { if (!closed) throw rejected }
    }
    private fun notifyClient(task: Runnable) { config.callbackExecutor().execute { if (!closed) task.run() } }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        enqueue { current?.cancel(); io.shutdown(); modelControl.shutdown(); state.shutdown() }
    }

    private inner class Session(private val options: MeetingOptions, private val listener: MeetingListener) : MeetingSession {
        private lateinit var server: MeetingServerClient
        private lateinit var sessionConfig: MeetingClientConfig
        private val display = MeetingDisplayState()
        private val finals = FinalTranscriptCollector()
        @Volatile var done = false
        @Volatile private var cancelled = false
        /** 状态队列关门后，已排队电平也不能再次点亮 UI。 */
        @Volatile private var audioLevelActive = false
        private var meetingId: String? = null
        private val id: String get() = meetingId!!
        private var available = emptySet<String>()
        private var model: ModelSession? = null
        private var phase = MeetingSessionState.Phase.CREATING
        private var lastLoggedPhase: MeetingSessionState.Phase? = null
        private var active = false
        private var canStop = false
        private var stopRequested = false
        private var stopInputSent = false
        private var modelFinished = false
        @Volatile private var audioFailed = false
        private var completionQueued = false
        private var acceptingPresentation = false
        private var sequence = 0
        private var savedEvents = 0
        private var deadline: ScheduledFuture<*>? = null
        /** IO 队列保留已分号原包，失败不跳队首；后续事件／停止重交，不增加磁盘或定时队列。 */
        private val pendingStandardEvents = ArrayDeque<PendingStandardEvent>()
        /** 截图独立序号，仅 ACK 推进；至多保留一个未确认原包，不覆盖或换号。 */
        private var presentationSequence = 0
        private var pendingPresentation: PresentationFrame? = null
        private var frozenAudioDuration = OptionalLong.empty()
        private var usageRead = false
        private var usageSubmitted = false
        private var summarySubmitted = false
        // 纪要一旦生成就冻结：提交确认丢失后重试必须原样重放，
        // 否则模型每次生成不同正文，后端会判 EVENT_CONFLICT 导致永远停不下来。
        private var pendingSummary: SummaryResult? = null
        /** 含失败在内，同场只读取一次 sealed 事实，仅 IO 队列访问。 */
        private var audioArtifactRead = false
        private var audioArtifactFailure: IOException? = null
        /** 采音起点落盘的原场现场；结束或确认收口后清理，崩溃时留给宿主续办。 */
        private var recoveryRecord: CapturingMeeting? = null
        private var audioJournal: TranscriptJournal? = null
        private var publicationContext: MeetingArtifactPublisher.Context? = null
        private var publicationConfirmed = false
        private var stoppedCheckpoint: StoppedMeetingCheckpoint? = null
        private var genTime: Long? = null
        @Volatile private var readyResult: MeetingResult? = null
        /** 唯一 state 队列跟踪待译原文；HTTP 仍走既有 IO 队列，不创建第二套会议状态。 */
        private var textTranslationGrant: TranslationSessionGrant? = null
        private var textTranslationAdapter: TranslationAdapter? = null
        private val latestTextTranslationRevisions = HashMap<String, Int>()
        private val pendingTextTranslations = LinkedHashMap<String, TranslationInput>()
        private val translating = HashSet<TranslationInput>()

        private fun notifyListener(task: Runnable) { notifyClient { if (!cancelled) task.run() } }
        private fun publish(notice: MeetingNotice? = null, errorCode: MeetingErrorCode? = null) {
            if (phase != lastLoggedPhase) {
                LOG.info("会议状态 $phase" + (notice?.let { " notice=$it" } ?: "") +
                    (errorCode?.let { " error=$it" } ?: ""))
                lastLoggedPhase = phase
            }
            val snapshot = MeetingSessionState(phase, meetingId, available, savedEvents, active, canStop,
                notice, errorCode)
            notifyListener { listener.onState(snapshot) }
        }
        private fun showDisplay() { val snapshot = display.snapshot(); notifyListener { listener.onDisplay(snapshot) } }
        fun begin() {
            if (cancelled || closed) return
            if (options.retainAudio() && !canPublishRetainedAudio()) {
                failStart(MeetingErrorCode.AUDIO_RETAIN_UNSUPPORTED)
                return
            }
            publish()
            io.execute {
                var step = "身份读取"
                try {
                    val identity = readIdentity()
                    if (identity.status() != IdentityResult.Status.LOGGED_IN) {
                        LOG.info("会议开始身份未就绪 state=${identity.status()}")
                        enqueue { failStart(MeetingErrorCode.LOGIN_REQUIRED) }
                        return@execute
                    }
                    sessionConfig = config.forSession(identity.session()!!)
                    server = servers.apply(sessionConfig)
                    LOG.info("本场身份已确认")
                    step = "能力发现"
                    val deployment = server.discover()
                    val setup = availableDeploymentCapabilities(deployment)
                    requireCapabilities(setup)
                    LOG.info("本场部署能力已确认")
                    step = "创建会议"
                    // 只把本场实际可用的可选能力交给 Create；必需能力仍由原始设置决定。
                    // 本场发言人来自实时 Adapter，跨会议身份来自服务端会后 Provider，二者分开协商。
                    val createOptions = options.withAvailableCapabilities(setup)
                    val created = server.createMeeting(createOptions)
                    val granted = HashSet<String>()
                    for (capability in createOptions.requestedCapabilities())
                        if (setup.contains(capability) && created.capabilities().canUse(capability, setup)) granted.add(capability)
                    requireCapabilities(granted)
                    LOG.info("会议创建及必需能力已确认")
                    step = "模型材料/工厂选择"
                    val grant = if (runtime.usesServerModelMaterials()) server.requestModelSession(created.meetingId()) else null
                    val local = HashSet(if (runtime.usesServerModelMaterials()) adapters.realtimeCapabilities(grant!!.adapterId()) else runtime.localCapabilities())
                    if (grant?.translationSession() != null && runtime.supportsTextTranslation() &&
                        adapters.hasTranslation(grant.translationSession()!!.adapterId()))
                        local.add(MeetingCapabilityState.TRANSLATION)
                    if (setupCapabilities().contains(MeetingCapabilityState.SUMMARY)) local.add(MeetingCapabilityState.SUMMARY)
                    val enabled = HashSet<String>()
                    for (capability in createOptions.requestedCapabilities())
                        if (capability != MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING &&
                            granted.contains(capability) && local.contains(capability) &&
                            created.capabilities().canUse(capability, local)) enabled.add(capability)
                    // 服务端身份依赖实际选中 Adapter 的本场 speaker；不从临时 speakerId 推断 personId。
                    if (granted.contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING) &&
                        enabled.contains(MeetingCapabilityState.SPEAKER))
                        enabled.add(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING)
                    requireCapabilities(enabled)
                    val adapter = if (grant == null) null else adapters.create(grant.adapterId())
                    LOG.info("本场模型材料与工厂已准备")
                    step = "启动客户端输入"
                    server.startClient(created.meetingId())
                    LOG.info("后端客户端输入已启动")
                    enqueue { startModel(created, grant, adapter, java.util.Set.copyOf(enabled)) }
                } catch (failure: Exception) {
                    if (failure !is IOException && failure !is RuntimeException) throw failure
                    logFailure(step, failure)
                    enqueue { failStart(MeetingErrorCode.START_FAILED) }
                }
            }
        }
        private fun requireCapabilities(capabilities: Set<String>) {
            if (!capabilities.containsAll(options.requiredCapabilities())) {
                LOG.warning("本场缺少用户已选择的必需能力 ${options.requiredCapabilities()}")
                throw IOException("本场缺少必需字幕或模式能力")
            }
        }
        private fun startModel(created: CreatedMeeting, grant: ModelSessionGrant?, adapter: ModelAdapter?, enabled: Set<String>) {
            if (cancelled || closed) return
            meetingId = created.meetingId()
            available = enabled
            val startedAtMs = monotonicMs.asLong
            display.start(startedAtMs, options.mode() == MeetingOptions.Mode.LIVE_TRANSLATION, options.sourceLanguage(), options.targetLanguage())
            display.setWaitForModelLanguage(adapter?.mayEmitDeferredSourceLanguage() == true)
            try {
                // 明确 sidecar 才组合独立翻译；听悟原生译文没有此字段，保持原路径。
                if (enabled.contains(MeetingCapabilityState.TRANSLATION)) grant?.translationSession()?.let {
                    check(runtime.supportsTextTranslation()) { "当前 Runtime 未装配独立文本翻译" }
                    textTranslationGrant = it
                    textTranslationAdapter = adapters.createTranslation(it.adapterId())
                }
                // 闭包冻结原场 Server 和 ID，HTTP 由 Runtime 续接线程执行，不占 state／modelControl。
                val frozenServer = server
                val frozenMeetingId = created.meetingId()
                val reconnector = if (grant == null) null else ModelSessionReconnector {
                    val next = frozenServer.requestModelSession(frozenMeetingId)
                    val fresh = adapters.create(next.adapterId())
                    LOG.fine("本场模型续接材料及新解析实例已准备")
                    ModelSessionConnection(next, fresh)
                }
                model = runtime.openWithAudioMix(grant, adapter, TranscriptListener { event -> enqueue { transcript(event) } },
                    object : ModelSessionListener {
                        override fun onAudioInputMode(mode: AudioInputMode) { enqueue { if (!done) notifyListener { listener.onAudioInputMode(mode) } } }
                        override fun onAudioLevel(level: AudioInputLevel) {
                            enqueue { if (audioLevelActive) notifyListener { if (audioLevelActive) listener.onAudioLevel(level) } }
                        }
                        override fun onAudioDirection(sample: AudioDirectionSample) { enqueue { if (!done) display.updateAudioDirection(sample) } }
                        override fun onSegmentStarted(event: SegmentStartedEvent) { enqueue { if (!done) display.onSegmentStarted(event) } }
                        override fun onTranslation(event: TranslationEvent) { enqueue { translation(event) } }
                        override fun onFinished() { enqueue { finished() } }
                        override fun onAudioError(code: AudioInputErrorCode, message: String) {
                            enqueue {
                                if (!done) {
                                    audioFailed = true; modelFinished = true; audioLevelActive = false
                                    // 音频已经失效，必须允许用户结束本场；否则会停在
                                    // "有悬浮窗、没有字幕、停止不可用"的状态里出不去。
                                    canStop = true
                                    LOG.warning("本场音频输入终止 code=$code message=$message")
                                    notifyListener { listener.onAudioError(code) }
                                    publish(notice = MeetingNotice.AUDIO_INPUT_ENDED)
                                    maybeComplete()
                                }
                            }
                        }
                        override fun onError(message: String) {
                            enqueue {
                                if (!done) {
                                    modelFinished = true; audioLevelActive = false
                                    LOG.warning("本场模型运行时终止")
                                    publish(notice = MeetingNotice.MODEL_SESSION_ENDED)
                                    maybeComplete()
                                }
                            }
                        }
                    }, options.retainAudio(), reconnector, options.audioMix())
                // 采音真正开始前落原场现场；此后进程异常退出，宿主可凭本记录续办。
                recordRecovery(startedAtMs)
                active = true; audioLevelActive = true; canStop = runtime.waitsForModelFinish()
                acceptingPresentation = options.presentationCapture()
                phase = MeetingSessionState.Phase.RUNNING
                val info = MeetingStarted(id, startedAtMs, created.maxMeetingDurationMs(), available)
                notifyListener { listener.onStarted(info) }
                showDisplay(); publish(); LOG.info("会议已开始")
                modelControl.execute {
                    if (cancelled || closed || done) return@execute
                    try { model!!.start() }
                    catch (failure: RuntimeException) { logFailure("启动模型运行时", failure); enqueue { failStart(MeetingErrorCode.MODEL_START_FAILED) } }
                }
            } catch (failure: RuntimeException) { logFailure("创建模型会话", failure); failStart(MeetingErrorCode.MODEL_START_FAILED) }
        }
        /**
         * 采音起点写原场现场：只有装配了恢复端口、本场保留录音且模型给出录音文件位置时才写。
         * 写入失败只告警，不阻断开会；缺现场等于回到旧的无恢复行为。
         */
        private fun recordRecovery(startedAtMs: Long) {
            val store = config.meetingRecoveryStore()
            if (store.isEmpty || !options.retainAudio()) return
            try {
                val audio = model?.audioArtifactFile()?.orElse(null) ?: return
                val journal = TranscriptJournal(File(audio.absoluteFile.parentFile, "${id}.jsonl"))
                val record = CapturingMeeting(id, sessionConfig.serverUrl(), sessionConfig.clientIdentity(),
                    audio, journal.file(), startedAtMs, options.sourceLanguage(), options.summaryEnabled(),
                    options.retainAudio(),
                    options.resultOptions().policy() != ResultOptions.Policy.NO_ACTIVE_DELIVERY)
                store.get().save(record)
                recoveryRecord = record; audioJournal = journal
                LOG.info("原场现场已落盘")
            } catch (failure: Exception) {
                LOG.warning("原场现场落盘失败 class=${failure.javaClass.simpleName}")
            }
        }
        /** 已确认收口或明确失败才删除现场；未确认现场保留给宿主续办。 */
        private fun cleanupRecovery() {
            val record = recoveryRecord ?: return
            recoveryRecord = null
            audioJournal?.delete(); audioJournal = null
            try { config.meetingRecoveryStore().orElse(null)?.delete(record.meetingId()) }
            catch (failure: Exception) { LOG.warning("原场现场清理失败 class=${failure.javaClass.simpleName}") }
        }
        private fun transcript(event: TranscriptEvent) {
            if (done || modelFinished) return
            val standard = if (available.contains(MeetingCapabilityState.SPEAKER)) event else event.withoutSpeaker()
            val nowMs = monotonicMs.asLong
            if (standard.text().isNotBlank()) notifyListener { listener.onTranscriptActivity(nowMs) }
            finals.apply(standard); display.apply(standard, nowMs); showDisplay(); scheduleDeadline()
            audioJournal?.let { journal -> io.execute { journal.append(standard) } }
            val number = ++sequence
            io.execute { saveStandardEvent(PendingStandardEvent(number, standard, null)) }
            queueTextTranslation(standard)
        }
        private fun translation(event: TranslationEvent, fromTextAdapter: Boolean = false) {
            if (done || modelFinished && !fromTextAdapter || !available.contains(MeetingCapabilityState.TRANSLATION) ||
                textTranslationGrant != null && !fromTextAdapter) return
            display.apply(event, monotonicMs.asLong); showDisplay(); scheduleDeadline()
            val number = ++sequence
            io.execute { saveStandardEvent(PendingStandardEvent(number, null, event)) }
        }
        /** partial 只显示；相同 final 修订不重复请求，真正更高修订仍产生对应标准译文修订。 */
        private fun queueTextTranslation(event: TranscriptEvent) {
            if (textTranslationGrant == null || !event.isFinal() || event.text().isBlank()) return
            if (event.revision() <= (latestTextTranslationRevisions[event.segmentId()] ?: 0)) return
            latestTextTranslationRevisions[event.segmentId()] = event.revision()
            val source = BilingualLanguageResolver.normalize(event.language())
            val first = BilingualLanguageResolver.normalize(options.sourceLanguage())
            val second = BilingualLanguageResolver.normalize(options.targetLanguage())
            // 真实源语种优先决定翻译目标；未知语种沿已有方向/字形锁侧作两语种默认，不改原文事实。
            val sourceIsSecond = when (source) {
                first -> false
                second -> true
                else -> display.route(event.segmentId())?.side() == 2
            }
            val input = TranslationInput(event.segmentId(), event.revision(), event.language(),
                if (sourceIsSecond) options.sourceLanguage() else options.targetLanguage(), event.text())
            pendingTextTranslations[event.segmentId()] = input
            submitTextTranslation(input)
        }
        private fun submitTextTranslation(input: TranslationInput) {
            if (!translating.add(input)) return
            io.execute {
                try {
                    if (closed || cancelled) return@execute
                    var grant = textTranslationGrant!!
                    // 无需定时续领；下一句前才检查短期材料，续领只换翻译，不动原ASR/音源。
                    if (Instant.parse(grant.expiresAt()).toEpochMilli() - wallClockMs.asLong <= 30_000L) {
                        grant = server.requestTranslationSession(id)
                        textTranslationGrant = grant
                        textTranslationAdapter = adapters.createTranslation(grant.adapterId())
                        LOG.fine("本场文本翻译材料已续领")
                    }
                    val result = runtime.translate(grant, textTranslationAdapter!!, input)
                    enqueue {
                        translating.remove(input)
                        if (!done && !cancelled) {
                            translation(result, true)
                            if (pendingTextTranslations[input.sourceSegmentId()] === input)
                                pendingTextTranslations.remove(input.sourceSegmentId())
                            maybeComplete()
                        }
                    }
                } catch (failure: Exception) {
                    if (failure !is IOException && failure !is RuntimeException) throw failure
                    logFailure("独立文本翻译", failure)
                    enqueue {
                        translating.remove(input)
                        if (!done && !cancelled) {
                            publish(notice = MeetingNotice.TRANSLATION_PENDING)
                            maybeComplete()
                        }
                    }
                }
            }
        }
        /** 先接纳原包再确认队首，失败不跳过顺序。 */
        private fun saveStandardEvent(event: PendingStandardEvent) {
            if (closed || cancelled) return
            pendingStandardEvents.addLast(event)
            try { savePendingStandardEvents() }
            catch (failure: IOException) {
                logFailure("保存标准字幕或译文", failure)
                enqueue { if (!done) publish(notice = MeetingNotice.TRANSCRIPT_UNCONFIRMED) }
            }
        }
        /** IO 队列原序号重交，Server 返回后先出队再更新计数，Stop 也复用此确认屏障。 */
        private fun savePendingStandardEvents() {
            while (pendingStandardEvents.isNotEmpty()) {
                if (closed || cancelled) return
                val event = pendingStandardEvents.first
                if (event.transcript != null) server.appendTranscript(id, event.sequence, event.transcript)
                else server.appendTranslation(id, event.sequence, event.translation!!)
                pendingStandardEvents.removeFirst()
                LOG.fine("标准事件保存已确认 sequence=${event.sequence}")
                enqueue {
                    if (!done) {
                        if (event.transcript != null) {
                            savedEvents++
                            if (!runtime.waitsForModelFinish() && event.transcript.isFinal() && !stopRequested) canStop = true
                        }
                        publish()
                    }
                }
            }
        }
        private fun scheduleDeadline() {
            cancelDeadline()
            val next = display.nextLanguageDeadlineMs()
            if (next.isPresent) deadline = state.schedule(Runnable {
                if (!done) { if (display.releaseExpiredLanguages(monotonicMs.asLong)) showDisplay(); scheduleDeadline() }
            }, maxOf(0L, next.asLong - monotonicMs.asLong), TimeUnit.MILLISECONDS)
        }
        private fun cancelDeadline() { deadline?.cancel(false); deadline = null }
        private fun finished() {
            if (done || modelFinished) return
            modelFinished = true; audioLevelActive = false
            if (display.flushPendingLanguages()) showDisplay()
            cancelDeadline()
            publish()
            maybeComplete()
        }
        override fun submitPresentation(offsetMs: Long, jpeg: ByteArray) {
            submitPresentation(offsetMs, jpeg, object : MeetingClient.Callback<Void?> {
                override fun onSuccess(result: Void?) {}
                override fun onError(error: MeetingError) {}
            })
        }
        override fun submitPresentation(offsetMs: Long, jpeg: ByteArray, callback: MeetingClient.Callback<Void?>) {
            val frame = PresentationFrame(offsetMs, jpeg)
            enqueue {
                if (done || !acceptingPresentation) {
                    notifyListener { callback.onError(MeetingError(MeetingErrorCode.PRESENTATION_REJECTED)) }
                }
                else io.execute { savePresentation(frame, callback) }
            }
        }
        /** IO 队列先检查实际预算，不为本地超限保留帧或分配序号。 */
        private fun savePresentation(frame: PresentationFrame, callback: MeetingClient.Callback<Void?>) {
            if (closed || cancelled) return
            try {
                val candidate = presentationSequence + if (pendingPresentation == null) 1 else 2
                HttpMeetingServerClient.validatePresentationRequestSize(candidate, frame.offsetMs(), frame.jpeg())
            } catch (invalidSize: IOException) {
                logFailure("检查演示截图大小", invalidSize)
                presentationFailure(callback, MeetingErrorCode.PRESENTATION_TOO_LARGE,
                    "演示截图超出1MiB传输预算，请缩小图片；字幕会议继续")
                return
            }
            try {
                if (pendingPresentation != null) { LOG.fine("重新确认原演示截图包 sequence=${presentationSequence + 1}"); savePendingPresentation() }
                // 旧包确认后才接纳新帧，重放失败时保留旧原包、不覆盖。
                pendingPresentation = frame
                savePendingPresentation()
                enqueue { notifyListener { callback.onSuccess(null) } }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("保存演示截图", failure)
                presentationFailure(callback, MeetingErrorCode.PRESENTATION_SAVE_FAILED,
                    "演示截图保存未确认；字幕会议继续")
            }
        }
        private fun savePendingPresentation() {
            val number = presentationSequence + 1
            val pending = pendingPresentation!!
            server.appendPresentation(id, number, pending.offsetMs(), pending.jpeg())
            presentationSequence = number; pendingPresentation = null
            LOG.fine("演示截图保存已确认 sequence=$number")
        }
        /** 图片失败独立提示，不阻断已保存的文字正常停止。 */
        private fun presentationFailure(callback: MeetingClient.Callback<Void?>,
            code: MeetingErrorCode, message: String) {
            LOG.warning(message)
            enqueue { if (!done) { publish(errorCode = code); notifyListener { callback.onError(MeetingError(code)) } } }
        }
        override fun swapDisplayColumns() {
            enqueue { if (!done && active && display.isBilingual()) { display.swapColumns(); showDisplay() } }
        }
        override fun requestStop() {
            enqueue {
                if (done || !active || !canStop) return@enqueue
                stopRequested = true; audioLevelActive = false; canStop = false
                phase = MeetingSessionState.Phase.STOPPING
                if (display.flushPendingLanguages()) showDisplay()
                cancelDeadline(); publish()
                // 只在用户确认停止时重试已失败的原输入，不增加定时任务或无限重试。
                pendingTextTranslations.values.toList().forEach(::submitTextTranslation)
                if (stopInputSent) { maybeComplete(); return@enqueue }
                val continued = AtomicBoolean()
                notifyListener { listener.beforeStop { if (continued.compareAndSet(false, true)) enqueue { continueStop() } } }
            }
        }
        private fun continueStop() {
            if (done || stopInputSent) return
            acceptingPresentation = false
            modelControl.execute {
                try { model!!.stopInput(); enqueue { stopInputSent = true; maybeComplete() } }
                catch (failure: RuntimeException) {
                    logFailure("停止模型输入", failure)
                    enqueue { canStop = true; publish(notice = MeetingNotice.STOP_RETRY_REQUIRED) }
                }
            }
        }
        private fun maybeComplete() {
            if (done || completionQueued || !stopRequested || !stopInputSent || runtime.waitsForModelFinish() && !modelFinished) return
            if (translating.isNotEmpty()) return
            if (pendingTextTranslations.isNotEmpty()) {
                completionFailed("独立翻译尚未完成，请检查模型连接后再次确认停止")
                return
            }
            completionQueued = true; phase = MeetingSessionState.Phase.FINALIZING
            publish()
            var input: SummaryInput? = null
            if (!audioFailed && available.contains(MeetingCapabilityState.SUMMARY) && options.summaryEnabled()) {
                try { input = finals.input(options.sourceLanguage()) }
                catch (empty: IllegalArgumentException) {
                    // 静音／短会无有效 final 时不伪造总结、不请求模型，继续用量／录音／Stop。
                    LOG.info("本场没有有效最终字幕，跳过纪要并继续正常停止")
                    publish(notice = MeetingNotice.NO_FINAL_TRANSCRIPT)
                }
            }
            val summaryInput = input
            io.execute { complete(summaryInput) }
        }
        private fun complete(input: SummaryInput?) {
            if (cancelled || closed) return
            var step = "检查标准事件保存"
            try {
                savePendingStandardEvents()
                if (cancelled || closed) return
                step = "提交冻结采音用量"
                if (!usageRead) { frozenAudioDuration = model!!.frozenAudioDurationMs(); usageRead = true }
                if (frozenAudioDuration.isPresent && !usageSubmitted) {
                    val durationMs = frozenAudioDuration.asLong
                    LOG.info("冻结采音用量提交开始 durationMs=$durationMs")
                    server.submitUsage(id, durationMs); usageSubmitted = true; LOG.info("冻结采音用量已确认")
                }
                if (input != null && !summarySubmitted) {
                    step = "生成/提交标准纪要"
                    val frozen = pendingSummary ?: run {
                        LOG.info("标准纪要生成开始")
                        val grant = if (runtime.usesServerModelMaterials()) server.requestSummarySession(id) else null
                        val adapter = if (grant == null) null else adapters.createSummary(grant.adapterId())
                        runtime.summarize(grant, adapter, input).also { pendingSummary = it }
                    }
                    try {
                        server.submitSummary(id, frozen)
                        summarySubmitted = true; LOG.info("标准纪要已提交")
                    } catch (conflict: MeetingHttpStatusException) {
                        if (conflict.status != 409) throw conflict
                        // 服务端已有纪要（可能正是上次丢失确认的那次提交），保留服务端版本继续收尾。
                        summarySubmitted = true
                        LOG.warning("标准纪要已存在且正文不同，保留服务端版本并继续停止")
                    }
                }
                if (cancelled || closed) return
                // 只在原尾部全部 ACK 与 sealed 读取成功后保存候选，无痕继续 Stop→ENDED。
                val store = sessionConfig.stoppedMeetingStore()
                if (store.isPresent && options.retainAudio() && !audioFailed && options.resultOptions().policy() != ResultOptions.Policy.NO_ACTIVE_DELIVERY) {
                    step = "保存并完成原场停止收尾"
                    readAudioArtifactOnce()
                    if (cancelled || closed) return
                    if (stoppedCheckpoint == null) stoppedCheckpoint = StoppedMeetingCheckpoint.pending(publicationContext!!)
                    else {
                        var checkpoint = store.get().read(stoppedCheckpoint!!.checkpointId()).orElse(stoppedCheckpoint!!)
                        // 首次候选写失败也可重按：先保存，身份扩展才有原账号归属可核。
                        checkpoint = store.get().save(checkpoint)
                        checkpoint = renewFinalizationIdentity(checkpoint, store.get())
                        stoppedCheckpoint = checkpoint
                        publicationContext = checkpoint.context()
                        sessionConfig = sessionConfig.forSession(IdentitySession(publicationContext!!.accessToken(), ""))
                        server = servers.apply(sessionConfig)
                    }
                    val finalResult = finishSavedFinalization(stoppedCheckpoint!!, store.get(), server,
                        sessionConfig.artifactPublisher(), BooleanSupplier { cancelled || closed }, LongSupplier {
                            if (genTime == null) genTime = wallClockMs.asLong
                            genTime!!
                        }, Runnable { model!!.close() })
                    if (finalResult != null) enqueue { ready(finalResult) }
                    return
                }
                // 模型结束不等于音频封口；模型异常保留已采录音，输入错误／取消不正常发布。
                if (options.retainAudio() && !audioFailed && !publicationConfirmed) {
                    step = "发布本场封口音频"
                    readAudioArtifactOnce()
                    if (cancelled || closed) return
                    if (!audioFailed) {
                        LOG.info("本场封口音频发布开始")
                        sessionConfig.artifactPublisher().publish(publicationContext!!)
                        publicationConfirmed = true; LOG.info("本场封口音频发布已确认")
                    }
                }
                if (cancelled || closed) return
                step = "后端 Stop"; LOG.info("后端 Stop 开始")
                if (!server.stopMeeting(id).accepted()) {
                    LOG.info("后端 Stop 尚未受理，等待用户再次确认")
                    enqueue { completionFailed("后端仍在收口，请稍后再次确认停止") }
                    return
                }
                LOG.info("后端 Stop 已受理，结果尚需确认")
                if (genTime == null) genTime = wallClockMs.asLong
                model!!.close()
                if (audioFailed || options.resultOptions().policy() == ResultOptions.Policy.NO_ACTIVE_DELIVERY) {
                    enqueue { endWithoutDelivery(if (audioFailed) "音频输入已结束，本场已停止" else "会议已结束；无痕互译不主动交付结果") }
                    return
                }
                step = "读取实际 READY 清单"; LOG.info("读取实际 READY 清单开始")
                val finalResult = server.readResult(id, genTime!!, false)
                enqueue { ready(finalResult) }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure(step, failure)
                enqueue { completionFailed("结束会议失败，请检查连接后再次确认停止") }
            }
        }
        private fun ready(finalResult: MeetingResult) {
            if (done) return
            done = true; active = false; phase = MeetingSessionState.Phase.READY; readyResult = finalResult
            cleanupRecovery()
            notifyListener { listener.onResult(finalResult) }
            publish(); LOG.info("会议结果已就绪")
        }
        private fun readAudioArtifactOnce() {
            if (!audioArtifactRead) {
                audioArtifactRead = true
                try {
                    val audio = model!!.sealedAudioArtifact().orElseThrow { IOException("本场录音尚未完成封口") }
                    if (meetingId != audio.meetingId()) throw IOException("录音产物不属于本场会议")
                    if (frozenAudioDuration.isEmpty || frozenAudioDuration.asLong != audio.durationMs())
                        throw IOException("录音事实与本场冻结采音用量不一致")
                    publicationContext = MeetingArtifactPublisher.Context(id, audio, frozenAudioDuration.asLong, sessionConfig)
                } catch (failure: Exception) {
                    if (failure !is IOException && failure !is RuntimeException) throw failure
                    audioArtifactFailure = IOException("本场录音封口事实不可用", failure)
                }
            }
            audioArtifactFailure?.let { throw it }
        }
        private fun completionFailed(message: String) {
            if (done) return
            completionQueued = false; canStop = true; phase = MeetingSessionState.Phase.STOP_PENDING
            LOG.warning("本场停止未完成：$message")
            publish(notice = MeetingNotice.STOP_RETRY_REQUIRED)
        }
        private fun endWithoutDelivery(message: String) {
            if (done) return
            done = true; active = false; phase = MeetingSessionState.Phase.ENDED
            cleanupRecovery()
            LOG.info("会议已停止，未主动交付结果：$message")
            publish()
        }
        private fun failStart(code: MeetingErrorCode) {
            if (done) return
            audioLevelActive = false; done = true; active = false; canStop = false; phase = MeetingSessionState.Phase.FAILED
            cancelDeadline()
            cleanupRecovery()
            model?.let { modelControl.execute(it::close) }
            publish(errorCode = code)
        }
        override fun resultActionAccess(): ResultActionAccess = object : ResultActionAccess {
            override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) {
                accessResult("读取结果内容", callback) { result -> result.content()
                    ?: server.readResult(result.meetingId(), result.genTime(), true).content() }
            }
            override fun sendEmail(recipients: List<String>, resultUrl: String, callback: MeetingClient.Callback<Void?>) {
                val frozenRecipients = java.util.List.copyOf(recipients)
                accessResult("发送结果邮件", callback) { result -> server.sendResultEmail(result.meetingId(), frozenRecipients, resultUrl); null }
            }
        }
        private fun <T> accessResult(step: String, callback: MeetingClient.Callback<T>, call: (MeetingResult) -> T) {
            enqueue {
                if (closed || cancelled) return@enqueue
                val result = readyResult
                if (result == null) {
                    notifyListener { callback.onError(MeetingError(MeetingErrorCode.RESULT_NOT_READY)) }
                    return@enqueue
                }
                io.execute {
                    if (closed || cancelled) return@execute
                    try {
                        LOG.fine("同场结果访问开始 step=$step")
                        val value = call(result)
                        notifyListener { callback.onSuccess(value) }
                    } catch (failure: Exception) {
                        if (failure !is IOException && failure !is RuntimeException) throw failure
                        logFailure(step, failure)
                        notifyListener { callback.onError(MeetingError(MeetingErrorCode.STEP_FAILED)) }
                    }
                }
            }
        }
        override fun close() { enqueue { cancel() } }
        fun cancel() {
            if (cancelled) return
            cancelled = true; audioLevelActive = false; readyResult = null; done = true; active = false
            cancelDeadline()
            model?.let { modelControl.execute(it::close) }
        }
    }
    /** 恰有一种不可变标准原包，后续不重新归一化或重新分号。 */
    private data class PendingStandardEvent(val sequence: Int, val transcript: TranscriptEvent?, val translation: TranslationEvent?)
    companion object {
        private val LOG = Logger.getLogger(MeetingCoordinator::class.java.name)
        private val HTTP_CODE = Pattern.compile("HTTP[ =:]*(\\d{3})")
        private fun logFailure(step: String, failure: Throwable) {
            var code = ""
            val message = failure.message
            if (message != null) { val match = HTTP_CODE.matcher(message); if (match.find()) code = " HTTP=${match.group(1)}" }
            // 仅本地阶段／异常类型／HTTP 数字，不记录正文、URL、凭据或 Throwable。
            LOG.warning("会议步骤失败 step=$step type=${failure.javaClass.simpleName}$code")
        }
    }
}
