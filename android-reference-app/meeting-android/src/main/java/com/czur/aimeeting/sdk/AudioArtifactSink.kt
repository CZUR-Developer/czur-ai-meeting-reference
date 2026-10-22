package com.czur.aimeeting.sdk

import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.File
import java.io.IOException

/**
 * 唯一本场标准 PCM 的可选本地保存端口，不是音源、上传器或第二个会议编排器。
 * SDK 已在计数门复制帧，write/seal/cancel 均在本场串行保存队列、会话锁外调用。
 * 实现不得保留可变外部数组、回调会议控制或另开 AudioInput/AudioRecord/Broker。
 */
interface AudioArtifactSink {
    /** 仅构造本场轻量句柄；文件/媒体初始化延迟到保存队列，不能在此阻塞 UI/状态队列。 */
    fun interface Factory {
        @Throws(IOException::class)
        fun create(meetingId: String): AudioArtifactSink
    }

    /** 顺序保存同一计数门接受的 PCM16LE；失败必须外传，不能只记日志后冒称完整。 */
    @Throws(IOException::class)
    fun write(pcm: ByteArray)

    /**
     * 本场录音文件的确定位置，只供宿主写原场恢复现场；封口前文件可能尚未创建，
     * 返回的不是 sealed 事实。未装配或位置未确定时返回 null。
     */
    fun file(): File? = null

    /**
     * 正常 Stop、模型结束或模型失败停流及尾帧交付后调用一次。
     * 排空、EOS、容器停止及文件同步全部成功才返回；模型失败不代表音频保存失败。
     * capturedPcmBytes 来自唯一计数门，须与实际保存字节一致；padding 不增加该值。
     */
    @Throws(IOException::class)
    fun seal(capturedPcmBytes: Long): SealedAudioArtifact

    /** 显式取消、音频输入或保存失败只释放资源，不生成 sealed 事实，不自动删除残留文件。 */
    fun cancel()
}
