package com.czur.aimeeting.reference

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.content.Context
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingHeartbeatTimer
import com.czur.aimeeting.client.MeetingSession
import com.czur.aimeeting.client.display.DisplayRouter
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.model.AudioInputLevel
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import com.czur.aimeeting.reference.meeting.MeetingTimeRule
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly

/** 正常 UI 生命周期使用真实 Service／窗口；不建会、不联网，也不启动音源。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeetingServicePresentationTest {
    @Test fun overlayStopUpdatesStateBeforeReturningToTheSingleSetupHost() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        var activeWhenStopped = true
        var overlayWhenStopped = true
        val session = Proxy.newProxyInstance(MeetingSession::class.java.classLoader, arrayOf(MeetingSession::class.java)) { _, method, _ ->
            if (method.name == "requestStop") {
                activeWhenStopped = get(service, "active") as Boolean
                overlayWhenStopped = (get(service, "overlay") as MeetingOverlay).isShowing()
            }
            null
        } as MeetingSession
        set(service, "active", true); set(service, "canStop", true); set(service, "session", session)
        try {
            assertTrue(service.showOverlay())
            val token = (get(service, "overlay") as MeetingOverlay).captureWindowTokens().first()
            assertTrue(service.showOverlay())
            assertSame("重复打开保留原浮窗", token, (get(service, "overlay") as MeetingOverlay).captureWindowTokens().first())
            invoke(service, "requestStopFromOverlay")
            assertFalse(activeWhenStopped)
            assertFalse(overlayWhenStopped)
            assertEquals(MainActivity::class.java.name, shadowOf(service).nextStartedActivity.component!!.className)
            assertNull(shadowOf(service).nextStartedActivity)
        } finally { host.destroy() }
    }

    @Test fun queuedResultWaitsWhileScreenIsOffThenOpensItsHostOnWake() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val power = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(false)
        try {
            val command = AppResultActionPlugin.UiOperation(Runnable { })
            invoke(service, "queueUiOperation", Runnable::class.java, command)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(MeetingAppAssembly.hasPendingResultUi())
            assertNull(shadowOf(service).nextStartedActivity)
            shadowOf(power).setIsInteractive(true)
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(MainActivity::class.java.name, shadowOf(service).nextStartedActivity.component!!.className)
        } finally {
            while (MeetingAppAssembly.consumeResultUiOperation() != null) { }
            host.destroy()
        }
    }

    @Test fun normalStopDismissesOverlayAndFreezesClockBeforeBackendResultArrives() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val stops = AtomicInteger()
        val closes = AtomicInteger()
        val session = Proxy.newProxyInstance(MeetingSession::class.java.classLoader,
            arrayOf(MeetingSession::class.java)) { _, method, _ ->
            if (method.name == "requestStop") stops.incrementAndGet()
            null
        } as MeetingSession
        val client = Proxy.newProxyInstance(MeetingClient::class.java.classLoader,
            arrayOf(MeetingClient::class.java)) { _, method, _ ->
            if (method.name == "close") closes.incrementAndGet()
            null
        } as MeetingClient
        var state: MeetingService.State? = null
        set(service, "session", session)
        set(service, "client", client)
        set(service, "busy", true)
        set(service, "active", true)
        set(service, "canStop", true)
        set(service, "timing", MeetingTimeRule(SystemClock.elapsedRealtime() - 60_000L, 3_600_000L))
        set(service, "audioLevel", AudioInputLevel(45, SystemClock.elapsedRealtime()))
        service.observe { state = it }
        try {
            service.toggleOverlay()
            assertTrue(state!!.overlayShowing)
            service.requestStop()
            assertEquals(1, stops.get())
            assertFalse(state!!.overlayShowing)
            assertFalse(state!!.active)
            assertFalse(state!!.canStop)
            assertNull(state!!.audioLevel)
            assertTrue("会后工作仍由同一场持有", state!!.busy)
            assertNull(state!!.readyResult)
            assertSame(session, get(service, "session"))
            assertEquals(0, closes.get())
            val stoppedTime = state!!.timeText
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            invoke(service, "publish")
            assertEquals(stoppedTime, state!!.timeText)
            assertNull(state!!.readyResult)
        } finally { host.destroy() }
    }

    @Test fun heartbeatTimerStartsOnlyWithPositiveIntervalAndStopsWithPresentation() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val beats = AtomicInteger()
        val session = Proxy.newProxyInstance(MeetingSession::class.java.classLoader,
            arrayOf(MeetingSession::class.java)) { _, method, _ ->
            if (method.name == "heartbeat") beats.incrementAndGet()
            null
        } as MeetingSession
        set(service, "session", session)
        val timer = get(service, "heartbeatTimer") as MeetingHeartbeatTimer
        try {
            invoke(service, "startHeartbeat", Long::class.javaPrimitiveType!!, 0L)
            assertFalse("旧服务端缺字段时不发心跳", timer.isRunning())
            invoke(service, "startHeartbeat", Long::class.javaPrimitiveType!!, 120_000L)
            assertTrue(timer.isRunning())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("首次心跳在启动后立即发出", 1, beats.get())

            set(service, "active", true)
            set(service, "timing", MeetingTimeRule(SystemClock.elapsedRealtime(), 3_600_000L))
            invoke(service, "stopMeetingPresentation")
            assertFalse("停止会议呈现必须取消定时器", timer.isRunning())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
            assertEquals("取消后不再补发心跳", 1, beats.get())
        } finally { host.destroy() }
    }

    @Test fun idleClearAffectsOnlyPresentationAndFreshOriginalRestoresBothViews() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val original = display("原文", "Translation")
        var state: MeetingService.State? = null
        set(service, "active", true)
        set(service, "display", original)
        set(service, "timing", MeetingTimeRule(SystemClock.elapsedRealtime() - 10_001L, 3_600_000L))
        set(service, "transcriptVersion", 1)
        service.observe { state = it }
        try {
            service.toggleOverlay()
            val tick = get(service, "clockTick") as Runnable
            tick.run()
            assertTrue(state!!.active)
            assertTrue(state!!.overlayShowing)
            assertTrue(state!!.display.rows().isEmpty())
            assertTrue(state!!.display.bilingual())
            assertTrue(state!!.display.swapped())
            assertEquals(1, original.rows().size)
            assertEquals("原文", original.rows()[0].transcript()!!.text())
            val clearedVersion = state!!.transcriptVersion
            tick.run()
            assertEquals("同一无原文区间只清屏一次", clearedVersion, state!!.transcriptVersion)

            // SDK 的迟到译文／交换仍可产生完整快照，但不应独自解除无原文清屏。
            invoke(service, "updateDisplay", MeetingDisplaySnapshot::class.java, display("原文", "Late translation"))
            assertTrue(state!!.display.rows().isEmpty())
            invoke(service, "recordTranscriptActivity", Long::class.javaPrimitiveType!!, SystemClock.elapsedRealtime())
            val resumed = display("新原文", "New translation")
            invoke(service, "updateDisplay", MeetingDisplaySnapshot::class.java, resumed)
            assertSame(resumed, state!!.display)
            assertEquals("新原文", state!!.display.rows()[0].transcript()!!.text())
            assertTrue(state!!.overlayShowing)
            assertEquals("原文", original.rows()[0].transcript()!!.text())
        } finally { host.destroy() }
    }

    @Test fun screenOffUsesNormalStopOnceAndPreservesTheSameCompletionSession() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val stops = AtomicInteger()
        val session = Proxy.newProxyInstance(MeetingSession::class.java.classLoader,
            arrayOf(MeetingSession::class.java)) { _, method, _ ->
            if (method.name == "requestStop") stops.incrementAndGet()
            null
        } as MeetingSession
        set(service, "session", session)
        set(service, "busy", true)
        set(service, "active", true)
        set(service, "canStop", true)
        var state: MeetingService.State? = null
        service.observe { state = it }
        try {
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, stops.get())
            assertFalse(state!!.active)
            assertFalse(state!!.canStop)
            assertTrue(state!!.busy)
            assertSame(session, get(service, "session"))
            assertEquals("屏幕已关闭，正在等待模型最终字幕…", state!!.status)

            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("已停止的本场不因重复息屏广播再次停止", 1, stops.get())
        } finally { host.destroy() }
    }

    private fun display(text: String, translated: String) = MeetingDisplaySnapshot(true, "zh-CN", "en-US", true,
        listOf(MeetingDisplaySnapshot.Row("one", TranscriptEvent("one", 1, 0, 2000, "zh-CN", text, true),
            TranslationEvent("tr", "one", 1, "zh-CN", "en-US", translated, true),
            MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE)), "one")

    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun invoke(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }
    private fun invoke(target: Any, name: String, type: Class<*>, value: Any) {
        target.javaClass.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(target, value)
    }
}
