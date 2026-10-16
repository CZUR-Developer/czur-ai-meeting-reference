package com.czur.aimeeting.model

import java.io.IOException
import java.util.Optional
import java.util.OptionalLong

/** 单次模型会话；start 后产出事件，stopInput 后继续接收最终结果。 */
interface ModelSession : AutoCloseable {
    /** 开始数据处理。 */
    fun start()
    /** 停止采音／发送并发协议停止消息，最终结果由监听器回调。 */
    fun stopInput()

    /**
     * 采音停止、完整尾帧交付后冻结的累计毫秒。16kHz 单声道 PCM16 按实交字节数／32；
     * 静音和连接前采帧计入，模型重发与总结等待不计入。冻结后跨线程读取且终身不变。
     * empty 表示未冻结／未提供，不能当零；0 表示已确认没有完整帧。
     */
    fun frozenAudioDurationMs(): OptionalLong = OptionalLong.empty()

    /**
     * 已停止采音、排完尾帧并完成编码／文件同步的同源录音事实，冻结后不重复封口。
     * empty 表示未装配／未封口；失败抛 IOException，取消不产生可发布事实。
     */
    @Throws(IOException::class)
    fun sealedAudioArtifact(): Optional<SealedAudioArtifact> = Optional.empty()

    /** 取消并释放本场资源，调用方不再接收事件。 */
    override fun close()
}
