package com.czur.aimeeting.model

/** 闭源 SDK 模型运行时，内部管理音源与连接；应用装配 Adapter 并消费标准事件。 */
fun interface ModelRuntime {
    fun open(
        grant: ModelSessionGrant,
        adapter: ModelAdapter,
        transcriptListener: TranscriptListener,
        sessionListener: ModelSessionListener,
    ): ModelSession

    /** 只有装配同源录音及封口实现时为 true。 */
    fun supportsRetainedAudio(): Boolean = false

    /** 原四参入口保留；请求保留录音必须由实际支持的实现处理，不能静默忽略。 */
    fun open(
        grant: ModelSessionGrant,
        adapter: ModelAdapter,
        transcriptListener: TranscriptListener,
        sessionListener: ModelSessionListener,
        retainAudio: Boolean,
    ): ModelSession {
        check(!retainAudio) { "同源录音尚未装配" }
        return open(grant, adapter, transcriptListener, sessionListener)
    }

    /**
     * 可选同场续接端口，null 表示未装配。默认保持旧五参行为。
     * 具体运行时只替换模型连接，保留唯一音源、录音、用量和业务场次；停止不等材料 HTTP。
     */
    fun open(
        grant: ModelSessionGrant,
        adapter: ModelAdapter,
        transcriptListener: TranscriptListener,
        sessionListener: ModelSessionListener,
        retainAudio: Boolean,
        reconnector: ModelSessionReconnector?,
    ): ModelSession = open(grant, adapter, transcriptListener, sessionListener, retainAudio)
}
