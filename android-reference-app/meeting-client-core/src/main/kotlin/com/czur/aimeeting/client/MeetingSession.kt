package com.czur.aimeeting.client

import com.czur.aimeeting.client.result.ResultActionAccess

/** 单场业务控制，UI／截图线程可调用，内部串行排队。 */
interface MeetingSession : AutoCloseable {
    /**
     * beforeStop→停输入→等最终事件→总结／Stop→实际 READY。仅 canStop 时受理。
     * Stop 202 为 STOP_PENDING 可再次确认，不重复末帧／停音源／总结；无痕以 ENDED 结束。
     */
    fun requestStop()
    /**
     * offsetMs 相对 onStarted.startedAtMs；调用时复制 JPEG。旧入口只表示排队，不是保存确认。
     * 只有开启演示截图且尚未完成 beforeStop 时接纳，失败提示不阻断字幕及 Stop。
     */
    fun submitPresentation(offsetMs: Long, jpeg: ByteArray)
    /**
     * 仅后端确认序号、帧数和产物 ID 后 onSuccess(null)，失败 onError；每次最多回调一次。
     * JPEG 调用时复制，IO 编码／网络；正文超过既有 1MiB 限额先报错且不消耗序号。
     * 保留至多一个未确认原包，下一次先确认旧包；不自动定时重试。
     * App 仅在 ACK 后推进去重基线；末帧无论成功／失败均须续接 beforeStop，不能阻塞 UI。
     * 默认明确不支持，不委托旧入口伪造 ACK。
     */
    fun submitPresentation(offsetMs: Long, jpeg: ByteArray, callback: MeetingClient.Callback<Void?>) {
        throw UnsupportedOperationException("当前会议实现未提供演示截图保存确认")
    }
    /** 交换呈现位置，不改语义侧及真实语种。 */
    fun swapDisplayColumns()
    /** 本场结果窄访问，READY 前返回类型化错误，关闭后不可继续，不重读当前账号或重做 Stop。 */
    fun resultActionAccess(): ResultActionAccess =
        throw UnsupportedOperationException("当前会议实现未提供结果动作访问")
    /** 幂等取消释放，不产生正常 Stop 或结果。 */
    override fun close()
}
