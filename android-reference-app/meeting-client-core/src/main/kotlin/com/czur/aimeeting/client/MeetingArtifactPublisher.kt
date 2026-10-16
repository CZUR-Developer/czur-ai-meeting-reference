package com.czur.aimeeting.client

import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.IOException

/**
 * 正常收尾发布扩展，在唯一 IO 队列最终事件／用量／选定纪要保存后、Stop 前执行。
 * 正常返回表示发布及资源登记已确认，否则抛 IOException；不另建状态机或自动恢复任务。
 */
fun interface MeetingArtifactPublisher {
    @Throws(IOException::class)
    fun publish(context: Context)
    /** 显式发布器默认可处理音频，无副作用默认实现返回 false。 */
    fun supportsAudioPublication(): Boolean = true

    /**
     * 仅保存本场 sealed 事实及冻结服务身份，不持全局 Provider 或重登录。
     * 同账号续签后仅替换访问材料，文件、场次、用量不变；路径和 token 不得日志输出。
     */
    class Context private constructor(
        private val meetingId: String,
        private val audio: SealedAudioArtifact,
        private val frozenAudioDurationMs: Long,
        private val serverUrl: String,
        private val accessToken: String,
        private val clientIdentity: ClientIdentity,
    ) {
        constructor(meetingId: String, audio: SealedAudioArtifact, frozenAudioDurationMs: Long,
            sessionConfig: MeetingClientConfig) : this(meetingId, audio, frozenAudioDurationMs,
            sessionConfig.serverUrl(), sessionConfig.accessToken(), sessionConfig.clientIdentity())
        /** 仅协调器在身份扩展批准同账号后使用。 */
        fun withAccessToken(accessToken: String): Context = Context(meetingId, audio,
            frozenAudioDurationMs, serverUrl, accessToken, clientIdentity)
        fun meetingId(): String = meetingId
        fun audio(): SealedAudioArtifact = audio
        fun frozenAudioDurationMs(): Long = frozenAudioDurationMs
        fun serverUrl(): String = serverUrl
        fun accessToken(): String = accessToken
        fun clientIdentity(): ClientIdentity = clientIdentity
        override fun toString(): String = "MeetingArtifactPublisher.Context[meetingId=redacted, audio=redacted, " +
            "serverUrl=redacted, accessToken=redacted, clientIdentity=redacted]"
    }
    class None private constructor() : MeetingArtifactPublisher {
        override fun supportsAudioPublication(): Boolean = false
        override fun publish(context: Context) {}
        companion object { private val INSTANCE: MeetingArtifactPublisher = None() }
    }
    companion object {
        /** 无录音／上传副作用的旧构造器默认值。 */
        @JvmStatic fun none(): MeetingArtifactPublisher = NONE
        private val NONE: MeetingArtifactPublisher = object : MeetingArtifactPublisher {
            override fun supportsAudioPublication(): Boolean = false
            override fun publish(context: Context) {}
        }
    }
}
