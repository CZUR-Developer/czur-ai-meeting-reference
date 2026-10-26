package com.czur.aimeeting.client

import java.util.logging.Level
import java.util.logging.Logger

/**
 * 会议活跃期的心跳定时器：只负责按间隔触发，不重试、不补发。
 *
 * <p>一次触发失败由注入的动作自行记录，下一次定时自然重试；本类不改变会议
 * 状态，也不因为单次失败停止定时。调度器由宿主注入，Android 前台服务可用
 * 主线程 Handler 实现，单元测试可用记录参数的假调度器。</p>
 */
class MeetingHeartbeatTimer(
    private val scheduler: Scheduler,
    private val beat: Runnable,
) {
    /** 可替换的重复调度来源；返回的句柄必须能取消尚未触发和后续触发。 */
    fun interface Scheduler {
        fun scheduleAtFixedRate(
            initialDelayMs: Long,
            intervalMs: Long,
            task: Runnable,
        ): Cancellation
    }

    fun interface Cancellation {
        fun cancel()
    }

    private var cancellation: Cancellation? = null
    private var intervalMs = 0L

    /**
     * 首次立即触发（initialDelay=0）再按 [intervalMs] 重复；重复启动会先停掉旧定时。
     *
     * @throws IllegalArgumentException 间隔不是正数
     */
    @Synchronized
    fun start(intervalMs: Long) {
        require(intervalMs > 0) { "heartbeat interval must be positive" }
        stop()
        this.intervalMs = intervalMs
        cancellation = scheduler.scheduleAtFixedRate(0L, intervalMs, Runnable {
            try {
                beat.run()
            } catch (failure: RuntimeException) {
                // 宿主回调异常不能让重复调度停摆；下一次继续按原间隔重试。
                LOG.log(Level.WARNING, "会议心跳任务未执行 type={0}",
                    failure.javaClass.simpleName)
            }
        })
    }

    /** 停止并取消尚未触发的下一次；重复调用安全。 */
    @Synchronized
    fun stop() {
        cancellation?.cancel()
        cancellation = null
        intervalMs = 0L
    }

    @Synchronized
    fun isRunning(): Boolean = cancellation != null

    @Synchronized
    fun intervalMs(): Long = intervalMs

    private companion object {
        private val LOG = Logger.getLogger(MeetingHeartbeatTimer::class.java.name)
    }
}
