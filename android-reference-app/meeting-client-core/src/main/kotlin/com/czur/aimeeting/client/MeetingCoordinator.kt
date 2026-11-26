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
import java.util.function.Consumer
import java.util.function.BooleanSupplier
import java.util.function.Function
import java.util.function.LongSupplier
import java.util.function.Supplier
import java.util.logging.Logger
import java.util.regex.Pattern

/**
 * 平台无关的唯一正常会议编排。状态／显示／deadline 只在 state 队列，HTTP 在 io，
 * start／stop／close 在 modelControl 保序，使挂起 HTTP 不拖停音源；心跳另用独立单线程，
 * 不受业务 io 队列积压影响。SDK／Mock 只注入运行时。纪要改为服务端生成：停止时只上报
 * 语言列表，不再在设备侧调用文本模型或提交纪要正文。
 * 单客户端最多一场会议。采用私有持久端口时，标准事件先保存原包再出站，冷启沿原序续交。
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
    /**
     * 心跳专用单线程执行器：与字幕／翻译共用的 io 队列分离，慢业务请求不会把
     * 心跳挤过服务端失联窗口。同一时刻至多一个心跳在途，宿主高频触发不会堆积。
     */
    private val heartbeatExecutor = Executors.newSingleThreadExecutor()
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
    @Synchronized override fun resumeFinalization(checkpointId: String,
        callback: MeetingClient.Callback<FinalizationResult>) {
        requireOpen()
        val store = config.stoppedMeetingStore()
        if (store.isEmpty) { notifyClient { callback.onError(MeetingError(MeetingErrorCode.STOP_STORE_MISSING)) }; return }
        if (resumingFinalization || recovering || current != null && !current!!.done) {
            notifyClient { callback.onError(MeetingError(MeetingErrorCode.BUSY)) }
            return
        }
        resumingFinalization = true
        io.execute {
            var outcome: SavedFinalizationOutcome? = null
            var errorCode: MeetingErrorCode? = null
            try {
                if (closed) return@execute
                config.checkRequestAllowed()
                var checkpoint = store.get().read(checkpointId).orElseThrow { IOException("原场停止记录不存在") }
                val record = durableRecoveryRecord(checkpointId)
                config.checkRequestAllowed()
                if (record != null && (record.meetingId() != checkpointId ||
                        record.serverUrl() != checkpoint.context().serverUrl()))
                    throw UnverifiedStandardEventsException()
                val pending = record?.let(PendingStandardEvents::open)
                val packets = pending?.pending().orEmpty()
                if (packets.isNotEmpty() && checkpoint.phase() != StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING)
                    throw UnverifiedStandardEventsException()
                checkpoint = renewFinalizationIdentity(checkpoint, store.get())
                val original = checkpoint.context()
                // 原场事实仍来自 Store；身份扩展已核同账号，不重新发现／建会／调用模型。
                val frozenConfig = MeetingClientConfig(original.serverUrl(), original.accessToken(),
                    original.clientIdentity(), config.profile(), config.callbackExecutor()).withRequestGuard {
                    config.checkRequestAllowed()
                    !closed
                }
                val server = servers.apply(frozenConfig)
                if (pending != null) replayPendingEvents(checkpointId, pending, packets, server)
                LOG.info("原场停止收尾续办开始 phase=${checkpoint.phase()} deliversResult=${checkpoint.deliversResult()}")
                if (checkpoint.phase() == StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING) {
                    // 恢复流程先写停止记录、后提交冻结用量；若在这之间被打断，续办时快照会
                    // 一直缺用量并 409。这里补交同场同值事实，正常停止已提交过时由服务端幂等处理。
                    LOG.info("原场停止收尾补交冻结采音用量 durationMs=${checkpoint.context().frozenAudioDurationMs()}")
                    server.submitUsage(checkpoint.checkpointId(), checkpoint.context().frozenAudioDurationMs())
                }
                // 停止记录带会前语言快照：发布确认后如未上报纪要语言，续办会先补发再 Stop；
                // 已上报（或旧记录没有快照）不再请求，旧记录行为与修复前一致。
                outcome = finishSavedFinalization(checkpoint, store.get(), server, config.artifactPublisher(),
                    BooleanSupplier { closed }, wallClockMs, Runnable {}, config::checkRequestAllowed)
                if (outcome != null && record != null && pending != null &&
                    !hasScopedSummaryPending(store.get(), checkpointId)) {
                    config.checkRequestAllowed()
                    discardRecoveredScene(checkpointId, record)
                }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("续办原场停止收尾", failure)
                outcome = null
                errorCode = stopRejection(failure) ?: MeetingErrorCode.FINALIZATION_FAILED
            } finally { synchronized(this) { resumingFinalization = false } }
            // 先释放互斥，再回调，允许宿主在回调中开始下一场。
            when (val actual = outcome) {
                is SavedFinalizationOutcome.Delivered -> notifyClient {
                    callback.onSuccess(FinalizationResult(checkpointId, actual.result))
                }
                SavedFinalizationOutcome.EndedWithoutDelivery -> notifyClient {
                    // 明确完成回调，而不是静默成功；调用方据 delivered()==false 结束本地记录。
                    callback.onSuccess(FinalizationResult(checkpointId, null))
                }
                null -> errorCode?.let { code -> notifyClient { callback.onError(MeetingError(code)) } }
            }
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
                config.checkRequestAllowed()
                val existingStop = stoppedStore.get().read(meetingId).orElse(null)
                if (existingStop != null && existingStop.phase() != StoppedMeetingCheckpoint.Phase.READY)
                    throw IOException("原场已有未完成停止记录，应由停止续办收口")
                if (existingStop != null) {
                    // 停止记录已 READY：服务端早已收口，本次只清可能残留的现场，不重复提交
                    // 用量／纪要语言，也不再发布、Stop 或读取清单；现场缺失或读不出来同样按已完成返回。
                    LOG.info("原场停止记录已 READY，只清理残留现场")
                    recoveryStore.get().read(meetingId).orElse(null)?.let { record ->
                        config.checkRequestAllowed()
                        if (record.meetingId() != meetingId || record.serverUrl() != config.serverUrl() ||
                            record.serverUrl() != existingStop.context().serverUrl()) throw UnverifiedStandardEventsException()
                        discardRecoveredScene(meetingId, record)
                    }
                    outcome = RecoveryResult(meetingId, null)
                } else {
                    val record = recoveryStore.get().read(meetingId)
                        .orElseThrow { IOException("原场现场不存在或原账号已改变") }
                    if (record.meetingId() != meetingId || record.serverUrl() != config.serverUrl())
                        throw UnverifiedStandardEventsException()
                    val pending = if (usesDurableEvents()) PendingStandardEvents.open(record) else null
                    val packets = pending?.pending().orEmpty()
                    val identity = config.identityProvider().currentIdentity()
                    if (identity.status() != IdentityResult.Status.LOGGED_IN) throw IOException("原场账号身份尚未就绪")
                    val sessionConfig = config.forSession(identity.session()!!)
                    if (record.serverUrl() != sessionConfig.serverUrl()) throw IOException("原场服务地址已改变")
                    val server = servers.apply(sessionConfig)
                    if (pending != null) replayPendingEvents(meetingId, pending, packets, server)
                    val intentStore = recoveryStore.get() as? MeetingStopIntentStore
                    val intent = intentStore?.readExplicitStop(meetingId)
                    // 录音只为续办读取文件持久前缀；估算时长不冒充正常停止的计数门原值。
                    val audio = intent?.sealedAudio ?: RecoveredAudioFacts.sealedArtifact(meetingId, record.audioFile())
                    if (audio.meetingId() != meetingId || audio.file() != record.audioFile()
                        || !audio.file().isFile || audio.file().length() != audio.encodedBytes()
                        || intent?.frozenUsageMs?.let { it != audio.durationMs() } == true) {
                        throw IOException("原场恢复录音与已冻结用量不一致")
                    }
                    // A crash before sealing can recover the durable prefix. Once chosen, that
                    // value is frozen on disk before any request, even if its usage ACK is lost.
                    if (intent != null) intentStore.saveFrozenStop(meetingId,
                        MeetingStopIntent(intent.stopKey, audio.durationMs(), audio))
                    val context = MeetingArtifactPublisher.Context(meetingId, audio, audio.durationMs(), sessionConfig)
                    val candidate = (if (intent == null) StoppedMeetingCheckpoint.pending(context, record.activeDelivery())
                        else StoppedMeetingCheckpoint.pendingWithKey(context, record.activeDelivery(), intent.stopKey))
                        .withSummaryLanguages(recoverySummaryLanguages(record))
                    config.checkRequestAllowed()
                    val checkpoint = if (intent == null) candidate else stoppedStore.get().save(candidate)
                    // 发布快照要求本场冻结用量已提交；正常停止在发布前提交，续办必须补同一事实。
                    config.checkRequestAllowed()
                    LOG.info("原场冻结用量提交开始 durationMs=${audio.durationMs()}")
                    server.submitUsage(meetingId, audio.durationMs())
                    LOG.info("原场冻结用量已确认")
                    config.checkRequestAllowed()
                    val saved = if (intent == null) stoppedStore.get().save(checkpoint) else checkpoint
                    LOG.info("原场现场续办开始 phase=${checkpoint.phase()} deliversResult=${checkpoint.deliversResult()}")
                    val finished = finishSavedFinalization(saved, stoppedStore.get(), server,
                        sessionConfig.artifactPublisher(), BooleanSupplier { closed }, wallClockMs,
                        Runnable {}, config::checkRequestAllowed)
                    // 停止记录已保存为 READY；先清现场再回调，失败时现场保留给下次扫描重试。
                    // 被关闭打断时不删现场，留待下次扫描从停止记录继续。
                    if (finished != null && !hasScopedSummaryPending(stoppedStore.get(), meetingId)) {
                        config.checkRequestAllowed()
                        discardRecoveredScene(meetingId, record)
                    }
                    outcome = when (finished) {
                        is SavedFinalizationOutcome.Delivered -> RecoveryResult(meetingId, finished.result)
                        SavedFinalizationOutcome.EndedWithoutDelivery -> RecoveryResult(meetingId, null)
                        null -> null
                    }
                }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("原场现场续办", failure)
                errorCode = stopRejection(failure) ?: MeetingErrorCode.RECOVERY_FAILED
            } finally { synchronized(this) { recovering = false } }
            // 先释放互斥，再回调，允许宿主在回调中处理下一场。
            val actual = outcome
            val code = errorCode
            if (actual != null) notifyClient { callback.onSuccess(actual) }
            else if (code != null) notifyClient { callback.onError(MeetingError(code)) }
        }
    }

    private fun usesDurableEvents(): Boolean =
        (config.meetingRecoveryStore().orElse(null) as? MeetingStopIntentStore)?.usesDurableStandardEvents() == true

    private fun durableRecoveryRecord(meetingId: String): CapturingMeeting? =
        if (usesDurableEvents()) config.meetingRecoveryStore().get().read(meetingId).orElse(null) else null

    private fun replayPendingEvents(meetingId: String, journal: PendingStandardEvents,
        packets: List<StandardEventPacket>, server: MeetingServerClient) {
        for (packet in packets) {
            config.checkRequestAllowed()
            if (closed) throw IOException("原场客户端已关闭")
            server.appendStoredStandardEvent(meetingId, packet.body)
            config.checkRequestAllowed()
            journal.acknowledge(packet)
        }
    }
    private fun checkStandardEventScope() {
        config.checkRequestAllowed()
        (config.meetingRecoveryStore().orElse(null) as? MeetingStopIntentStore)?.checkStandardEventScope()
    }

    /** 删现场指针失败时保留回执，下次只核同场 ACK 并继续清理。 */
    private fun discardRecoveredScene(meetingId: String, record: CapturingMeeting) {
        val store = config.meetingRecoveryStore().get()
        if (usesDurableEvents()) {
            record.discardAcknowledgedStandardEvents(::checkStandardEventScope) { store.delete(meetingId) }
            return
        }
        val journal = record.journalFile()
        if (journal != null && journal.exists() && !journal.delete()) {
            LOG.warning("原场字幕日志删除失败，保留现场等待下次清理")
            return
        }
        try { store.delete(meetingId) }
        catch (failure: Exception) {
            if (failure !is IOException && failure !is RuntimeException) throw failure
            LOG.warning("原场现场清理失败 class=${failure.javaClass.simpleName}")
        }
    }
    /**
     * 原场现场只保存会前语言快照；正文与模型调用都由服务端负责。
     * 语言列表规则沿用 SummaryLanguages，失败只告警，不阻断原场发布与 Stop。
     */
    private fun recoverySummaryLanguages(record: CapturingMeeting): List<String>? =
        if (!record.summaryEnabled()) null
        else SummaryLanguages.from(record.sourceLanguage(), record.targetLanguage())
    /**
     * 每次续办只调用一次同账号身份扩展，先持久保存新材料，再允许外部发布／Stop。
     *
     * <p>READY 记录通常只读已收口的结果，不需要再核验凭据，保持原有行为直接返回；
     * 但还欠一次纪要语言上报时例外：补报走原账号 Bearer，记录里的令牌过期会一直 401，
     * 这条 READY 补报路径永远补不上。此时必须核验原账号归属并用新令牌落盘，让后续
     * 纪要请求使用刷新后的凭据。</p>
     *
     * <p>READY 的核验失败只降级为“本轮仍用原凭据”：服务端已经收口，不能因为一次身份
     * 核验挡住读取结果；未上报状态保留在记录里，下一轮扫描会再核验、再补报。其它阶段
     * 的核验失败仍抛给续办调用方，保持原有失败语义。</p>
     */
    private fun renewFinalizationIdentity(checkpoint: StoppedMeetingCheckpoint, store: StoppedMeetingStore): StoppedMeetingCheckpoint {
        val ready = checkpoint.phase() == StoppedMeetingCheckpoint.Phase.READY
        // READY 且不需要补报：只读已收口结果，保持原行为不核验凭据。
        if (ready && !needsSummaryReport(checkpoint)) return checkpoint
        return try {
            val original = IdentitySession(checkpoint.context().accessToken(), "")
            val identity = config.identityProvider().identityForFinalization(checkpoint.checkpointId(), original)
            if (identity.status() != IdentityResult.Status.LOGGED_IN) throw IOException("原场账号身份尚未就绪")
            val accessToken = identity.session()!!.accessToken()
            if (accessToken == original.accessToken()) checkpoint
            else store.save(checkpoint.withAccessToken(accessToken)).also {
                LOG.info("原场停止收尾访问凭据已更新，原会议及确认进度保持")
            }
        } catch (failure: Exception) {
            // 能走到这里的 READY 一定是欠上报的 READY：核验失败不挡读取已收口的结果，
            // 未上报状态保留，下一轮扫描会重新核验并补报。
            if (!ready || failure !is IOException && failure !is RuntimeException) throw failure
            logFailure("原场停止收尾身份核验", failure)
            LOG.warning("READY 续办身份核验失败，本轮沿用原凭据读取结果，记录保留待下轮")
            checkpoint
        }
    }
    /** 停止记录还欠一次纪要语言上报：带会前语言快照且尚未落盘“已上报”。 */
    private fun needsSummaryReport(checkpoint: StoppedMeetingCheckpoint): Boolean =
        checkpoint.summaryLanguages() != null && !checkpoint.summaryReported()

    private fun hasScopedSummaryPending(store: StoppedMeetingStore, id: String): Boolean {
        val checkpoint = store.read(id).orElse(null) ?: return false
        return checkpoint.context().accessToken().startsWith("stop1~") && needsSummaryReport(checkpoint)
    }
    /** 已确认尾部共用唯一流程，真实 ACK 先保存；READY 也读实际清单，不由本地阶段伪造。 */
    private sealed interface SavedFinalizationOutcome {
        class Delivered(val result: MeetingResult) : SavedFinalizationOutcome
        /** Stop 成功且原策略不交付；只推进本地阶段，不读取清单。 */
        data object EndedWithoutDelivery : SavedFinalizationOutcome
    }
    private fun finishSavedFinalization(initial: StoppedMeetingCheckpoint, store: StoppedMeetingStore,
        server: MeetingServerClient, publisher: MeetingArtifactPublisher, aborted: BooleanSupplier,
        acceptedGenTime: LongSupplier, onInputAccepted: Runnable,
        checkAllowed: () -> Unit = {}): SavedFinalizationOutcome? {
        if (aborted.asBoolean) return null
        checkAllowed()
        var checkpoint = store.save(initial)
        // 进入本轮时 Stop 已受理（STOP_ACCEPTED／READY）：上一轮在 Stop 前上报纪要若失败，
        // 记录就会停在已受理 Stop 的阶段，本轮不再走 PUBLISHED 分支，必须按同一幂等步骤
        // 补报一次，否则该场永久缺纪要。同一轮首次停止已在 Stop 前尝试过一次，此时不立即
        // 重发，保持“同一轮一次请求”的既有语义。
        val resumesAcceptedStop =
            checkpoint.phase().ordinal >= StoppedMeetingCheckpoint.Phase.STOP_ACCEPTED.ordinal
        if (aborted.asBoolean) return null
        if (checkpoint.phase() == StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING) {
            if (!publisher.supportsAudioPublication()) throw IOException("停止记录续办需要原场产物发布器")
            LOG.info("原场封口音频发布开始")
            publisher.publish(checkpoint.context())
            checkAllowed()
            checkpoint = store.save(checkpoint.published())
            LOG.info("原场封口音频发布已确认并保存")
        }
        if (aborted.asBoolean) return null
        if (checkpoint.phase() == StoppedMeetingCheckpoint.Phase.PUBLISHED) {
            checkAllowed()
            // 会前语言快照存在且尚未上报时，在发布确认后、Stop 前补发纪要请求；
            // 成功立即落盘，进程再次续办不会重复请求，失败只告警并保留未上报状态。
            checkpoint = reportCheckpointSummary(checkpoint, store, server)
            if (aborted.asBoolean) return null
            checkAllowed()
            LOG.info("后端原键 Stop 开始")
            val id = checkpoint.checkpointId()
            val stop = try {
                server.stopMeeting(id, checkpoint.stableStopKey())
            } catch (failure: MeetingHttpStatusException) {
                if (!checkpoint.deliversResult() || failure.status != 409 ||
                    failure.errorCode != "INVALID_MEETING_STATE") throw failure
                if (aborted.asBoolean) return null
                LOG.info("原键 Stop 返回 INVALID_MEETING_STATE，核验原场终态")
                val observedAt = wallClockMs.asLong
                val result = server.readReconciledResult(id, observedAt)
                if (result.meetingId() != id || result.genTime() != observedAt)
                    throw IOException("终态核验结果不属于本场或结果访问时间不一致")
                if (aborted.asBoolean) return null
                checkAllowed()
                store.save(checkpoint.reconciledReady(observedAt))
                if (aborted.asBoolean) return null
                onInputAccepted.run()
                LOG.info("原场成功终态已核验并保存")
                return SavedFinalizationOutcome.Delivered(result)
            }
            if (!stop.accepted())
                throw IOException("后端尚未受理本场停止")
            checkAllowed()
            checkpoint = store.save(checkpoint.stopAccepted(acceptedGenTime.asLong))
            LOG.info("后端 Stop 已受理并保存，结果尚需确认")
        }
        if (aborted.asBoolean) return null
        checkAllowed()
        onInputAccepted.run()
        if (resumesAcceptedStop) {
            // Stop 已被受理后的续办：读取清单／推进 READY 之前补一次纪要上报。
            // 服务端允许 Stop 之后仍受理纪要请求；已上报或无语言快照时该步骤直接返回。
            checkpoint = reportCheckpointSummary(checkpoint, store, server)
            if (aborted.asBoolean) return null
        }
        if (!checkpoint.deliversResult()) {
            // NO_ACTIVE_DELIVERY 只收口到 ENDED，不读清单；本地阶段仍记录到 Stop 已受理。
            if (checkpoint.phase() != StoppedMeetingCheckpoint.Phase.READY) store.save(checkpoint.ready())
            LOG.info("原场已停止，未主动交付结果")
            return SavedFinalizationOutcome.EndedWithoutDelivery
        }
        LOG.info("读取原场实际 READY 清单开始")
        // The first manifest after Stop must prove the same origin as a saved READY read.
        val result = server.readSavedReadyResult(checkpoint.checkpointId(), checkpoint.genTime()!!, false)
        checkAllowed()
        if (result.meetingId() != checkpoint.checkpointId() || result.genTime() != checkpoint.genTime())
            throw IOException("结果清单与原场不一致")
        if (checkpoint.phase() != StoppedMeetingCheckpoint.Phase.READY) store.save(checkpoint.ready())
        return SavedFinalizationOutcome.Delivered(result)
    }
    /**
     * 幂等的纪要语言补报步骤：仅当停止记录带会前语言快照且尚未上报时请求一次，
     * 服务端受理后立刻把“已上报”写回 Store。已上报或旧记录没有快照（null）时直接返回，
     * 因此发布确认后、Stop 前与 Stop 已受理后的续办都可安全调用，不会重复请求。
     * 请求失败只告警并保留未上报状态，由下一次续办重试，不阻断 Stop／结果读取。
     */
    private fun reportCheckpointSummary(checkpoint: StoppedMeetingCheckpoint,
        store: StoppedMeetingStore, server: MeetingServerClient): StoppedMeetingCheckpoint {
        if (checkpoint.summaryReported()) return checkpoint
        val languages = checkpoint.summaryLanguages() ?: return checkpoint
        LOG.info("停止记录纪要语言补报开始 languages=${languages.size}")
        if (!requestSummaryQuietly(server, checkpoint.checkpointId(), languages)) return checkpoint
        return store.save(checkpoint.withSummaryReported())
    }
    private fun requireOpen() { check(!closed) { "会议客户端已关闭" } }
    private fun enqueue(task: Runnable) {
        try { state.execute(task) } catch (rejected: RejectedExecutionException) { if (!closed) throw rejected }
    }
    private fun notifyClient(task: Runnable) { config.callbackExecutor().execute { if (!closed) task.run() } }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        // 销毁后不再接受新心跳；已在途的一次允许自然结束，不中断 HTTP。
        heartbeatExecutor.shutdown()
        enqueue {
            current?.cancel()
            current?.notifyAfterPendingWork()
            io.shutdown()
            modelControl.shutdown()
            state.shutdown()
        }
    }

    /** 本场是否真正获授服务端纪要能力；本地/直连部署为 false，不上报语言。 */
    private var serverSummaryCapable = false

    private inner class Session(private val options: MeetingOptions, private val listener: MeetingListener) : MeetingSession {
        @Volatile private lateinit var server: MeetingServerClient
        @Volatile private lateinit var sessionConfig: MeetingClientConfig
        private val display = MeetingDisplayState()
        @Volatile var done = false
        @Volatile private var cancelled = false
        private val accountCredentialRetired = AtomicBoolean()
        /** 状态队列关门后，已排队电平也不能再次点亮 UI。 */
        @Volatile private var audioLevelActive = false
        @Volatile private var meetingId: String? = null
        /**
         * 仅心跳线程读取。开始后置 true；服务端受理 Stop（ACK）、失败、结果、
         * 取消／销毁时置 false。用户点停止只进入收尾，不会关闭它，避免收尾中的
         * 会议因心跳断档被服务端判成弃场。
         */
        @Volatile private var heartbeatAllowed = false
        /** 原子 in-flight：已有心跳未返回时直接跳过本次触发，不排队。 */
        private val heartbeatInFlight = AtomicBoolean(false)
        private val id: String get() = meetingId!!
        private var available = emptySet<String>()
        private var model: ModelSession? = null
        private var phase = MeetingSessionState.Phase.CREATING
        private var lastLoggedPhase: MeetingSessionState.Phase? = null
        private var active = false
        private var canStop = false
        private var stopRequested = false
        private var stopInputSent = false
        private var stopInputInFlight = false
        private var beforeStopStarted = false
        private var beforeStopCompleted = false
        private var modelFinished = false
        @Volatile private var audioFailed = false
        private var completionQueued = false
        private var keepExplicitStop = false
        private var stopGrant: MeetingStopGrant? = null
        private var hostExitInputStopped: Consumer<Boolean>? = null
        private var stopFreezeSaved = false
        private var savingFrozenStop = false
        @Volatile private var explicitStopKey: String? = null
        private var pendingStopError: MeetingErrorCode? = null
        private var closeNotification: Runnable? = null
        private var acceptingPresentation = false
        private var sequence = 0
        private var savedEvents = 0
        /**
         * 服务端已按部署正文预算拒绝标准事件（HTTP 413／CONTENT_TOO_LARGE）。
         * 这是确定性拒绝，不再重试同场标准事件；提示保持到会议结束，避免静默丢数据。
         */
        @Volatile private var contentTooLarge = false
        private var deadline: ScheduledFuture<*>? = null
        /** IO 队列保留已分号原包，失败不跳队首；后续事件／停止重交，不增加磁盘或定时队列。 */
        private val pendingStandardEvents = ArrayDeque<PendingStandardEvent>()
        /** 截图独立序号，仅 ACK 推进；至多保留一个未确认原包，不覆盖或换号。 */
        private var presentationSequence = 0
        private var pendingPresentation: PresentationFrame? = null
        private var frozenAudioDuration = OptionalLong.empty()
        private var usageRead = false
        private var usageSubmitted = false
        /** 本场只在发布确认后上报一次纪要语言；Stop 重试不重复请求。 */
        private var summaryRequested = false
        // 只有服务端生成纪要的部署才有 summary:request；本地/直连部署不上报语言。
        private var summaryServerAvailable = false
        /** 含失败在内，同场只读取一次 sealed 事实，仅 IO 队列访问。 */
        private var audioArtifactRead = false
        private var audioArtifactFailure: IOException? = null
        /** 采音起点落盘的原场现场；结束或确认收口后清理，崩溃时留给宿主续办。 */
        private var recoveryRecord: CapturingMeeting? = null
        private var audioJournal: TranscriptJournal? = null
        private var eventJournal: PendingStandardEvents? = null
        @Volatile private var standardEventWriteFailed = false
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
            // 413 后保持提示可见，直到本场结束；不因后续阶段发布而静默消失。
            val effectiveNotice = notice ?: if (contentTooLarge) MeetingNotice.CONTENT_TOO_LARGE else null
            if (phase != lastLoggedPhase) {
                LOG.info("会议状态 $phase" + (effectiveNotice?.let { " notice=$it" } ?: "") +
                    (errorCode?.let { " error=$it" } ?: ""))
                lastLoggedPhase = phase
            }
            val snapshot = MeetingSessionState(phase, meetingId, available, savedEvents, active, canStop,
                effectiveNotice, errorCode ?: pendingStopError.takeIf { phase == MeetingSessionState.Phase.STOP_PENDING })
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
                    sessionConfig = config.forSession(identity.session()!!).withRequestGuard {
                        config.checkRequestAllowed()
                        !accountCredentialRetired.get()
                    }
                    server = servers.apply(sessionConfig)
                    LOG.info("本场身份已确认")
                    step = "能力发现"
                    val deployment = server.discover()
                    val setup = availableDeploymentCapabilities(deployment)
                    val negotiatedOptions = options.withAvailableCapabilities(setup)
                    requireCapabilities(setup, negotiatedOptions)
                    LOG.info("本场部署能力已确认")
                    step = "创建会议"
                    // 只把本场实际可用的可选能力交给 Create；必需能力仍由原始设置决定。
                    // 本场发言人来自实时 Adapter，跨会议身份来自服务端会后 Provider，二者分开协商。
                    val createOptions = negotiatedOptions
                    val created = server.createMeeting(createOptions)
                    val granted = HashSet<String>()
                    for (capability in createOptions.requestedCapabilities())
                        if (setup.contains(capability) && created.capabilities().canUse(capability, setup)) granted.add(capability)
                    requireCapabilities(granted, createOptions)
                    // 只有本场真正获授服务端纪要能力时才上报语言：本地/直连部署没有 summary:request。
                    summaryServerAvailable = granted.contains(MeetingCapabilityState.SUMMARY_SERVER)
                    serverSummaryCapable = summaryServerAvailable
                    LOG.info("会议创建及必需能力已确认")
                    step = "模型材料/工厂选择"
                    val grant = if (runtime.usesServerModelMaterials()) server.requestModelSession(created.meetingId()) else null
                    val local = HashSet(if (runtime.usesServerModelMaterials()) adapters.realtimeCapabilities(grant!!.adapterId()) else runtime.localCapabilities())
                    if (grant?.translationSession() != null && runtime.supportsTextTranslation() &&
                        adapters.hasTranslation(grant.translationSession()!!.adapterId()))
                        local.add(MeetingCapabilityState.TRANSLATION)
                    // 纪要正文在服务端生成，执行位置不在本机：只要部署声明与 Create 授权就可用，
                    // 不要求本地注册文本总结 Adapter。设备直连兼容期的 summary.structured 仍按
                    // 本机实际注册（注册表声明）参与交集，不复用服务端生成能力冒充。
                    val readyCapabilities = setupCapabilities()
                    if (readyCapabilities.contains(MeetingCapabilityState.SUMMARY_SERVER))
                        local.add(MeetingCapabilityState.SUMMARY_SERVER)
                    if (readyCapabilities.contains(MeetingCapabilityState.SUMMARY))
                        local.add(MeetingCapabilityState.SUMMARY)
                    val enabled = HashSet<String>()
                    for (capability in createOptions.requestedCapabilities())
                        if (capability != MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING &&
                            granted.contains(capability) && local.contains(capability) &&
                            created.capabilities().canUse(capability, local)) enabled.add(capability)
                    // 服务端身份依赖实际选中 Adapter 的本场 speaker；不从临时 speakerId 推断 personId。
                    if (granted.contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING) &&
                        enabled.contains(MeetingCapabilityState.SPEAKER))
                        enabled.add(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING)
                    requireCapabilities(enabled, createOptions)
                    val adapter = if (grant == null) null else adapters.create(grant.adapterId())
                    LOG.info("本场模型材料与工厂已准备")
                    step = "启动客户端输入"
                    server.startClient(created.meetingId())
                    LOG.info("后端客户端输入已启动")
                    if (cancelled || closed) return@execute
                    step = "原场停止授权"
                    val intentStore = config.meetingRecoveryStore().orElse(null) as? MeetingStopIntentStore
                    val scoped = intentStore?.prepareStopGrant(created.meetingId(), sessionConfig.accessToken())
                    enqueue {
                        stopGrant = scoped
                        startModel(created, grant, adapter, java.util.Set.copyOf(enabled))
                    }
                } catch (failure: Exception) {
                    if (failure !is IOException && failure !is RuntimeException) throw failure
                    logFailure(step, failure)
                    enqueue { failStart(MeetingErrorCode.START_FAILED) }
                }
            }
        }
        private fun requireCapabilities(capabilities: Set<String>, negotiated: MeetingOptions) {
            if (!capabilities.containsAll(negotiated.requiredCapabilities())) {
                LOG.warning("本场缺少用户已选择的必需能力 ${negotiated.requiredCapabilities()}")
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
                // onStarted 之后宿主可能立刻按间隔触发第一次心跳，先开放再回调。
                heartbeatAllowed = true
                acceptingPresentation = options.presentationCapture()
                phase = MeetingSessionState.Phase.RUNNING
                val info = MeetingStarted(id, startedAtMs, created.maxMeetingDurationMs(), available,
                    created.heartbeatIntervalMs())
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
            val durable = usesDurableEvents() && server.supportsStoredStandardEvents()
            try {
                val audio = model?.audioArtifactFile()?.orElse(null)
                    ?: if (durable) throw IOException("原场持久录音路径不可用") else return
                val journal = if (durable) null else TranscriptJournal(File(audio.absoluteFile.parentFile, "${id}.jsonl"))
                val record = CapturingMeeting(id, sessionConfig.serverUrl(), sessionConfig.clientIdentity(),
                    audio, journal?.file(), startedAtMs, options.sourceLanguage(),
                    // 只有本场真正获授服务端纪要能力的部署才在续办现场记纪要意图，
                    // 否则续办会给没有 summary:request 的本地/直连部署反复补报。
                    options.summaryEnabled() && summaryServerAvailable,
                    options.retainAudio(),
                    options.resultOptions().policy() != ResultOptions.Policy.NO_ACTIVE_DELIVERY,
                    if (options.mode() == MeetingOptions.Mode.LIVE_TRANSLATION) options.targetLanguage() else null,
                    durable)
                val pending = if (durable) PendingStandardEvents.initialize(record) else null
                store.get().save(record)
                recoveryRecord = record; audioJournal = journal; eventJournal = pending
                LOG.info("原场现场已落盘")
            } catch (failure: Exception) {
                LOG.warning("原场现场落盘失败 class=${failure.javaClass.simpleName}")
                if (durable) throw IllegalStateException("原场持久事件存储不可用")
            }
        }
        /** 已确认收口或明确失败才删除现场；未确认现场保留给宿主续办。 */
        private fun cleanupRecovery() {
            val record = recoveryRecord ?: return
            if (eventJournal != null) {
                try {
                    discardRecoveredScene(record.meetingId(), record)
                } catch (failure: IOException) {
                    logFailure("清理原场标准事件", failure)
                    return
                }
                recoveryRecord = null
                return
            }
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
            display.apply(standard, nowMs); showDisplay(); scheduleDeadline()
            val number = ++sequence
            queueStandardEvent(PendingStandardEvent(number, standard, null))
            queueTextTranslation(standard)
        }
        private fun translation(event: TranslationEvent, fromTextAdapter: Boolean = false) {
            if (done || modelFinished && !fromTextAdapter || !available.contains(MeetingCapabilityState.TRANSLATION) ||
                textTranslationGrant != null && !fromTextAdapter) return
            display.apply(event, monotonicMs.asLong); showDisplay(); scheduleDeadline()
            val number = ++sequence
            queueStandardEvent(PendingStandardEvent(number, null, event))
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
        /** 先在状态队列持久化；请求队列即使被上一条 ACK 卡住也不会阻止原包落盘。 */
        private fun queueStandardEvent(event: PendingStandardEvent) {
            val journal = eventJournal
            val prepared = if (journal == null) event else {
                val packet = try {
                    val original = event.transcript?.let { StandardEventPacket.transcript(event.sequence, it) }
                        ?: StandardEventPacket.translation(event.sequence, event.translation!!)
                    journal.append(original)
                    original
                } catch (failure: Exception) {
                    if (failure !is IOException && failure !is RuntimeException) throw failure
                    standardEventWriteFailed = true
                    journal.markWriteFailed()
                    logFailure("保存原场标准事件原包", failure)
                    publish(notice = MeetingNotice.TRANSCRIPT_UNCONFIRMED)
                    return
                }
                event.copy(packet = packet)
            }
            event.transcript?.let { transcript -> audioJournal?.let { journalCopy ->
                io.execute { journalCopy.append(transcript) }
            } }
            io.execute { saveStandardEvent(prepared) }
        }

        /** 接纳原包后按原顺序确认队首，失败不越过。 */
        private fun saveStandardEvent(event: PendingStandardEvent) {
            if (closed || cancelled) return
            // 413 之后服务端不会再接受标准事件；只保留本地显示／日志，不再制造重试。
            if (contentTooLarge) {
                LOG.fine("正文超过部署预算后不再上传标准事件 sequence=${event.sequence}")
                return
            }
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
                if (closed || cancelled || contentTooLarge) return
                val event = pendingStandardEvents.first
                try {
                    if (event.packet != null) {
                        checkStandardEventScope()
                        server.appendStoredStandardEvent(id, event.packet.body)
                        checkStandardEventScope()
                        checkNotNull(eventJournal).acknowledge(event.packet)
                    } else if (event.transcript != null) {
                        server.appendTranscript(id, event.sequence, event.transcript)
                    } else server.appendTranslation(id, event.sequence, event.translation!!)
                } catch (failure: IOException) {
                    // 413 是确定性拒绝；普通 5xx／网络失败仍是可重试的瞬时失败。
                    if (failure is MeetingHttpStatusException && failure.isContentTooLarge) {
                        rejectContentTooLarge()
                        return
                    }
                    throw failure
                }
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
        /**
         * 服务端拒绝继续追加正文：清掉未确认尾部并停止上传，同场不再重试。
         * 已接受内容仍在服务端，Stop／用量／纪要照常收口；宿主通过状态提示看到损失边界。
         */
        private fun rejectContentTooLarge() {
            if (contentTooLarge) return
            contentTooLarge = true
            val dropped = pendingStandardEvents.size
            pendingStandardEvents.clear()
            LOG.warning("正文累计超过部署预算，停止上传标准事件 dropped=$dropped")
            enqueue {
                if (!done) {
                    // 停止上传后不能再等一次永远不会来的 ACK；否则本地 Mock 的 canStop
                    // 会一直为 false，用户点停止也收不了口。
                    canStop = true
                    publish(notice = MeetingNotice.CONTENT_TOO_LARGE)
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

        override fun submitPresentation(
            offsetMs: Long,
            jpeg: ByteArray,
            ocrText: String?,
            callback: PresentationResultCallback,
        ) {
            val frame = PresentationFrame(offsetMs, jpeg, ocrText)
            enqueue {
                if (done || !acceptingPresentation) {
                    notifyListener { callback.onError(MeetingError(MeetingErrorCode.PRESENTATION_REJECTED)) }
                } else io.execute { savePresentation(frame, callback) }
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
        /** 带结果回调的保存：预算超限先去文本；能力不支持时文本不发送。 */
        private fun savePresentation(frame: PresentationFrame, callback: PresentationResultCallback) {
            if (closed || cancelled) return
            var effective = frame
            var suppression: OcrTextSuppression? = null
            try {
                val candidate = presentationSequence + if (pendingPresentation == null) 1 else 2
                try {
                    HttpMeetingServerClient.validatePresentationRequestSize(
                        candidate, frame.offsetMs(), frame.jpeg(), frame.ocrText())
                } catch (withText: IOException) {
                    if (frame.ocrText() == null) throw withText
                    effective = PresentationFrame(frame.offsetMs(), frame.jpeg())
                    HttpMeetingServerClient.validatePresentationRequestSize(
                        candidate, effective.offsetMs(), effective.jpeg())
                    suppression = OcrTextSuppression.BUDGET
                }
            } catch (invalidSize: IOException) {
                logFailure("检查演示截图大小", invalidSize)
                enqueue { notifyListener {
                    callback.onError(MeetingError(MeetingErrorCode.PRESENTATION_TOO_LARGE))
                } }
                return
            }
            val textIncluded = effective.ocrText() != null
            try {
                if (pendingPresentation != null) savePendingPresentation()
                pendingPresentation = effective
                savePendingPresentation()
                enqueue { notifyListener {
                    callback.onSuccess(PresentationSubmitResult(true, textIncluded, suppression))
                } }
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("保存演示截图", failure)
                enqueue { notifyListener {
                    callback.onError(MeetingError(MeetingErrorCode.PRESENTATION_SAVE_FAILED))
                } }
            }
        }

        private fun savePendingPresentation() {
            val number = presentationSequence + 1
            val pending = pendingPresentation!!
            server.appendPresentation(id, number, pending.offsetMs(), pending.jpeg(), pending.ocrText())
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
        /**
         * 心跳只刷新服务端活跃事实：失败只记录，不改会议状态，也不在本次调用内重试。
         * 由宿主按 Create 回告的间隔触发；开始后持续发送，直到服务端受理 Stop（ACK）、
         * 会议进入 READY／ENDED／FAILED 或销毁。
         *
         * <p>用户点停止不等于服务端已受理停止：冻结用量、纪要、Stop 可能在后端业务
         * 队列里排很久。停止后立刻断心跳，收尾中的会议会被服务端的失联窗口判成弃场。
         * 服务端心跳接口只接受 STARTING／RUNNING，受理 Stop 后进入 STOPPING 并回 409，
         * 这个 409 本身无害，但已无意义，所以在 ACK 处停发。</p>
         *
         * <p>发送走独立单线程执行器，不复用字幕／翻译的 io 队列：即使业务请求被慢
         * 任务占满，心跳仍按宿主节奏发出。原子 in-flight 保证同一时刻最多一次，
         * 上一次未返回时后续触发直接忽略，不会在本地无界堆积。</p>
         */
        override fun heartbeat() {
            if (closed || done || cancelled || !heartbeatAllowed) return
            if (!heartbeatInFlight.compareAndSet(false, true)) return
            try {
                heartbeatExecutor.execute {
                    try {
                        // 排队期间可能已经停止、失败或销毁；执行时再核一次。
                        if (closed || cancelled || done || !heartbeatAllowed) return@execute
                        val currentId = meetingId ?: return@execute
                        try {
                            server.sendHeartbeat(currentId)
                        } catch (failure: Exception) {
                            if (failure !is IOException && failure !is RuntimeException) throw failure
                            logFailure("会议心跳", failure)
                        }
                    } finally {
                        heartbeatInFlight.set(false)
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                heartbeatInFlight.set(false)
                // 只有关闭中的拒绝可以静默；其它情况保留原异常类型给宿主。
                if (!closed) throw rejected
            }
        }
        override fun requestStop() = requestStop(false)

        override fun requestStop(persistIntent: Boolean) = stop(persistIntent, null, null)

        override fun requestStopForHostExit(persistIntent: Boolean, onIntentSaved: Consumer<Boolean>,
            onInputStopped: Consumer<Boolean>) = stop(persistIntent, onIntentSaved, onInputStopped)

        private fun stop(persistIntent: Boolean, onIntentSaved: Consumer<Boolean>?,
            onInputStopped: Consumer<Boolean>?) {
            enqueue {
                if (done || !active) {
                    if (onIntentSaved != null) {
                        if (!done) cancel()
                        notifyClient { onIntentSaved.accept(true); onInputStopped?.accept(true) }
                    }
                    return@enqueue
                }
                if (!canStop && onIntentSaved == null) return@enqueue
                val intentStore = config.meetingRecoveryStore().orElse(null) as? MeetingStopIntentStore
                if (intentStore != null &&
                    (persistIntent || stopGrant != null && intentStore.canUseStopGrantAtCurrentLogin())) {
                    keepExplicitStop = true
                }
                if (keepExplicitStop && explicitStopKey == null) {
                    try {
                        explicitStopKey = checkNotNull(intentStore).beginExplicitStop(id)
                        stopGrant?.let { grant ->
                            check(grant.stopKey == explicitStopKey) { "原场停止键与授权不一致" }
                            accountCredentialRetired.set(true)
                            sessionConfig = config.forSession(IdentitySession(grant.capability, ""))
                            server = servers.apply(sessionConfig)
                        }
                    } catch (failure: IOException) {
                        // Stop local capture even if storage failed. No server finalization can
                        // pass maybeComplete until a retry has durably recorded the intent.
                        logFailure("保存原场停止意图", failure)
                    }
                }
                if (onIntentSaved != null) {
                    hostExitInputStopped = onInputStopped
                    // Once the command owns this scene no delayed callback may reopen its window.
                    // In a storage failure still stop audio and release the device account.
                    val saved = !persistIntent || explicitStopKey != null
                    notifyClient { onIntentSaved.accept(saved) }
                    if (stopInputSent) confirmHostExitAfterStoppedInput()
                }
                if (!canStop && stopRequested) return@enqueue
                // 这里刻意不停心跳：停止受理后还要提交冻结用量、生成纪要并等后端
                // 受理 Stop，这些请求可能排在业务队列里超过失联窗口。服务端只把
                // 仍在 STARTING／RUNNING 的会议视为活跃，若用户一点停止就断心跳，
                // 收尾中的会议会被弃场扫描按失联处理。心跳改在 Stop ACK 处停止，
                // 见 complete()／finishSavedFinalization 的受理分支。
                stopRequested = true; audioLevelActive = false; canStop = false
                phase = MeetingSessionState.Phase.STOPPING
                if (display.flushPendingLanguages()) showDisplay()
                cancelDeadline(); publish()
                // 只在用户确认停止时重试已失败的原输入，不增加定时任务或无限重试。
                pendingTextTranslations.values.toList().forEach(::submitTextTranslation)
                // 停音走独立 modelControl，不等待图片保存 IO；图片 ACK/NAS 屏障仍先于最终发布。
                val beginBarrier = !beforeStopStarted
                beforeStopStarted = true
                stopModelInput()
                if (beginBarrier) {
                    val continued = AtomicBoolean()
                    notifyListener {
                        listener.beforeStop {
                            if (continued.compareAndSet(false, true)) enqueue { releaseStopBarrier() }
                        }
                    }
                } else maybeComplete()
            }
        }
        private fun releaseStopBarrier() {
            if (done) return
            beforeStopCompleted = true
            acceptingPresentation = false
            maybeComplete()
        }
        private fun stopModelInput() {
            if (done || stopInputSent || stopInputInFlight) return
            stopInputInFlight = true
            modelControl.execute {
                try {
                    model!!.stopInput()
                    val freezeSaved = trySaveFrozenStop()
                    enqueue {
                        stopInputInFlight = false
                        stopInputSent = true
                        stopFreezeSaved = freezeSaved
                        confirmHostExitAfterStoppedInput()
                        maybeComplete()
                    }
                }
                catch (failure: RuntimeException) {
                    logFailure("停止模型输入", failure)
                    enqueue {
                        stopInputInFlight = false
                        canStop = true
                        publish(notice = MeetingNotice.STOP_RETRY_REQUIRED)
                        notifyHostExitInputStopped(false)
                    }
                }
            }
        }
        private fun trySaveFrozenStop(): Boolean = try {
            if (explicitStopKey == null) false else {
                saveFrozenExplicitStop()
                true
            }
        } catch (failure: IOException) {
            logFailure("保存冻结采音用量", failure)
            false
        }

        private fun confirmHostExitAfterStoppedInput() {
            if (hostExitInputStopped == null || savingFrozenStop) return
            if (keepExplicitStop && !stopFreezeSaved && explicitStopKey != null) {
                // The ordinary Stop may have stopped the input before this explicit intent was written.
                savingFrozenStop = true
                modelControl.execute {
                    val saved = trySaveFrozenStop()
                    enqueue {
                        savingFrozenStop = false
                        stopFreezeSaved = saved
                        notifyHostExitInputStopped(saved)
                    }
                }
            } else notifyHostExitInputStopped(!keepExplicitStop || stopFreezeSaved)
        }

        private fun notifyHostExitInputStopped(confirmed: Boolean) {
            hostExitInputStopped?.let { callback -> notifyClient { callback.accept(confirmed) } }
            hostExitInputStopped = null
        }
        private fun maybeComplete() {
            if (done || completionQueued || !stopRequested || !stopInputSent || !beforeStopCompleted || runtime.waitsForModelFinish() && !modelFinished) return
            if (translating.isNotEmpty()) return
            if (pendingTextTranslations.isNotEmpty()) {
                completionFailed("独立翻译尚未完成，请检查模型连接后再次确认停止")
                return
            }
            if (keepExplicitStop && explicitStopKey == null) {
                completionFailed("原场停止意图未保存，可再次完成")
                return
            }
            completionQueued = true; phase = MeetingSessionState.Phase.FINALIZING
            publish()
            io.execute { complete() }
        }
        private fun saveFrozenExplicitStop() {
            val key = explicitStopKey ?: return
            val duration = model!!.frozenAudioDurationMs()
            if (duration.isEmpty) throw IOException("原场冻结采音用量尚未就绪")
            val audio = try {
                model!!.sealedAudioArtifact().orElse(null)
            } catch (_: IOException) {
                null
            }
            val store = config.meetingRecoveryStore().orElseThrow() as MeetingStopIntentStore
            store.saveFrozenStop(id, MeetingStopIntent(key, duration.asLong, audio))
        }

        private fun complete() {
            if (cancelled || closed) return
            var step = "检查标准事件保存"
            try {
                saveFrozenExplicitStop()
                savePendingStandardEvents()
                if (eventJournal != null) {
                    if (standardEventWriteFailed || contentTooLarge) throw UnverifiedStandardEventsException()
                    checkStandardEventScope()
                    checkNotNull(recoveryRecord).requireAcknowledgedStandardEvents()
                }
                if (cancelled || closed) return
                step = "提交冻结采音用量"
                if (!usageRead) { frozenAudioDuration = model!!.frozenAudioDurationMs(); usageRead = true }
                if (frozenAudioDuration.isPresent && !usageSubmitted) {
                    val durationMs = frozenAudioDuration.asLong
                    LOG.info("冻结采音用量提交开始 durationMs=$durationMs")
                    server.submitUsage(id, durationMs); usageSubmitted = true; LOG.info("冻结采音用量已确认")
                }
                if (cancelled || closed) return
                // 只在原尾部全部 ACK 与 sealed 读取成功后保存候选，无痕继续 Stop→ENDED。
                val store = sessionConfig.stoppedMeetingStore()
                if (store.isPresent && options.retainAudio() && !audioFailed && options.resultOptions().policy() != ResultOptions.Policy.NO_ACTIVE_DELIVERY) {
                    step = "保存并完成原场停止收尾"
                    readAudioArtifactOnce()
                    if (cancelled || closed) return
                    // 只有服务端生成纪要的部署才记录语言快照；本地/直连部署不记，避免续办误发。
                    if (stoppedCheckpoint == null) {
                        val context = publicationContext!!
                        stoppedCheckpoint = ((explicitStopKey ?: stopGrant?.stopKey)?.let {
                            StoppedMeetingCheckpoint.pendingWithKey(context, true, it)
                        }
                            ?: StoppedMeetingCheckpoint.pending(context))
                            .withSummaryLanguages(if (summaryServerAvailable) summaryLanguages() else null)
                    }
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
                    val finished = finishSavedFinalization(stoppedCheckpoint!!, store.get(), server,
                        sessionConfig.artifactPublisher(), BooleanSupplier { cancelled || closed }, LongSupplier {
                            if (genTime == null) genTime = wallClockMs.asLong
                            genTime!!
                        }, Runnable { heartbeatAllowed = false; model!!.close() })
                    when (finished) {
                        is SavedFinalizationOutcome.Delivered -> enqueue { ready(finished.result) }
                        SavedFinalizationOutcome.EndedWithoutDelivery ->
                            enqueue { endWithoutDelivery("会议已结束；无痕互译不主动交付结果") }
                        null -> {}
                    }
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
                // 资源已经发布（或本场没有可发布资源）：现在才上报纪要语言，仍先于 Stop。
                requestSummaryOnce()
                step = "后端 Stop"; LOG.info("后端 Stop 开始")
                val stop = (explicitStopKey ?: stopGrant?.stopKey)?.let { server.stopMeeting(id, it) } ?: server.stopMeeting(id)
                if (!stop.accepted()) {
                    LOG.info("后端 Stop 尚未受理，等待用户再次确认")
                    enqueue { completionFailed("后端仍在收口，请稍后再次确认停止") }
                    return
                }
                // 服务端已受理 Stop：会议进入 STOPPING，之后心跳只会得到 409。
                // 从这一刻停发；尚未受理或受理失败时仍在 STOPPING／FINALIZING 收尾，
                // 心跳继续，避免会议在收尾期间被扫描器判成弃场。
                heartbeatAllowed = false
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
                enqueue { completionFailed("结束会议失败，请检查连接后再次确认停止", stopRejection(failure)) }
            }
        }
        /**
         * 按会前选择上报本场纪要语言，正文由服务端异步生成。
         * 仅用于没有停止记录、无法靠续办补发的场次；有停止记录时由
         * finishSavedFinalization 按记录里的语言快照上报并持久化。
         * 同场只上报一次；请求失败只告警，不阻断发布／Stop。
         */
        private fun requestSummaryOnce() {
            if (summaryRequested) return
            // 本地/设备直连纪要的部署没有 summary:request 接口，语言上报只服务服务端生成。
            if (!summaryServerAvailable) return
            val languages = summaryLanguages() ?: return
            summaryRequested = true
            requestSummaryQuietly(server, id, languages)
        }
        /** 会前语言快照：不要纪要时为空，避免没有纪要的场次续办时误发请求。 */
        private fun summaryLanguages(): List<String>? = if (!options.summaryEnabled()) null
            else SummaryLanguages.from(options.sourceLanguage(),
                options.targetLanguage().takeIf { options.mode() == MeetingOptions.Mode.LIVE_TRANSLATION })
        private fun ready(finalResult: MeetingResult) {
            if (done) return
            heartbeatAllowed = false
            done = true; active = false; phase = MeetingSessionState.Phase.READY; readyResult = finalResult
            val needsReadySummary = try {
                stopGrant != null && config.stoppedMeetingStore().orElse(null)?.let {
                    hasScopedSummaryPending(it, id)
                } == true
            } catch (failure: IOException) {
                logFailure("核对原场纪要待补报状态", failure)
                true
            }
            if (!needsReadySummary) cleanupRecovery()
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
        private fun completionFailed(message: String, error: MeetingErrorCode? = null) {
            if (done) return
            pendingStopError = error
            completionQueued = false; canStop = true; phase = MeetingSessionState.Phase.STOP_PENDING
            LOG.warning("本场停止未完成：$message")
            publish(notice = MeetingNotice.STOP_RETRY_REQUIRED)
        }
        private fun endWithoutDelivery(message: String) {
            if (done) return
            heartbeatAllowed = false
            done = true; active = false; phase = MeetingSessionState.Phase.ENDED
            cleanupRecovery()
            LOG.info("会议已停止，未主动交付结果：$message")
            publish()
        }
        private fun failStart(code: MeetingErrorCode) {
            if (done) return
            heartbeatAllowed = false
            audioLevelActive = false; done = true; active = false; canStop = false; phase = MeetingSessionState.Phase.FAILED
            cancelDeadline()
            cleanupRecovery()
            model?.let { modelControl.execute(it::close) }
            publish(errorCode = code)
        }
        override fun resultActionAccess(): ResultActionAccess = object : ResultActionAccess {
            override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) {
                accessResult("读取结果内容", callback) { result ->
                    result.content() ?: if (stoppedCheckpoint != null)
                        server.readSavedReadyResult(result.meetingId(), result.genTime(), true).content()
                    else server.readResult(result.meetingId(), result.genTime(), true).content()
                }
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
        override fun notifyAfterClientClose(onIdle: Runnable): Boolean {
            enqueue { closeNotification = onIdle }
            return true
        }
        fun notifyAfterPendingWork() {
            val onIdle = closeNotification ?: return
            closeNotification = null
            val unfinished = java.util.concurrent.atomic.AtomicInteger(2)
            val drained = Runnable { if (unfinished.decrementAndGet() == 0) onIdle.run() }
            modelControl.execute(drained)
            io.execute(drained)
        }
        fun cancel() {
            if (cancelled) return
            accountCredentialRetired.set(true)
            heartbeatAllowed = false
            cancelled = true; audioLevelActive = false; readyResult = null; done = true; active = false
            cancelDeadline()
            model?.let { modelControl.execute(it::close) }
        }
    }
    /** 恰有一种不可变标准原包，后续不重新归一化或重新分号。 */
    private data class PendingStandardEvent(val sequence: Int, val transcript: TranscriptEvent?,
        val translation: TranslationEvent?, val packet: StandardEventPacket? = null)
    companion object {
        private val LOG = Logger.getLogger(MeetingCoordinator::class.java.name)
        private val HTTP_CODE = Pattern.compile("HTTP[ =:]*(\\d{3})")
        private fun stopRejection(failure: Throwable): MeetingErrorCode? {
            if (failure is UnverifiedStandardEventsException) return MeetingErrorCode.STOP_EVENTS_UNVERIFIED
            val http = failure as? MeetingHttpStatusException ?: return null
            return when {
                http.status == 403 && http.errorCode == "ACCESS_DENIED" -> MeetingErrorCode.STOP_ACCESS_DENIED
                http.status == 409 && http.errorCode == "IDEMPOTENCY_CONFLICT" -> MeetingErrorCode.STOP_KEY_CONFLICT
                else -> null
            }
        }
        /**
         * 纪要语言上报失败只记 WARNING 并继续调用方流程：纪要能否生成由服务端能力与
         * 状态判定，设备不能因为一次纪要请求卡住发布或 Stop。
         * 返回 true 表示服务端已受理，调用方据此决定是否把“已上报”持久化。
         */
        private fun requestSummaryQuietly(server: MeetingServerClient, meetingId: String,
            languages: List<String>): Boolean {
            try {
                val ack = server.requestSummary(meetingId, languages)
                LOG.info("纪要语言已上报 languages=${languages.size} status=${ack.summaryStatus() ?: "UNKNOWN"}")
                return true
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                logFailure("上报纪要语言", failure)
                LOG.warning("纪要语言上报失败，继续结束本场")
                return false
            }
        }
        private fun logFailure(step: String, failure: Throwable) {
            var code = ""
            val message = failure.message
            if (message != null) { val match = HTTP_CODE.matcher(message); if (match.find()) code = " HTTP=${match.group(1)}" }
            // 仅本地阶段／异常类型／HTTP 数字，不记录正文、URL、凭据或 Throwable。
            LOG.warning("会议步骤失败 step=$step type=${failure.javaClass.simpleName}$code")
        }
    }
}
