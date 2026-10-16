package com.czur.aimeeting.reference.ui

import android.os.Looper
import android.view.View
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.ui.OverlayController
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

/** 单形态例通过同一装配/快照/回调入口，不要求默认四项，不改会议核心。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SingleFormOverlayFactoryTest {
    @Before fun allowOverlay() = ShadowSettings.setCanDrawOverlays(true)
    @After fun restoreDefault() = MeetingAppAssembly.registerOverlayFactory(OverlayViewFactory())

    @Test fun onlyOneCustomIdUsesLatestHiddenSubtitleAndOriginalStop() {
        val stops = AtomicInteger()
        MeetingAppAssembly.registerOverlayFactory(SingleFormOverlayFactory())
        val window = MeetingAppAssembly.overlayFactory().create(RuntimeEnvironment.getApplication(), {}, {},
            { stops.incrementAndGet() }) as SingleFormOverlayFactory.SingleWindow
        assertTrue(window.show(OverlayViewFactoryTest.single("draft", false)))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(window.windowToken())
        assertSame(window.controller.contentView()!!.windowToken, window.windowToken())
        assertEquals(setOf(SingleFormOverlayFactory.FORM_ID), window.controller.registeredFormIds())
        window.controller.hide()
        assertNull(window.windowToken())
        window.render(OverlayViewFactoryTest.single("finished while hidden", true), MeetingSetup.Mode.LIVE_TRANSCRIPT,
            false, "00:20", true, 38)
        window.show(OverlayViewFactoryTest.single("unused", false))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(window.windowToken())
        assertSame(window.controller.contentView()!!.windowToken, window.windowToken())
        OverlayViewFactoryTest.measure(window.controller)
        assertEquals("finished while hidden", OverlayViewFactoryTest.cell(window.controller, "overlay.subtitle").getParts()[0].text().toString())
        OverlayViewFactoryTest.visible(window.controller, "overlay.stop").performClick()
        assertEquals(1, stops.get())
        window.dismiss()
        assertEquals(OverlayController.State.RELEASED, window.controller.state())
    }

    @Test fun sameSingleFormKeepsBilingualSideSwapAndIndependentFinal() {
        val swaps = AtomicInteger()
        val window = SingleFormOverlayFactory().create(RuntimeEnvironment.getApplication(), {},
            { swaps.incrementAndGet() }, {}) as SingleFormOverlayFactory.SingleWindow
        window.show(OverlayViewFactoryTest.bilingual(false))
        window.render(OverlayViewFactoryTest.bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION, true, "00:10", true, 66)
        OverlayViewFactoryTest.measure(window.controller)
        assertTrue(OverlayViewFactoryTest.cell(window.controller, "overlay.left").getParts().isEmpty())
        assertEquals("source", OverlayViewFactoryTest.cell(window.controller, "overlay.right").getParts()[0].text().toString())
        assertEquals("译文", OverlayViewFactoryTest.cell(window.controller, "overlay.right-translation").getParts()[0].text().toString())
        assertTrue(OverlayViewFactoryTest.cell(window.controller, "overlay.right-translation").getParts()[0].isFinal())
        assertFalse(OverlayViewFactoryTest.cell(window.controller, "overlay.right").getParts()[0].isFinal())
        assertEquals(View.GONE, window.controller.contentView()!!.findViewWithTag<View>("overlay.size").visibility)
        OverlayViewFactoryTest.visible(window.controller, "overlay.swap").performClick()
        assertEquals(1, swaps.get())
        window.render(OverlayViewFactoryTest.bilingual(true), MeetingSetup.Mode.LIVE_TRANSLATION, true, "00:20", false, 0)
        OverlayViewFactoryTest.measure(window.controller)
        assertEquals("source", OverlayViewFactoryTest.cell(window.controller, "overlay.left").getParts()[0].text().toString())
        assertFalse(OverlayViewFactoryTest.visible(window.controller, "overlay.stop").isEnabled)
        assertEquals(setOf(SingleFormOverlayFactory.FORM_ID), window.controller.registeredFormIds())
        window.dismiss()
    }
}
