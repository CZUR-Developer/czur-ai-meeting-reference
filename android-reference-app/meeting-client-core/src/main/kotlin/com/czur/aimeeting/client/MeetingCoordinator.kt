package com.czur.aimeeting.client

import com.czur.aimeeting.client.display.MeetingDisplayState
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider
import com.czur.aimeeting.client.result.ResultActionAccess
import com.czur.aimeeting.client.result.ResultOptions
import com.czur.aimeeting.client.server.*
import com.czur.aimeeting.model.*
import java.io.IOException
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
 * 停止记录只覆盖尾部确认后的发布／同键 Stop／实际 READY，不建立实时持久队列或定时恢复。
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

    private fun setupCapabilities(): Set<String> = if (runtime.usesServerModelMaterials()) adapters.setupCapabilities()
        else java.util.Set.copyOf(runtime.localCapabilities())
    override fun discover(callback: MeetingClient.Callback<MeetingDiscovery>) {
        requireOpen()
        io.execute {
            try {
                LOG.info("部署能力发现开始")
                val deployment = servers.apply(config).discover()
                val available = HashSet(deployment.capabilities())
                available.retainAll(setupCapabilities())
                LOG.info("部署能力发现完成")
                notifyClient { callback.onSuccess(MeetingDiscovery(deployment, available)) }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("部署能力发现", failure)
                notifyClient { callback.onError("读取部署能力失败") }
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
            IdentityResult.readFailed("读取登录状态失败，请稍后重试")
        }
    override fun login(username: String, password: String, callback: MeetingClient.Callback<ReferenceLoginSession>) {
        requireOpen()
        io.execute {
            val identityProvider = config.identityProvider()
            if (identityProvider !is ReferenceIdentityProvider) {
                notifyClient { callback.onError("请使用当前身份来源的应用登录入口") }
                return@execute
            }
            try {
                LOG.info("终端用户登录开始")
                val login = HttpMeetingSessionClient(config.serverUrl()).login(username, password)
                identityProvider.updateSession(IdentitySession(login.accessToken(), username))
                LOG.info("终端用户登录完成")
                notifyClient { callback.onSuccess(login) }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("终端用户登录", failure)
                notifyClient { callback.onError("登录失败，请检查账号和部署地址") }
            }
        }
    }
    @Synchronized override fun start(options: MeetingOptions, listener: MeetingListener): MeetingSession {
        requireOpen()
        check(!resumingFinalization && (current == null || current!!.done)) { "已有会议或停止收尾正在进行" }
        val session = Session(options, listener)
        current = session
        enqueue { session.begin() }
        return session
    }
    @Synchronized override fun resumeFinalization(checkpointId: String, callback: MeetingClient.Callback<MeetingResult>) {
        requireOpen()
        val store = config.stoppedMeetingStore()
        if (store.isEmpty) { notifyClient { callback.onError("当前部署未装配停止记录续办") }; return }
        if (resumingFinalization || current != null && !current!!.done) {
            notifyClient { callback.onError("已有会议或停止收尾正在进行") }
            return
        }
        resumingFinalization = true
        io.execute {
            var result: MeetingResult? = null
            var error: String? = null
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
                result = finishSavedFinalization(checkpoint, store.get(), server, config.artifactPublisher(),
                    BooleanSupplier { closed }, wallClockMs, Runnable {})
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("续办原场停止收尾", failure)
                error = "原场收尾尚未完成，请检查登录和连接后再次续办"
            } finally { synchronized(this) { resumingFinalization = false } }
            // 先释放互斥，再回调，允许宿主在回调中开始下一场。
            val actual = result
            val message = error
            if (actual != null) notifyClient { callback.onSuccess(actual) }
            else if (message != null) notifyClient { callback.onError(message) }
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
        acceptedGenTime: LongSupplier, onStopAccepted: Runnable): MeetingResult? {
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
        /** 含失败在内，同场只读取一次 sealed 事实，仅 IO 队列访问。 */
        private var audioArtifactRead = false
        private var audioArtifactFailure: IOException? = null
        private var publicationContext: MeetingArtifactPublisher.Context? = null
        private var publicationConfirmed = false
        private var stoppedCheckpoint: StoppedMeetingCheckpoint? = null
        private var genTime: Long? = null
        @Volatile private var readyResult: MeetingResult? = null

        private fun notifyListener(task: Runnable) { notifyClient { if (!cancelled) task.run() } }
        private fun publish(message: String) {
            if (phase != lastLoggedPhase) { LOG.info("会议状态 $phase：$message"); lastLoggedPhase = phase }
            val snapshot = MeetingSessionState(phase, meetingId, available, savedEvents, active, canStop, message)
            notifyListener { listener.onState(snapshot) }
        }
        private fun showDisplay() { val snapshot = display.snapshot(); notifyListener { listener.onDisplay(snapshot) } }
        fun begin() {
            if (cancelled || closed) return
            if (options.retainAudio() && (!runtime.supportsRetainedAudio() || !config.artifactPublisher().supportsAudioPublication())) {
                failStart("本场音频保留需要装配同源录音与产物发布")
                return
            }
            publish("正在确认登录状态并创建会议…")
            io.execute {
                var step = "身份读取"
                try {
                    val identity = readIdentity()
                    if (identity.status() != IdentityResult.Status.LOGGED_IN) {
                        LOG.info("会议开始身份未就绪 state=${identity.status()}")
                        enqueue { failStart(identity.message()) }
                        return@execute
                    }
                    sessionConfig = config.forSession(identity.session()!!)
                    server = servers.apply(sessionConfig)
                    LOG.info("本场身份已确认")
                    step = "能力发现"
                    val deployment = server.discover()
                    val setup = HashSet(deployment.capabilities())
                    setup.retainAll(setupCapabilities())
                    requireCapabilities(setup)
                    LOG.info("本场部署能力已确认")
                    step = "创建会议"
                    val created = server.createMeeting(options)
                    val granted = HashSet<String>()
                    for (capability in options.requestedCapabilities())
                        if (setup.contains(capability) && created.capabilities().canUse(capability, setup)) granted.add(capability)
                    requireCapabilities(granted)
                    LOG.info("会议创建及必需能力已确认")
                    step = "模型材料/工厂选择"
                    val grant = if (runtime.usesServerModelMaterials()) server.requestModelSession(created.meetingId()) else null
                    val local = HashSet(if (runtime.usesServerModelMaterials()) adapters.realtimeCapabilities(grant!!.adapterId()) else runtime.localCapabilities())
                    if (setupCapabilities().contains(MeetingCapabilityState.SUMMARY)) local.add(MeetingCapabilityState.SUMMARY)
                    val enabled = HashSet<String>()
                    for (capability in options.requestedCapabilities())
                        if (local.contains(capability) && created.capabilities().canUse(capability, local)) enabled.add(capability)
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
                    enqueue { failStart("创建或启动会议失败，请检查本场能力与模型配置") }
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
                // 闭包冻结原场 Server 和 ID，HTTP 由 Runtime 续接线程执行，不占 state／modelControl。
                val frozenServer = server
                val frozenMeetingId = created.meetingId()
                val reconnector = if (grant == null) null else ModelSessionReconnector {
                    val next = frozenServer.requestModelSession(frozenMeetingId)
                    val fresh = adapters.create(next.adapterId())
                    LOG.fine("本场模型续接材料及新解析实例已准备")
                    ModelSessionConnection(next, fresh)
                }
                model = runtime.open(grant, adapter, TranscriptListener { event -> enqueue { transcript(event) } },
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
                                    LOG.warning("本场音频输入终止 code=$code")
                                    notifyListener { listener.onAudioError(code, message) }
                                    publish("音频输入已结束，请检查权限或音频连接后停止本场会议")
                                    maybeComplete()
                                }
                            }
                        }
                        override fun onError(message: String) {
                            enqueue {
                                if (!done) {
                                    modelFinished = true; audioLevelActive = false
                                    LOG.warning("本场模型运行时终止")
                                    publish("模型会话已结束，请检查连接或停止本场会议")
                                    maybeComplete()
                                }
                            }
                        }
                    }, options.retainAudio(), reconnector)
                active = true; audioLevelActive = true; canStop = runtime.waitsForModelFinish()
                acceptingPresentation = options.presentationCapture()
                phase = MeetingSessionState.Phase.RUNNING
                val info = MeetingStarted(id, startedAtMs, created.maxMeetingDurationMs(), available)
                notifyListener { listener.onStarted(info) }
                showDisplay(); publish("会议已开始，正在接收标准字幕"); LOG.info("会议已开始")
                modelControl.execute {
                    if (cancelled || closed || done) return@execute
                    try { model!!.start() }
                    catch (failure: RuntimeException) { logFailure("启动模型运行时", failure); enqueue { failStart("打开模型会话失败") } }
                }
            } catch (failure: RuntimeException) { logFailure("创建模型会话", failure); failStart("打开模型会话失败") }
        }
        private fun transcript(event: TranscriptEvent) {
            if (done || modelFinished) return
            val standard = if (available.contains(MeetingCapabilityState.SPEAKER)) event else event.withoutSpeaker()
            val nowMs = monotonicMs.asLong
            if (standard.text().isNotBlank()) notifyListener { listener.onTranscriptActivity(nowMs) }
            finals.apply(standard); display.apply(standard, nowMs); showDisplay(); scheduleDeadline()
            val number = ++sequence
            io.execute { saveStandardEvent(PendingStandardEvent(number, standard, null)) }
        }
        private fun translation(event: TranslationEvent) {
            if (done || modelFinished || !available.contains(MeetingCapabilityState.TRANSLATION)) return
            display.apply(event, monotonicMs.asLong); showDisplay(); scheduleDeadline()
            val number = ++sequence
            io.execute { saveStandardEvent(PendingStandardEvent(number, null, event)) }
        }
        /** 先接纳原包再确认队首，失败不跳过顺序。 */
        private fun saveStandardEvent(event: PendingStandardEvent) {
            if (closed || cancelled) return
            pendingStandardEvents.addLast(event)
            try { savePendingStandardEvents() }
            catch (failure: IOException) {
                logFailure("保存标准字幕或译文", failure)
                enqueue { if (!done) publish("标准字幕或译文尚未确认，后续事件或停止时继续提交") }
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
                        publish(if (phase == MeetingSessionState.Phase.RUNNING) "标准字幕或译文已保存" else "正在等待本场正常收口")
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
            publish(if (stopRequested) "模型最终输出已接收，正在收口" else "模型已完成，可结束会议")
            maybeComplete()
        }
        override fun submitPresentation(offsetMs: Long, jpeg: ByteArray) {
            submitPresentation(offsetMs, jpeg, object : MeetingClient.Callback<Void?> {
                override fun onSuccess(result: Void?) {}
                override fun onError(message: String) {}
            })
        }
        override fun submitPresentation(offsetMs: Long, jpeg: ByteArray, callback: MeetingClient.Callback<Void?>) {
            val frame = PresentationFrame(offsetMs, jpeg)
            enqueue {
                if (done || !acceptingPresentation) notifyListener { callback.onError("本场当前不接收演示截图") }
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
                presentationFailure(callback, "演示截图超出1MiB传输预算，请缩小图片；字幕会议继续")
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
                presentationFailure(callback, "演示截图保存未确认；字幕会议继续")
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
        private fun presentationFailure(callback: MeetingClient.Callback<Void?>, message: String) {
            enqueue { if (!done) publish(message); notifyListener { callback.onError(message) } }
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
                cancelDeadline(); publish("正在等待模型最终字幕…")
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
                    enqueue { canStop = true; publish("停止模型输入失败，请再次确认停止") }
                }
            }
        }
        private fun maybeComplete() {
            if (done || completionQueued || !stopRequested || !stopInputSent || runtime.waitsForModelFinish() && !modelFinished) return
            completionQueued = true; phase = MeetingSessionState.Phase.FINALIZING
            publish("正在保存本场最终结果…")
            var input: SummaryInput? = null
            if (!audioFailed && available.contains(MeetingCapabilityState.SUMMARY) && options.summaryEnabled()) {
                try { input = finals.input(options.sourceLanguage()) }
                catch (empty: IllegalArgumentException) {
                    // 静音／短会无有效 final 时不伪造总结、不请求模型，继续用量／录音／Stop。
                    LOG.info("本场没有有效最终字幕，跳过纪要并继续正常停止")
                    publish("没有最终字幕，无法生成纪要；继续结束会议")
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
                    LOG.info("标准纪要生成开始")
                    val grant = if (runtime.usesServerModelMaterials()) server.requestSummarySession(id) else null
                    val adapter = if (grant == null) null else adapters.createSummary(grant.adapterId())
                    server.submitSummary(id, runtime.summarize(grant, adapter, input))
                    summarySubmitted = true; LOG.info("标准纪要已提交")
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
            notifyListener { listener.onResult(finalResult) }
            publish("本场结果已就绪"); LOG.info("会议结果已就绪")
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
            completionQueued = false; canStop = true; phase = MeetingSessionState.Phase.STOP_PENDING; publish(message)
        }
        private fun endWithoutDelivery(message: String) {
            if (done) return
            done = true; active = false; phase = MeetingSessionState.Phase.ENDED
            publish(message); LOG.info("会议已停止，未主动交付结果")
        }
        private fun failStart(message: String) {
            if (done) return
            audioLevelActive = false; done = true; active = false; canStop = false; phase = MeetingSessionState.Phase.FAILED
            cancelDeadline()
            model?.let { modelControl.execute(it::close) }
            publish(message)
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
                if (result == null) { notifyListener { callback.onError("本场结果尚未就绪") }; return@enqueue }
                io.execute {
                    if (closed || cancelled) return@execute
                    try {
                        LOG.fine("同场结果访问开始 step=$step")
                        val value = call(result)
                        notifyListener { callback.onSuccess(value) }
                    } catch (failure: Exception) {
                        if (failure !is IOException && failure !is RuntimeException) throw failure
                        logFailure(step, failure)
                        notifyListener { callback.onError("${step}失败，请再次执行") }
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
