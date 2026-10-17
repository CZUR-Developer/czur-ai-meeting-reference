package com.czur.aimeeting.model

/**
 * 模型 WebSocket 协议端口，实现者只解析协议；MIC、连接及二进制 PCM 由 SDK 管理。
 * 每次连接使用独立实例；同场续接重新签发任务后也使用新实例，不能泄露短期访问材料。
 * Adapter 输出的字幕／句开始时间以本次模型任务收到的音频起点为零；不要先加会议偏移。
 * SDK 会加本代首个实际发送帧在完整录音中的位置，再向上交付本场时间。
 */
interface ModelAdapter {
    /** 与后端 modelSession.adapterId 一致的构建期注册标识。 */
    fun id(): String

    /**
     * WebSocket 握手请求。默认保留 GET 与非空 token 的 Bearer；伙伴可覆盖请求头，
     * 例如从 Grant 的 token／initParams 生成 x-api-key 或版本头。此处不访问网络。
     * 实时传输仍使用 GET、无正文的 WebSocket；该端口不改变 PCM 格式和启停消息。
     */
    fun connectionRequest(grant: ModelSessionGrant): ModelHttpRequest =
        ModelHttpRequest("GET", ModelHttpRequest.bearerHeaders(grant.token()))

    /**
     * 是否先发未知源语种、随后在同段修订／配对译文补充可信语种。
     * 默认 false 保持即时显示；组合层在首条字幕前读取声明，按已有方向／语种窗口处理。
     * 此项只声明消息时序，不代表授权或语言能力，后续语种仍通过标准修订回调表达。
     */
    fun mayEmitDeferredSourceLanguage(): Boolean = false

    /**
     * 是否接受“原业务会议＋新模型任务＋新 Adapter”续接。默认 false 保持终止语义。
     * Runtime 隔离连接代次、事件 ID 及录音时间原点；解析／音源错误不因此自动重试。
     */
    fun supportsSessionReconnect(): Boolean = false

    /**
     * 丢弃旧解析实例前交出模型已确认、因配对时序尚未回调的原文 final。
     * 不能将 partial 改成 final、伪造译文或发 FINISHED；与旧连接消息解析串行调用。
     */
    fun drainConfirmedOnDisconnect(transcriptListener: TranscriptListener) {}

    /** 连接建立后的协议启动消息，音频等待 AUDIO_READY 后才发送。 */
    fun startMessage(grant: ModelSessionGrant): String

    /** 先回调标准字幕，再返回生命周期信号；FINISHED 后由 SDK 通知完成。 */
    fun onServerMessage(message: String, listener: TranscriptListener): ModelSignal

    /**
     * 支持译文的入口。默认委托旧入口，原仅转写实现保持兼容。
     * 互译实现为同一原文保留 sourceSegmentId；SDK 转发标准事件，持久化仍由业务决定。
     */
    fun onServerMessage(
        message: String,
        transcriptListener: TranscriptListener,
        translationListener: TranslationListener,
    ): ModelSignal = onServerMessage(message, transcriptListener)

    /** 句开始先于本段字幕／译文；默认委托旧入口，语种修正仍使用标准字幕修订。 */
    fun onServerMessage(
        message: String,
        transcriptListener: TranscriptListener,
        translationListener: TranslationListener,
        segmentStartedListener: SegmentStartedListener,
    ): ModelSignal = onServerMessage(message, transcriptListener, translationListener)

    /** 停止输入后的协议消息；不需要文本收尾可返回 null。 */
    fun stopMessage(grant: ModelSessionGrant): String? = null
}
