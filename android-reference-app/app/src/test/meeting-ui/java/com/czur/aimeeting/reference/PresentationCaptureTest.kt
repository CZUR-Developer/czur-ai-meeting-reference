package com.czur.aimeeting.reference

import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingSession
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory
import com.czur.aimeeting.reference.ui.OverlayViewFactory
import com.czur.aimeeting.ui.DefaultMeetingOverlay
import java.util.ArrayDeque
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

/** SDK成品字节的薄接入、真实保存确认与Service异步末帧；不复制算法或连接后端。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class PresentationCaptureTest {
    @After fun resetFactory() = MeetingAppAssembly.registerOverlayFactory(OverlayViewFactory())

    @Test fun sdkBytesRemainUnchangedAndConfirmationBelongsToBusiness() {
        val events = ArrayList<String>()
        val source = FakeSource(true, events)
        val capture = PresentationCapture(source, 1000)
        var delivered: PresentationCapture.Frame? = null
        capture.capture(13345, emptyArray(), object : PresentationCapture.Callback {
            override fun onSuccess(frame: PresentationCapture.Frame?) { delivered = frame }
            override fun onError(message: String) { fail(message) }
        })
        val image = source.deliver()
        val frame = requireNotNull(delivered)
        assertEquals(12345L, frame.offsetMs())
        assertSame("SDK成品字节不得在App重新编码或改写", image.jpeg, frame.jpeg())
        assertTrue(events.isEmpty())
        frame.confirmAccepted()
        assertEquals(listOf("confirm"), events)
        frame.close()
        capture.close()
    }

    @Test fun periodicAckPrecedesFinalCaptureAndFinalAckPrecedesStopContinuation() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val events = ArrayList<String>()
        val source = FakeSource(true, events)
        val presentation = PresentationCapture(source, SystemClock.elapsedRealtime() - 1200)
        val session = RecordingSession(events)
        set(service, "presentation", presentation)
        set(service, "active", true)
        set(service, "session", session)
        try {
            // 周期尚未取得真实ACK时，末帧只排队，不先调用SDK采集。
            (get(service, "presentationTick") as Runnable).run()
            service.stopPresentationBefore { events.add("continue") }
            assertEquals(1, source.pending.size)
            assertTrue(events.isEmpty())
            val first = source.deliver()
            assertEquals(listOf("submit:1200"), events)
            assertSame(first.jpeg, session.submitted.single())
            assertEquals(0, source.pending.size)
            session.acknowledge()
            assertEquals(listOf("submit:1200", "confirm"), events)
            assertEquals(1, source.pending.size)
            source.deliver()
            assertEquals(listOf("submit:1200", "confirm", "submit:1200"), events)
            session.acknowledge()
            assertEquals(listOf("submit:1200", "confirm", "submit:1200", "confirm", "close", "continue"), events)
            assertNull(get(service, "presentation"))
        } finally { host.destroy() }
    }

    @Test fun rejectedFinalFrameDoesNotConfirmButStillContinuesStop() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val events = ArrayList<String>()
        val source = FakeSource(true, events)
        val session = RecordingSession(events)
        set(service, "presentation", PresentationCapture(source, SystemClock.elapsedRealtime() - 1200))
        set(service, "session", session)
        try {
            service.stopPresentationBefore { events.add("continue") }
            source.deliver()
            assertEquals(listOf("submit:1200"), events)
            session.reject()
            assertEquals(listOf("submit:1200", "close", "continue"), events)
            assertNull(get(service, "presentation"))
        } finally { host.destroy() }
    }

    @Test fun noNewImageFinalFrameClosesAndContinuesWithoutFailureNotice() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val events = ArrayList<String>()
        val source = FakeSource(true, events)
        set(service, "presentation", PresentationCapture(source, 0))
        set(service, "session", RecordingSession(events))
        try {
            service.stopPresentationBefore { events.add("continue") }
            source.skip()
            assertEquals(listOf("close", "continue"), events)
            assertEquals("", get(service, "presentationNotice"))
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
        val session = RecordingSession(ArrayList())
        set(service, "presentation", PresentationCapture(source, 0))
        set(service, "session", session)
        val overlay = get(service, "overlay") as MeetingOverlay
        overlay.show(emptyDisplay(), {}, {}, {}, {})
        try {
            assertEquals(1, overlay.captureWindowTokens().size)
            service.stopPresentationBefore {}
            assertEquals(0, source.excluded.size)
            source.deliver()
            session.acknowledge()
        } finally { host.destroy() }
    }

    @Test fun closingHostEndsPendingRequestAndIgnoresLateOutputWithoutConfirming() {
        val events = ArrayList<String>()
        val source = FakeSource(true, events)
        val capture = PresentationCapture(source, 0)
        capture.capture(1000, emptyArray(), object : PresentationCapture.Callback {
            override fun onSuccess(frame: PresentationCapture.Frame?) { fail("已关闭宿主不再交付此帧") }
            override fun onError(message: String) { events.add("ended") }
        })
        capture.close()
        source.deliver()
        assertEquals(listOf("close", "ended"), events)
    }

    @Test fun actualOverlayStopWaitsForFinalFrameBeforeReturningAndContinuing() {
        OverlayStopFixture(true).use { fixture ->
            fixture.clickStop()
            assertEquals(listOf("stop"), fixture.events)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(1, fixture.source.pending.size)
            fixture.source.deliver()
            assertEquals(listOf("stop", "submit"), fixture.events)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            fixture.session.acknowledge()
            assertEquals(listOf("stop", "submit", "confirm", "close", "return", "continue"), fixture.events)
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
            fixture.source.deliver()
            assertEquals(listOf("stop", "close", "return", "continue"), fixture.events)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }

    @Test fun lateBusinessAckDoesNotConfirmOrFinishAnotherCaptureSession() {
        OverlayStopFixture(true).use { fixture ->
            fixture.clickStop()
            fixture.source.deliver()
            MeetingService::class.java.getDeclaredMethod("onPresentationStopped").apply {
                isAccessible = true
                invoke(fixture.service)
            }
            assertEquals(listOf("stop", "submit", "close", "return", "continue"), fixture.events)
            val next = PresentationCapture(FakeSource(true, fixture.events), 0)
            set(fixture.service, "presentation", next)
            set(fixture.service, "presentationStopContinuation", Runnable { fixture.events.add("nextContinue") })
            fixture.session.acknowledge()
            assertEquals(listOf("stop", "submit", "close", "return", "continue"), fixture.events)
            assertSame(next, get(fixture.service, "presentation"))
        }
    }

    /** 只控制SDK输出和业务ACK时刻；默认窗口按钮→Service→beforeStop仍走实际实现。 */
    private class OverlayStopFixture(withCapture: Boolean) : AutoCloseable {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val events = ArrayList<String>()
        val source = object : FakeSource(true, events) {
            override fun close() {
                assertNull("发出关闭请求前不能先打开MainActivity",
                    Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
                super.close()
            }
        }
        val session = object : RecordingSession(events) {
            override fun recordSubmission(offsetMs: Long) { events.add("submit") }
            override fun requestStop() {
                if (!withCapture) { assertReturnedToMeeting(); events.add("return") }
                events.add("stop")
                service.stopPresentationBefore {
                    if (withCapture) { assertReturnedToMeeting(); events.add("return") }
                    events.add("continue")
                }
            }
        }

        init {
            ShadowSettings.setCanDrawOverlays(true)
            set(service, "active", true)
            set(service, "canStop", true)
            set(service, "session", session)
            service.toggleOverlay()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            // 窗口挂载会执行UI消息并推进时钟；在点击Stop前建立受控会议起点。
            if (withCapture) set(service, "presentation", PresentationCapture(source,
                SystemClock.elapsedRealtime() - 1200))
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

    private open class FakeSource(private val exclusion: Boolean, private val events: MutableList<String>) : ScreenCaptureSource {
        val pending = ArrayDeque<ScreenCaptureSource.Callback>()
        lateinit var excluded: Array<IBinder?>
        override fun mode() = if (exclusion) "SYSTEM_CAPTURE" else "MEDIA_PROJECTION"
        override fun supportsWindowExclusion() = exclusion
        override fun capture(excludedWindowTokens: Array<IBinder?>, callback: ScreenCaptureSource.Callback) {
            excluded = excludedWindowTokens
            pending.add(callback)
        }
        fun deliver() = ScreenCaptureSource.Image(byteArrayOf(1, 2, 3)) { events.add("confirm") }.also {
            pending.remove().onSuccess(it)
        }
        fun skip() = pending.remove().onSuccess(null)
        override fun close() { events.add("close") }
    }

    private open class RecordingSession(private val events: MutableList<String>) : MeetingSession {
        private val acknowledgements = ArrayDeque<MeetingClient.Callback<Void?>>()
        val submitted = ArrayList<ByteArray>()
        override fun submitPresentation(offsetMs: Long, jpeg: ByteArray) {
            fail("两参旧入口不能代签保存ACK")
        }
        override fun submitPresentation(offsetMs: Long, jpeg: ByteArray, callback: MeetingClient.Callback<Void?>) {
            recordSubmission(offsetMs)
            submitted.add(jpeg)
            acknowledgements.add(callback)
        }
        protected open fun recordSubmission(offsetMs: Long) { events.add("submit:$offsetMs") }
        fun acknowledge() = acknowledgements.remove().onSuccess(null)
        fun reject() = acknowledgements.remove().onError("保存未成功")
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
