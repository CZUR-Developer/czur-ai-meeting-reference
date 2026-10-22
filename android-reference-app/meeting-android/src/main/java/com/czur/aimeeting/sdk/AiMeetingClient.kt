package com.czur.aimeeting.sdk

import android.content.Context
import android.os.SystemClock
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingCoordinator
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.MeetingListener
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.AudioMixOptions
import com.czur.aimeeting.client.MeetingRuntimePort
import com.czur.aimeeting.client.MeetingSession
import com.czur.aimeeting.client.ModelAdapterRegistry
import com.czur.aimeeting.client.RecoveryResult
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.server.ReferenceLoginSession
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelRuntime
import com.czur.aimeeting.model.ModelSession
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.ModelSessionReconnector
import com.czur.aimeeting.model.SummaryAdapter
import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummaryResult
import com.czur.aimeeting.model.SummaryRuntime
import com.czur.aimeeting.model.SummarySessionGrant
import com.czur.aimeeting.model.TranscriptListener
import com.czur.aimeeting.model.TranslationAdapter
import com.czur.aimeeting.model.TranslationEvent
import com.czur.aimeeting.model.TranslationInput
import com.czur.aimeeting.model.TranslationRuntime
import com.czur.aimeeting.model.TranslationSessionGrant
import java.io.IOException

/**
 * Android 完整会议 façade，内带默认模型与唯一 core 协调器。App 提供部署、身份与设置，
 * 权限、前台服务、UI、截图调度和 PDF 留在 App；不复制业务状态、分栏算法或另开音源。
 * callbackExecutor 必须串行，Android 通常为主线程。正常结束用 requestStop，close 仅取消。
 */
class AiMeetingClient : MeetingClient {
    private val delegate: MeetingClient

    constructor(context: Context, config: MeetingClientConfig) : this(context, config, ModelAdapterRegistry())
    /** 伙伴开场前注册工厂，每场新建 Adapter；模型材料与能力授权仍由 Server 决定。 */
    constructor(context: Context, config: MeetingClientConfig, adapters: ModelAdapterRegistry) :
        this(config, adapters, AndroidModelRuntime(context), AndroidSummaryRuntime())
    /**
     * 可选本地产物工厂仍使用同一 PCM；配置还需装配本场发布器并设置 retainAudio。
     * 旧构造器和默认参考场次不录音/上传。
     */
    constructor(context: Context, config: MeetingClientConfig, adapters: ModelAdapterRegistry,
        artifactSinks: AudioArtifactSink.Factory) :
        this(config, adapters, AndroidModelRuntime(context, artifactSinks), AndroidSummaryRuntime())

    internal constructor(config: MeetingClientConfig, adapters: ModelAdapterRegistry,
        model: ModelRuntime, summary: SummaryRuntime, translation: TranslationRuntime = AndroidTranslationRuntime()) {
        delegate = MeetingCoordinator(config, adapters, object : MeetingRuntimePort {
            override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
            override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?,
                transcripts: TranscriptListener, listener: ModelSessionListener): ModelSession =
                model.open(grant!!, adapter!!, transcripts, listener)
            override fun supportsRetainedAudio(): Boolean = model.supportsRetainedAudio()
            override fun supportsTextTranslation(): Boolean = true
            override fun translate(grant: TranslationSessionGrant, adapter: TranslationAdapter,
                input: TranslationInput): TranslationEvent = translation.translate(grant, adapter, input)
            override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?,
                transcripts: TranscriptListener, listener: ModelSessionListener, retainAudio: Boolean): ModelSession =
                model.open(grant!!, adapter!!, transcripts, listener, retainAudio)
            override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?,
                transcripts: TranscriptListener, listener: ModelSessionListener, retainAudio: Boolean,
                reconnector: ModelSessionReconnector?): ModelSession =
                model.open(grant!!, adapter!!, transcripts, listener, retainAudio, reconnector)
            override fun openWithAudioMix(grant: ModelSessionGrant?, adapter: ModelAdapter?,
                transcripts: TranscriptListener, listener: ModelSessionListener, retainAudio: Boolean,
                reconnector: ModelSessionReconnector?, audioMix: AudioMixOptions): ModelSession =
                (model as? AudioMixAwareRuntime)?.openWithAudioMix(grant!!, adapter!!, transcripts,
                    listener, retainAudio, reconnector, audioMix)
                    ?: model.open(grant!!, adapter!!, transcripts, listener, retainAudio, reconnector)
            @Throws(IOException::class)
            override fun summarize(grant: SummarySessionGrant?, adapter: SummaryAdapter?,
                input: SummaryInput): SummaryResult = summary.summarize(grant!!, adapter!!, input)
        })
    }
    override fun discover(callback: MeetingClient.Callback<MeetingDiscovery>) = delegate.discover(callback)
    override fun refreshIdentity(callback: MeetingClient.Callback<IdentityResult>) = delegate.refreshIdentity(callback)
    override fun login(username: String, password: String,
        callback: MeetingClient.Callback<ReferenceLoginSession>) = delegate.login(username, password, callback)
    override fun start(options: MeetingOptions, listener: MeetingListener): MeetingSession = delegate.start(options, listener)
    /** 续办既有发布/同键 Stop/真实 READY，不重开音源或模型。 */
    override fun resumeFinalization(checkpointId: String, callback: MeetingClient.Callback<MeetingResult>) =
        delegate.resumeFinalization(checkpointId, callback)
    /** 用本机原场现场重建封口事实并收口；不重开音源或模型。 */
    override fun recoverInterrupted(meetingId: String, callback: MeetingClient.Callback<RecoveryResult>) =
        delegate.recoverInterrupted(meetingId, callback)
    override fun close() = delegate.close()
}
