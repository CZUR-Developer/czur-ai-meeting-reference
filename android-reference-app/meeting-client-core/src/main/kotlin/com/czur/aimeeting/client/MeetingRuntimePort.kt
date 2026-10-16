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
    @Throws(IOException::class)
    fun summarize(grant: SummarySessionGrant?, adapter: SummaryAdapter?, input: SummaryInput): SummaryResult
}
