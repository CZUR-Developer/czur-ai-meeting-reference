package com.czur.aimeeting.reference.ui;

import static org.junit.Assert.*;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.ui.OverlayController;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSettings;

/** 单形态例通过同一装配/快照/回调入口，不要求默认四项，不改会议核心。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SingleFormOverlayFactoryTest {
    @Before public void allowOverlay() { ShadowSettings.setCanDrawOverlays(true); }
    @After public void restoreDefault() { MeetingAppAssembly.registerOverlayFactory(new OverlayViewFactory()); }

    @Test public void onlyOneCustomIdUsesLatestHiddenSubtitleAndOriginalStop() {
        AtomicInteger stops = new AtomicInteger();
        MeetingAppAssembly.registerOverlayFactory(new SingleFormOverlayFactory());
        var window = (SingleFormOverlayFactory.SingleWindow) MeetingAppAssembly.overlayFactory().create(
                RuntimeEnvironment.getApplication(), () -> { }, () -> { }, stops::incrementAndGet);
        assertTrue(window.show(OverlayViewFactoryTest.single("draft", false)));
        assertEquals(Set.of(SingleFormOverlayFactory.FORM_ID), window.controller.registeredFormIds());
        window.controller.hide();
        window.render(OverlayViewFactoryTest.single("finished while hidden", true), MeetingSetup.Mode.LIVE_TRANSCRIPT,
                false, "00:20", true, 38);
        window.show(OverlayViewFactoryTest.single("unused", false));
        OverlayViewFactoryTest.measure(window.controller);
        assertEquals("finished while hidden", OverlayViewFactoryTest.cell(window.controller, "overlay.subtitle").getParts().get(0).text().toString());
        OverlayViewFactoryTest.visible(window.controller, "overlay.stop").performClick();
        assertEquals(1, stops.get());
        window.dismiss();
        assertEquals(OverlayController.State.RELEASED, window.controller.state());
    }

    @Test public void sameSingleFormKeepsBilingualSideSwapAndIndependentFinal() {
        AtomicInteger swaps = new AtomicInteger();
        var window = (SingleFormOverlayFactory.SingleWindow) new SingleFormOverlayFactory().create(
                RuntimeEnvironment.getApplication(), () -> { }, swaps::incrementAndGet, () -> { });
        window.show(OverlayViewFactoryTest.bilingual(false));
        window.render(OverlayViewFactoryTest.bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION,
                true, "00:10", true, 66);
        OverlayViewFactoryTest.measure(window.controller);
        assertTrue(OverlayViewFactoryTest.cell(window.controller, "overlay.left").getParts().isEmpty());
        assertEquals("source", OverlayViewFactoryTest.cell(window.controller, "overlay.right").getParts().get(0).text().toString());
        assertEquals("译文", OverlayViewFactoryTest.cell(window.controller, "overlay.right-translation").getParts().get(0).text().toString());
        assertTrue(OverlayViewFactoryTest.cell(window.controller, "overlay.right-translation").getParts().get(0).isFinal());
        assertFalse(OverlayViewFactoryTest.cell(window.controller, "overlay.right").getParts().get(0).isFinal());
        assertEquals(android.view.View.GONE, window.controller.contentView().findViewWithTag("overlay.size").getVisibility());
        OverlayViewFactoryTest.visible(window.controller, "overlay.swap").performClick();
        assertEquals(1, swaps.get());
        window.render(OverlayViewFactoryTest.bilingual(true), MeetingSetup.Mode.LIVE_TRANSLATION,
                true, "00:20", false, 0);
        OverlayViewFactoryTest.measure(window.controller);
        assertEquals("source", OverlayViewFactoryTest.cell(window.controller, "overlay.left").getParts().get(0).text().toString());
        assertFalse(OverlayViewFactoryTest.visible(window.controller, "overlay.stop").isEnabled());
        assertEquals(Set.of(SingleFormOverlayFactory.FORM_ID), window.controller.registeredFormIds());
        window.dismiss();
    }
}
