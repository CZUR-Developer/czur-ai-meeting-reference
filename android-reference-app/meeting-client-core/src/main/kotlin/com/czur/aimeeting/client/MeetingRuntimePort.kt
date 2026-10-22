package com.czur.aimeeting.client

import com.czur.aimeeting.model.*
import java.io.IOException

/** 平台／Mock 窄端口，不做 Server 请求或选 Adapter。Mock 的材料和 Adapter 均为 null。 */
interface MeetingRuntimePort {
    /** Android 实现用 elapsedRealtime，所有段／时限／活跃共用单调时基。 */
    fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L
    fun usesServerModelMaterials(): Boolean = true
    fun waitsForModelFinish(): Boolean = usesServerModelMaterials()
    /** 只有本地 Mock 使用固定能力，真实模型从注册表读取。 */
    fun localCapabilities(): Set<String> = emptySet()
    /** 仅已装配文本翻译请求执行器时返回 true；旧 Runtime 默认不声称支持 sidecar。 */
    fun supportsTextTranslation(): Boolean = false
    /** 阻塞模型请求由 Coordinator 的 IO 队列执行，不得占用采音／主线程。 */
    @Throws(IOException::class)
    fun translate(grant: TranslationSessionGrant, adapter: TranslationAdapter, input: TranslationInput): TranslationEvent =
        throw IOException("当前 Runtime 未装配独立文本翻译")
    fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener, listener: ModelSessionListener): ModelSession
    fun supportsRetainedAudio(): Boolean = false
    /** false 委托旧入口，true 必须显式支持同源录音。 */
    fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener,
        listener: ModelSessionListener, retainAudio: Boolean): ModelSession {
        check(!retainAudio) { "同源录音尚未装配" }
        return open(grant, adapter, transcripts, listener)
    }
    /** 可选续接桥，默认委托旧五参；真实运行时在独立续接线程取材料，停止不等 HTTP。 */
    fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener,
        listener: ModelSessionListener, retainAudio: Boolean, reconnector: ModelSessionReconnector?): ModelSession =
        open(grant, adapter, transcripts, listener, retainAudio)
    /**
     * 本场共享音频混流选项；默认委托旧六参，Mock 与未装配共享流的运行时忽略该参数。
     * Android 实现把它交给音源，打开系统共享流时按场决定是否混入播放及两路增益。
     */
    fun openWithAudioMix(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener,
        listener: ModelSessionListener, retainAudio: Boolean, reconnector: ModelSessionReconnector?,
        audioMix: AudioMixOptions): ModelSession =
        open(grant, adapter, transcripts, listener, retainAudio, reconnector)
    @Throws(IOException::class)
    fun summarize(grant: SummarySessionGrant?, adapter: SummaryAdapter?, input: SummaryInput): SummaryResult
}
