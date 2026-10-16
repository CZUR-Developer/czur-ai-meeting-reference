package com.czur.aimeeting.reference

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.czur.aimeeting.client.MeetingSession
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory
import com.czur.aimeeting.reference.ui.OverlayViewFactory
import com.czur.aimeeting.ui.DefaultMeetingOverlay
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** 正常SDK图像接入、JPEG策略和Service异步末帧；不操作设备或连接会议后端。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class PresentationCaptureTest {
    @After fun resetFactory() = MeetingAppAssembly.registerOverlayFactory(OverlayViewFactory())

    @Test fun jpegRetains1280AspectRatioOffsetAndRecyclesDeliveredBitmap() {
        val captured = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
        captured.eraseColor(Color.RED)
        val frame = PresentationCapture.encode(captured, 12345)
        assertEquals(12345L, frame.offsetMs())
        assertTrue(captured.isRecycled)
        assertTrue(frame.jpeg().size < 750_000)
        val decoded = BitmapFactory.decodeByteArray(frame.jpeg(), 0, frame.jpeg().size)
        assertEquals(1280, decoded.width)
        assertEquals(720, decoded.height)
        decoded.recycle()
    }

    @Test fun periodicAndFinalFramesSubmitThenCloseBeforeContinuationWithoutWaiting() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val events = ArrayList<String>()
        val source = FakeSource(true, events)
        val work = WorkQueue()
        val callbacks = WorkQueue()
        val presentation = PresentationCapture(source, SystemClock.elapsedRealtime() - 1200, work, callbacks)
        set(service, "presentation", presentation)
        set(service, "active", true)
        set(service, "session", RecordingSession(events))
        try {
            // 实际10秒tick中的请求与Stop末帧共用原Service和同一JPEG工作队列。
            (get(service, "presentationTick") as Runnable).run()
            service.stopPresentationBefore { events.add("continue") }
            assertEquals(2, source.pending.size)
            assertTrue(events.isEmpty())
            val first = source.deliver()
            val last = source.deliver()
            assertTrue(events.isEmpty())
            assertFalse(first.isRecycled)
            assertFalse(last.isRecycled)
            work.drain()
            assertTrue(first.isRecycled)
            assertTrue(last.isRecycled)
            assertTrue(events.isEmpty())
            callbacks.drain()
            assertEquals(listOf("submit:1200", "submit:1200", "close", "continue"), events)
            assertNull(get(service, "presentation"))
        } finally { host.destroy() }
    }

    @Test fun eachCaptureCollectsMountedWindowAndReminderTokens() {
        ShadowSettings.setCanDrawOverlays(true)
        val overlay = MeetingOverlay(RuntimeEnvironment.getApplication())
        val display = emptyDisplay()
        try {
            assertEquals(0, overlay.captureWindowTokens().size)
            overlay.show(display, {}, {}, {}, {})
            overlay.render(display, MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:10",
                MeetingReminderPresentation.Notice(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
                    "长时间无转写", "请确认"), true, true, 20)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val window = get(overlay, "window") as MeetingOverlayFactory.Window
            val scrim = get(overlay, "reminderScrim") as View
            val popup = get(overlay, "reminderPopup") as MeetingReminderPopup
            val tokens = overlay.captureWindowTokens()
            assertEquals(3, tokens.size)
            assertArrayEquals(arrayOf(window.windowToken(), scrim.windowToken, popup.view().windowToken), tokens)
            for (token in tokens) assertNotNull(token)
            overlay.dismiss()
            assertEquals(0, overlay.captureWindowTokens().size)
            overlay.show(display, {}, {}, {}, {})
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val reattached = get(overlay, "window") as MeetingOverlayFactory.Window
            assertArrayEquals(arrayOf(reattached.windowToken()), overlay.captureWindowTokens())
        } finally { overlay.dismiss() }
    }

    @Test fun oldPlatformSelectsEmptyExclusionsBeforeCapture() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val source = FakeSource(false, ArrayList())
        val work = WorkQueue()
        val callbacks = WorkQueue()
        set(service, "presentation", PresentationCapture(source, 0, work, callbacks))
        set(service, "session", RecordingSession(ArrayList()))
        val overlay = get(service, "overlay") as MeetingOverlay
        overlay.show(emptyDisplay(), {}, {}, {}, {})
        try {
            assertEquals(1, overlay.captureWindowTokens().size)
            service.stopPresentationBefore {}
            assertEquals(0, source.excluded.size)
            source.deliver()
            work.drain()
            callbacks.drain()
        } finally { host.destroy() }
    }

    @Test fun closingHostRecyclesDeliveredImageStillQueuedForEncoding() {
        val source = FakeSource(true, ArrayList())
        val work = WorkQueue()
        val callbacks = WorkQueue()
        val capture = PresentationCapture(source, 0, work, callbacks)
        capture.capture(1000, emptyArray(), object : PresentationCapture.Callback {
            override fun onSuccess(frame: PresentationCapture.Frame) { fail("已关闭宿主不再交付此帧") }
            override fun onError(message: String) {}
        })
        val delivered = source.deliver()
        assertFalse(delivered.isRecycled)
        capture.close()
        assertTrue(delivered.isRecycled)
        work.drain()
        callbacks.drain()
    }

    @Test fun actualOverlayStopWaitsForFinalFrameBeforeReturningAndContinuing() {
        OverlayStopFixture(true).use { fixture ->
            fixture.clickStop()
            assertEquals(listOf("stop"), fixture.events)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(1, fixture.source.pending.size)
            fixture.source.deliver()
            fixture.work.drain()
            assertEquals(listOf("stop"), fixture.events)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            fixture.callbacks.drain()
            assertEquals(listOf("stop", "submit", "close", "return", "continue"), fixture.events)
            assertNull(get(fixture.service, "presentation"))
            assertFalse(get(fixture.service, "canStop") as Boolean)
        }
    }

    @Test fun actualOverlayStopWithoutCaptureKeepsImmediateReturnAndNormalStop() {
        OverlayStopFixture(false).use { fixture ->
            fixture.clickStop()
            assertEquals(listOf("return", "stop", "continue"), fixture.events)
            assertFalse(get(fixture.service, "canStop") as Boolean)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }

    @Test fun revokedCaptureContinuesOverlayStopWithoutDuplicateReturnFromLateFrame() {
        OverlayStopFixture(true).use { fixture ->
            fixture.clickStop()
            MeetingService::class.java.getDeclaredMethod("onPresentationStopped").apply {
                isAccessible = true
                invoke(fixture.service)
            }
            assertEquals(listOf("stop", "close", "return", "continue"), fixture.events)
            // 撤销时末帧尚在途：沿原SDK回调结束，不再回页或重复续接停止。
            val late = fixture.source.deliver()
            assertTrue(late.isRecycled)
            fixture.work.drain()
            fixture.callbacks.drain()
            assertEquals(listOf("stop", "close", "return", "continue"), fixture.events)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }

    /** 仅控制截图返回的时刻；实际默认窗口按钮→Service→beforeStop链和JPEG路径保持。 */
    private class OverlayStopFixture(withCapture: Boolean) : AutoCloseable {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val events = ArrayList<String>()
        val work = WorkQueue()
        val callbacks = WorkQueue()
        val source = object : FakeSource(true, events) {
            override fun close() {
                assertNull("发出关闭请求前不能先打开MainActivity",
                    Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
                super.close()
            }
        }

        init {
            ShadowSettings.setCanDrawOverlays(true)
            set(service, "active", true)
            set(service, "canStop", true)
            set(service, "session", object : RecordingSession(events) {
                override fun submitPresentation(offsetMs: Long, jpeg: ByteArray) {
                    // 验证回页顺序；点击控件推进测试时钟，偏移规则由前面的原测试验证。
                    events.add("submit")
                }
                override fun requestStop() {
                    if (!withCapture) { assertReturnedToMeeting(); events.add("return") }
                    events.add("stop")
                    service.stopPresentationBefore {
                        if (withCapture) { assertReturnedToMeeting(); events.add("return") }
                        events.add("continue")
                    }
                }
            })
            service.toggleOverlay()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            // 窗口挂载会执行UI消息并推进时钟；在点击Stop前建立受控会议起点。
            if (withCapture) set(service, "presentation", PresentationCapture(source,
                SystemClock.elapsedRealtime() - 1200, work, callbacks))
        }

        fun clickStop() {
            val window = get(get(service, "overlay")!!, "window")!!
            val displayed = get(window, "overlay") as DefaultMeetingOverlay
            val stop = displayed.controller().contentView()!!.findViewWithTag<View>("overlay.stop")
            assertNotNull(stop)
            assertTrue(stop.isEnabled)
            assertTrue(stop.performClick())
        }

        private fun assertReturnedToMeeting() {
            val returned = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertNotNull("需要通过原回页入口返回会议页", returned)
            assertEquals(MainActivity::class.java.name, returned.component!!.className)
        }

        override fun close() { host.destroy() }
    }

    private class WorkQueue : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued.add(command) }
        fun drain() { while (queued.isNotEmpty()) queued.remove().run() }
    }

    private open class FakeSource(private val exclusion: Boolean, private val events: MutableList<String>) : ScreenCaptureSource {
        val pending = ArrayDeque<ScreenCaptureSource.Callback>()
        lateinit var excluded: Array<IBinder?>
        override fun mode() = if (exclusion) "SYSTEM_CAPTURE" else "MEDIA_PROJECTION"
        override fun supportsWindowExclusion() = exclusion
        override fun captureBitmap(excludedWindowTokens: Array<IBinder?>, callback: ScreenCaptureSource.Callback) {
            excluded = excludedWindowTokens
            pending.add(callback)
        }
        fun deliver(): Bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).also { pending.remove().onSuccess(it) }
        override fun close() { events.add("close") }
    }

    private open class RecordingSession(private val events: MutableList<String>) : MeetingSession {
        override fun submitPresentation(offsetMs: Long, jpeg: ByteArray) { events.add("submit:$offsetMs") }
        override fun requestStop() {}
        override fun swapDisplayColumns() {}
        override fun close() {}
    }

    companion object {
        private fun emptyDisplay() = MeetingDisplaySnapshot(false, "zh-CN", "en-US", false, emptyList(), null)
        private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target)
        }
        private fun set(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }
}
