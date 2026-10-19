package com.czur.aimeeting.reference.ui

import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.czur.aimeeting.client.display.DisplayRouter
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.reference.R
import com.czur.aimeeting.ui.DefaultMeetingOverlay
import com.czur.aimeeting.ui.OverlayController
import com.czur.aimeeting.ui.SubtitleTextView
import java.util.concurrent.atomic.AtomicInteger
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

/** App真实工厂：同一投影、四形态、正常回调和国内固定尺寸，不创建音源或会议。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayViewFactoryTest {
    @Before fun allowOverlay() = ShadowSettings.setCanDrawOverlays(true)

    @Test fun automationBoundsFollowTheMountedFormAndExcludeDisabledControls() {
        val window = OverlayViewFactory().create(RuntimeEnvironment.getApplication(), {}, {}, {}) as OverlayViewFactory.DefaultWindow
        window.show(single("test", true))
        window.render(single("test", true), MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:10", true, 30)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            measure(window.overlay.controller())
            assertTrue(window.controlBounds().getValue("停止会议").width() > 0)
            assertTrue(window.controlBounds().containsKey("收起窗口"))
            window.overlay.collapse()
            shadowOf(Looper.getMainLooper()).idle()
            measure(window.overlay.controller())
            assertTrue(window.controlBounds().containsKey("展开会议窗口"))
            assertFalse(window.controlBounds().containsKey("停止会议"))
            window.overlay.restore()
            window.render(single("test", true), MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:10", false, 0)
            shadowOf(Looper.getMainLooper()).idle()
            measure(window.overlay.controller())
            assertFalse(window.controlBounds().containsKey("停止会议"))
        } finally { window.dismiss() }
        assertTrue(window.controlBounds().isEmpty())
    }

    @Test @Config(sdk = [34]) fun bundledWebpResourcesStillDecodeAsAnimations() {
        val context = RuntimeEnvironment.getApplication()
        // 资源分类调整后仍由平台读取原动画，不能悄悄退成单帧图片。
        for (resource in listOf(R.drawable.czur_overlay_voice, R.drawable.czur_overlay_recording)) {
            val drawable = context.getDrawable(resource)
            assertTrue(context.resources.getResourceEntryName(resource), drawable is AnimatedImageDrawable)
            assertTrue(drawable!!.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        }
    }

    @Test fun lateOldRevisionSurvivesMiniAndHiddenThenUsesOriginalCallbacks() {
        val opens = AtomicInteger()
        val stops = AtomicInteger()
        val window = OverlayViewFactory().create(RuntimeEnvironment.getApplication(),
            { opens.incrementAndGet() }, { fail("单语无交换") }, { stops.incrementAndGet() }) as OverlayViewFactory.DefaultWindow
        val initial = single("old revised", false)
        assertTrue(window.show(initial))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(window.windowToken())
        assertSame(window.overlay.controller().contentView()!!.windowToken, window.windowToken())
        window.render(initial, MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:10", true, 42)
        val controller = window.overlay.controller()
        assertEquals(1800, controller.windowSpec()!!.widthPx)
        assertEquals(222, controller.windowSpec()!!.heightPx)
        measure(controller)
        assertEquals("old revised", cell(controller, "overlay.subtitle").getParts()[0].text().toString())
        assertEquals("old", cell(controller, "overlay.subtitle").getParts()[0].id())
        assertFalse(cell(controller, "overlay.subtitle").getParts()[0].isFinal())
        assertEquals(2, cell(controller, "overlay.subtitle").getParts().size)
        assertTrue(cell(controller, "overlay.subtitle").getParts()[1].isFinal())
        visible(controller, "overlay.stop")
        window.overlay.collapse()
        assertSame(controller.contentView()!!.windowToken, window.windowToken())
        assertEquals(126, controller.windowSpec()!!.widthPx)
        assertEquals(60, controller.windowSpec()!!.heightPx)
        measure(controller)
        visible(controller, "overlay.restore")
        window.overlay.hide()
        assertNull(window.windowToken())
        window.render(single("old final while hidden", true), MeetingSetup.Mode.LIVE_TRANSCRIPT,
            false, "00:20", true, 73)
        assertFalse(window.isShowing())
        window.overlay.show(false)
        window.overlay.restore()
        assertSame(controller.contentView()!!.windowToken, window.windowToken())
        measure(controller)
        assertEquals("old final while hidden", cell(controller, "overlay.subtitle").getParts()[0].text().toString())
        assertTrue(cell(controller, "overlay.subtitle").getParts()[0].isFinal())
        assertEquals(73, controller.contentView()!!.findViewWithTag<CzurVoiceLevelView>("overlay.level").getLevel())
        visible(controller, "overlay.open").performClick()
        visible(controller, "overlay.stop").performClick()
        assertEquals(1, opens.get())
        assertEquals(1, stops.get())
        window.render(initial, MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:20", false, 0)
        assertFalse(visible(controller, "overlay.stop").isEnabled)
        // 原视觉回归以固定尺寸手动layout；attach traversal在这些断言后完成，避免改变其测量时序。
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(window.windowToken())
        assertSame(controller.contentView()!!.windowToken, window.windowToken())
        window.dismiss()
        assertNull(window.windowToken())
        assertEquals(OverlayController.State.RELEASED, controller.state())
    }

    @Test fun normalLargeMiniRestoreAndSwapKeepRoutedIndependentFinal() {
        val swaps = AtomicInteger()
        val window = OverlayViewFactory().create(RuntimeEnvironment.getApplication(), {},
            { swaps.incrementAndGet() }, {}) as OverlayViewFactory.DefaultWindow
        assertTrue(window.show(bilingual(false)))
        window.render(bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION, true, "00:30", true, 55)
        val controller = window.overlay.controller()
        assertEquals(293, controller.windowSpec()!!.heightPx)
        measure(controller)
        assertTrue(cell(controller, "overlay.left").getParts().isEmpty())
        assertEquals("source", cell(controller, "overlay.right").getParts()[0].text().toString())
        assertFalse(cell(controller, "overlay.right").getParts()[0].isFinal())
        assertEquals("译文", cell(controller, "overlay.right-translation").getParts()[0].text().toString())
        assertTrue(cell(controller, "overlay.right-translation").getParts()[0].isFinal())
        visible(controller, "overlay.swap").performClick()
        assertEquals(1, swaps.get())
        visible(controller, "overlay.size").performClick()
        assertEquals(1008, controller.windowSpec()!!.heightPx)
        measure(controller)
        visible(controller, "overlay.stop")
        window.overlay.collapse()
        window.render(bilingual(true), MeetingSetup.Mode.LIVE_TRANSLATION, true, "00:40", true, 64)
        window.overlay.restore()
        assertEquals(DefaultMeetingOverlay.TRANSLATION_LARGE, controller.currentFormId())
        measure(controller)
        assertEquals("source", cell(controller, "overlay.history-left").getParts()[0].text().toString())
        assertFalse(cell(controller, "overlay.history-left").getParts()[0].isFinal())
        assertEquals("译文", cell(controller, "overlay.history-left-translation").getParts()[0].text().toString())
        assertTrue(cell(controller, "overlay.history-left-translation").getParts()[0].isFinal())
        assertNull(controller.contentView()!!.findViewWithTag<View>("overlay.history-right"))
        window.overlay.showNormal()
        measure(controller)
        visible(controller, "overlay.stop")
        window.dismiss()
    }

    @Test fun domesticSimpleModeKeepsSamePairedSideAndOriginalCallbacks() {
        val swaps = AtomicInteger()
        val stops = AtomicInteger()
        val window = OverlayViewFactory().create(RuntimeEnvironment.getApplication(), {},
            { swaps.incrementAndGet() }, { stops.incrementAndGet() }) as OverlayViewFactory.DefaultWindow
        window.show(bilingual(false))
        window.render(bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION, false,
            "已进行 00:01:30 · 剩余 00:20:00", true, 55)
        val controller = window.overlay.controller()
        measure(controller)
        assertEquals(36, cell(controller, "overlay.right").getFixedLineHeightPx())
        assertEquals(0xFFFFDB10.toInt(), cell(controller, "overlay.right-translation").currentTextColor)
        val latest = bilingual(false).rows()[0]
        // 保留长输入和原断言：简洁裁切只能影响可见尾部，不能删除历史或当前句业务内容。
        val olderSource = "earlier source with enough words to wrap across the ordinary window ".repeat(8)
        val olderTranslation = "之前的译文需要覆盖普通窗口的多行排版，保留完整输入并只显示最近内容。".repeat(8)
        val older = MeetingDisplaySnapshot.Row("older", TranscriptEvent("older", 1, 0, 50,
            "en-US", olderSource, true), TranslationEvent("earlier-translation", "older", 1,
            "en-US", "zh-CN", olderTranslation, true), MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE)
        val history = MeetingDisplaySnapshot(true, "zh-CN", "en-US", false, listOf(older, latest), "source")
        window.render(history, MeetingSetup.Mode.LIVE_TRANSLATION, false,
            "已进行 00:01:30 · 剩余 00:20:00", true, 55)
        assertEquals(2, cell(controller, "overlay.right").getParts().size)
        assertTrue(cell(controller, "overlay.right").getParts()[0].isFinal())
        assertFalse(cell(controller, "overlay.right").getParts()[1].isFinal())
        visible(controller, "overlay.simple").performClick()
        assertEquals(164, controller.windowSpec()!!.heightPx)
        measure(controller)
        assertEquals(View.GONE, controller.contentView()!!.findViewWithTag<View>("overlay.level").visibility)
        assertEquals(2, cell(controller, "overlay.right").getMaxVisibleLines())
        assertEquals(2, cell(controller, "overlay.right-translation").getMaxVisibleLines())
        assertEquals(2, cell(controller, "overlay.right").lineCount)
        assertEquals(2, cell(controller, "overlay.right-translation").lineCount)
        visible(controller, "overlay.right")
        visible(controller, "overlay.right-translation")
        val sourceCell = cell(controller, "overlay.right")
        val translatedCell = cell(controller, "overlay.right-translation")
        assertTrue("原文不能越过简洁列底部", sourceCell.bottom <= (sourceCell.parent as View).height)
        assertTrue("译文不能越过简洁列底部", translatedCell.bottom <= (translatedCell.parent as View).height)
        assertEquals(olderSource + "source", sourceCell.getFullText().toString())
        assertEquals(olderTranslation + "译文", translatedCell.getFullText().toString())
        assertEquals("source", cell(controller, "overlay.right").getParts()[1].text().toString())
        visible(controller, "overlay.collapse").performClick()
        assertEquals(1, swaps.get())
        assertEquals(DefaultMeetingOverlay.TRANSLATION_NORMAL, controller.currentFormId())
        visible(controller, "overlay.stop").performClick()
        assertEquals(1, stops.get())
        visible(controller, "overlay.size").performClick()
        assertEquals(DefaultMeetingOverlay.TRANSLATION_LARGE, controller.currentFormId())
        window.overlay.showNormal()
        assertEquals(293, controller.windowSpec()!!.heightPx)
        measure(controller)
        assertEquals(3, cell(controller, "overlay.right").getMaxVisibleLines())
        assertEquals(3, cell(controller, "overlay.right-translation").getMaxVisibleLines())
        assertEquals(3, cell(controller, "overlay.right").lineCount)
        assertEquals(3, cell(controller, "overlay.right-translation").lineCount)
        assertEquals(olderSource + "source", cell(controller, "overlay.right").getFullText().toString())
        assertEquals(olderTranslation + "译文", cell(controller, "overlay.right-translation").getFullText().toString())
        window.dismiss()
    }

    @Test fun backgroundDragKeepsButtonsAndHistoryScrollIndependentAndMiniFixed() {
        val stops = AtomicInteger()
        val window = OverlayViewFactory().create(RuntimeEnvironment.getApplication(), {}, {},
            { stops.incrementAndGet() }) as OverlayViewFactory.DefaultWindow
        window.show(single("visible", true))
        window.render(single("visible", true), MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:10", true, 42)
        val controller = window.overlay.controller()
        measure(controller)
        val content = controller.contentView()!!
        assertTrue(content.keepScreenOn)
        val original = controller.windowSpec()!!
        drag(content, 10f, 5f, 40f, -95f)
        assertEquals(original.xPx + 30, controller.windowSpec()!!.xPx)
        assertEquals(original.yPx - 100, controller.windowSpec()!!.yPx)
        visible(controller, "overlay.stop").performClick()
        assertEquals(1, stops.get())
        window.overlay.collapse()
        measure(controller)
        val mini = controller.windowSpec()!!
        // Mini 的点击仍交给原 restore 控件，移动和取消不能改变其固定位置。
        touch(controller.contentView()!!, MotionEvent.ACTION_DOWN, 5f, 5f)
        touch(controller.contentView()!!, MotionEvent.ACTION_MOVE, 35f, 25f)
        touch(controller.contentView()!!, MotionEvent.ACTION_CANCEL, 35f, 25f)
        assertEquals(mini.xPx, controller.windowSpec()!!.xPx)
        assertEquals(mini.yPx, controller.windowSpec()!!.yPx)
        window.overlay.restore()
        assertEquals(original.xPx + 30, controller.windowSpec()!!.xPx)
        assertEquals(original.yPx - 100, controller.windowSpec()!!.yPx)
        controller.show(DefaultMeetingOverlay.TRANSLATION_LARGE)
        window.render(bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION, false, "00:20", true, 42)
        measure(controller)
        val large = controller.windowSpec()!!
        drag(controller.contentView()!!, 100f, 100f, 140f, 160f)
        assertEquals(large.xPx, controller.windowSpec()!!.xPx)
        assertEquals(large.yPx, controller.windowSpec()!!.yPx)
        window.dismiss()
    }

    @Test fun resizedMiniRestoresSimpleModeSnapshotAndPositionWithoutRevivingHiddenWindow() {
        val app = RuntimeEnvironment.getApplication()
        val window = OverlayViewFactory().create(app, {}, {}, {}) as OverlayViewFactory.DefaultWindow
        window.show(bilingual(false))
        window.render(bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION, false, "00:40", true, 64)
        val controller = window.overlay.controller()
        measure(controller)
        visible(controller, "overlay.simple").performClick()
        measure(controller)
        drag(controller.contentView()!!, 10f, 1f, -20f, -119f)
        window.overlay.collapse()
        val miniContent = controller.contentView()
        resize(app, "w1280dp-h720dp-mdpi")
        assertNotSame(miniContent, controller.contentView())
        assertEquals(DefaultMeetingOverlay.MINI, controller.currentFormId())
        assertEquals(84, controller.windowSpec()!!.widthPx)
        window.overlay.restore()
        measure(controller)
        assertEquals(DefaultMeetingOverlay.TRANSLATION_NORMAL, controller.currentFormId())
        assertEquals(109, controller.windowSpec()!!.heightPx)
        assertEquals(20, controller.windowSpec()!!.xPx)
        assertEquals(View.GONE, controller.contentView()!!.findViewWithTag<View>("overlay.open").visibility)
        assertEquals("source", cell(controller, "overlay.right").getParts()[0].text().toString())
        assertEquals("译文", cell(controller, "overlay.right-translation").getParts()[0].text().toString())
        assertEquals(16f, cell(controller, "overlay.right").textSize, .01f)
        assertEquals(64, controller.contentView()!!.findViewWithTag<CzurVoiceLevelView>("overlay.level").getLevel())
        assertEquals("00 : 40", controller.contentView()!!.findViewWithTag<TextView>("overlay.open").text.toString())
        val restored = controller.windowSpec()!!
        assertTrue(restored.yPx in 0..(720 - restored.heightPx))
        window.overlay.hide()
        resize(app, "w960dp-h540dp-mdpi")
        assertFalse(window.isShowing())
        window.overlay.show(true)
        assertEquals(900, controller.windowSpec()!!.widthPx)
        assertEquals(DefaultMeetingOverlay.TRANSLATION_NORMAL, controller.currentFormId())
        window.dismiss()
        resize(app, "w1920dp-h1080dp-mdpi")
        assertEquals(OverlayController.State.RELEASED, controller.state())
        assertFalse(window.isShowing())
    }

    companion object {
        private fun resize(app: Application, qualifiers: String) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            app.onConfigurationChanged(Configuration(app.resources.configuration))
        }

        private fun drag(view: View, fromX: Float, fromY: Float, toX: Float, toY: Float) {
            touch(view, MotionEvent.ACTION_DOWN, fromX, fromY)
            touch(view, MotionEvent.ACTION_MOVE, toX, toY)
            touch(view, MotionEvent.ACTION_UP, toX, toY)
        }

        private fun touch(view: View, action: Int, x: Float, y: Float) {
            MotionEvent.obtain(0, 10, action, x, y, 0).also { event ->
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        @JvmStatic fun single(oldText: String, complete: Boolean) = MeetingDisplaySnapshot(false, "zh-CN", "en-US", false,
            listOf(MeetingDisplaySnapshot.Row("old", TranscriptEvent("old", 2, 0, 100,
                "zh-CN", oldText, complete), null, MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE),
                MeetingDisplaySnapshot.Row("new", TranscriptEvent("new", 1, 100, 200,
                    "zh-CN", "newer", true), null, MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE)), "old")

        @JvmStatic fun bilingual(swapped: Boolean) = MeetingDisplaySnapshot(true, "zh-CN", "en-US", swapped,
            listOf(MeetingDisplaySnapshot.Row("source", TranscriptEvent("source", 1, 0, 100,
                "en-US", "source", false), TranslationEvent("translated", "source", 1,
                "en-US", "zh-CN", "译文", true), MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE)), "source")

        @JvmStatic fun cell(controller: OverlayController, tag: String): SubtitleTextView =
            controller.contentView()!!.findViewWithTag(tag)

        @JvmStatic fun measure(controller: OverlayController) {
            val root = controller.contentView()!!.parent as View
            val spec = controller.windowSpec()!!
            root.measure(View.MeasureSpec.makeMeasureSpec(spec.widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(spec.heightPx, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, spec.widthPx, spec.heightPx)
        }

        @JvmStatic fun visible(controller: OverlayController, tag: String): View {
            val panel = controller.contentView() as ViewGroup
            val target = panel.findViewWithTag<View>(tag)
            assertNotNull(target)
            assertTrue(tag, target.width > 0 && target.height > 0)
            val rect = Rect(0, 0, target.width, target.height)
            panel.offsetDescendantRectToMyCoords(target, rect)
            assertTrue("$tag outside fixed height: $rect", rect.top >= 0 && rect.bottom <= panel.height)
            assertTrue("$tag outside fixed width: $rect", rect.left >= 0 && rect.right <= panel.width)
            return target
        }
    }
}
