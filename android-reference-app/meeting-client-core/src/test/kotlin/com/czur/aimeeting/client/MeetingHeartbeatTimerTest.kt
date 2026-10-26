package com.czur.aimeeting.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 用可注入的调度器验证心跳定时的启动、停止与失败隔离，不依赖真实时间。 */
class MeetingHeartbeatTimerTest {
    private class FakeScheduler : MeetingHeartbeatTimer.Scheduler {
        var initialDelayMs = -1L
        var intervalMs = -1L
        var task: Runnable? = null
        var cancels = 0
        var schedules = 0

        override fun scheduleAtFixedRate(
            initialDelayMs: Long,
            intervalMs: Long,
            task: Runnable,
        ): MeetingHeartbeatTimer.Cancellation {
            schedules++
            this.initialDelayMs = initialDelayMs
            this.intervalMs = intervalMs
            this.task = task
            return MeetingHeartbeatTimer.Cancellation { cancels++ }
        }

        fun fire() {
            val current = task
            if (current == null) {
                fail("调度器尚未收到任务")
                return
            }
            current.run()
        }
    }

    @Test fun startsImmediatelyThenRepeatsUntilStopped() {
        val scheduler = FakeScheduler()
        val beats = AtomicInteger()
        val timer = MeetingHeartbeatTimer(scheduler) { beats.incrementAndGet() }

        assertFalse(timer.isRunning())
        timer.start(120_000L)

        assertTrue(timer.isRunning())
        assertEquals(120_000L, timer.intervalMs())
        assertEquals("首次心跳必须在会议启动后尽快发出", 0L, scheduler.initialDelayMs)
        assertEquals(120_000L, scheduler.intervalMs)
        scheduler.fire()
        assertEquals(1, beats.get())

        timer.stop()
        assertFalse(timer.isRunning())
        assertEquals(0L, timer.intervalMs())
        assertEquals(1, scheduler.cancels)
    }

    @Test fun restartCancelsPreviousScheduleAndReplacesInterval() {
        val scheduler = FakeScheduler()
        val timer = MeetingHeartbeatTimer(scheduler) { }

        timer.start(60_000L)
        timer.start(30_000L)

        assertEquals(2, scheduler.schedules)
        assertEquals("旧定时必须先取消", 1, scheduler.cancels)
        assertEquals(30_000L, scheduler.intervalMs)
        assertEquals(30_000L, timer.intervalMs())
        timer.stop()
    }

    @Test fun failedBeatIsLoggedAndTimerKeepsRunning() {
        val scheduler = FakeScheduler()
        val timer = MeetingHeartbeatTimer(scheduler) {
            throw IllegalStateException("fixture-heartbeat-failed")
        }

        timer.start(1_000L)
        scheduler.fire()

        assertTrue("单次心跳异常不能停掉重复调度", timer.isRunning())
        timer.stop()
    }

    @Test fun rejectsNonPositiveInterval() {
        val timer = MeetingHeartbeatTimer(FakeScheduler()) { }
        try {
            timer.start(0L)
            fail("0 不是合法心跳间隔")
        } catch (expected: IllegalArgumentException) {
            assertFalse(timer.isRunning())
        }
    }
}
