package com.czur.aimeeting.reference

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.czur.aimeeting.client.*
import com.czur.aimeeting.client.display.*
import com.czur.aimeeting.client.result.*
import com.czur.aimeeting.client.server.DeploymentOptions
import com.czur.aimeeting.model.*
import com.czur.aimeeting.reference.meeting.*
import com.czur.aimeeting.reference.ui.OverlayViewFactory
import com.czur.aimeeting.ui.SubtitleTextView
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowSettings

/** 实际原生布局与正常接线；不建会、不联网、不启动音源或操作实体设备。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceMeetingPageTest {
    @Test fun idleServiceClearsItsPreviousProgressWithoutOverwritingNewIdentityMessage() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MeetingService::class.java), service.onBind(Intent()))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            idleMain()
            val status = (field(activity.get(), "page") as MeetingPageViews).status
            field(service, "status", "本场结果已就绪，正在执行结果动作…")
            invoke(service, "publish")
            assertTrue(status.text.contains("正在执行"))
            field(service, "status", "")
            invoke(service, "publish")
            assertEquals("", status.text.toString())
            field(service, "status", "本场结果已就绪")
            invoke(service, "publish")
            status.text = "请重新登录"
            field(service, "status", "")
            invoke(service, "publish")
            assertEquals("请重新登录", status.text.toString())
        } finally { activity.pause().stop().destroy(); host.destroy() }
    }

    @Test fun defaultRunningFlowLeavesSetupOnlyAfterTheRealOverlayIsMounted() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        field(service, "active", true)
        field(service, "canStop", true)
        field(service, "busy", true)
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MeetingService::class.java), service.onBind(Intent()))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            idleMain()
            assertTrue("正常入口交接后退出设置Activity", activity.get().isFinishing)
            assertTrue((field(service, "overlay") as MeetingOverlay).isShowing())
            assertTrue(field(service, "active") as Boolean)
            assertEquals(988, activity.get().window.attributes.width)
            assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK,
                app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0).launchMode)
        } finally { activity.pause().stop().destroy(); host.destroy() }
    }

    @Test fun pausedDialogCannotStealTheForegroundObserverWhenItsBindArrivesLate() {
        val host = Robolectric.buildService(MeetingService::class.java).create()
        val service = host.get()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MeetingService::class.java), service.onBind(Intent()))
        val previous = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        idleMain()
        previous.pause() // 透明Activity仍可能处于STARTED；不依赖onStop解除观察。
        val current = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            idleMain()
            val late = field(previous.get(), "serviceConnection") as ServiceConnection
            late.onServiceConnected(ComponentName(app, MeetingService::class.java), service.onBind(Intent()))
            field(service, "status", "本场已停止")
            invoke(service, "publish")
            assertSame(field(current.get(), "observer"), field(service, "observer"))
            assertEquals("本场已停止", (field(current.get(), "page") as MeetingPageViews).status.text.toString())
            assertNotEquals("本场已停止", (field(previous.get(), "page") as MeetingPageViews).status.text.toString())
        } finally { current.pause().stop().destroy(); previous.stop().destroy(); host.destroy() }
    }

    @Test fun visibleMeetingPageProvidesItsMountedWindowForCaptureExclusion() {
        val serviceHost = Robolectric.buildService(MeetingService::class.java).create()
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            ComponentName(RuntimeEnvironment.getApplication(), MeetingService::class.java),
            serviceHost.get().onBind(Intent()))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val observer = MainActivity::class.java.getDeclaredField("observer").run {
                isAccessible = true
                get(activity) as MeetingService.Observer
            }
            assertNotNull(activity.window.decorView.windowToken)
            assertArrayEquals(arrayOf(activity.window.decorView.windowToken), observer.captureWindowTokens())
            val defaultDim = activity.window.attributes.dimAmount
            render(activity, display(false, false, true), 1, 0, capturingPresentation = true)
            assertEquals(0f, activity.window.attributes.dimAmount, 0f)
            render(activity, display(false, false, true), 2, 0, capturingPresentation = true, active = false)
            assertEquals("停止末帧期间不得恢复背景暗化", 0f, activity.window.attributes.dimAmount, 0f)
            render(activity, display(false, false, true), 3, 0, active = false)
            assertEquals(defaultDim, activity.window.attributes.dimAmount, 0f)
        } finally { controller.pause().stop().destroy(); serviceHost.destroy() }
    }

    @After fun resetAssembly() {
        MeetingAppAssembly.configureBackend("")
        MeetingAppAssembly.configureResultWeb("")
        MeetingAppAssembly.clearReferenceSession()
        MeetingAppAssembly.registerPageFactory(MeetingPageViews.DefaultFactory())
        MeetingAppAssembly.registerOverlayFactory(OverlayViewFactory())
        // 清理同进程交接，不执行其他测试留下的平台操作。
        val cleanup = Robolectric.buildService(MeetingService::class.java).create()
        while (cleanup.get().takeUiOperation() != null) { }
        cleanup.destroy()
    }

    // 同一会前用例使用1.5密度，捕获shape逐角／selector遗漏换算的实际视觉差异。
    @Config(qualifiers = "w1280dp-h720dp-hdpi")
    @Test fun centeredCardHasSingleLanguageAndFixedStartOutsideScroll() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val page = MeetingPageViews.DefaultFactory().create(activity)
        measure(page.root)
        val card = bounds(page.root, page.setupCard)
        assertEquals(988, card.width())
        assertEquals(856, card.height())
        assertEquals(960, card.centerX())
        assertEquals(540, card.centerY())
        assertEquals(48f, page.setupTitle.textSize, 0f)
        assertEquals(30f, page.root.findViewById<TextView>(R.id.setup_subtitle).textSize, 0f)
        assertEquals(View.GONE, page.targetContainer.visibility)
        assertEquals(View.GONE, page.swapLanguages.visibility)
        assertFixed(page.start)
        val start = bounds(page.root, page.start)
        assertEquals(180, start.width())
        assertEquals(50, start.height())
        val header = page.root.findViewById<View>(R.id.setup_header).background as GradientDrawable
        assertArrayEquals(floatArrayOf(40f, 40f, 40f, 40f, 0f, 0f, 0f, 0f), header.cornerRadii, 0f)
        val buttons = page.start.background.constantState as DrawableContainer.DrawableContainerState
        assertEquals(3, buttons.childCount)
        // Android inflate先取整像素（hdpi的15dp成为23px），允许不足半像素舍入。
        for (index in 0 until buttons.childCount) {
            assertEquals(15f, (buttons.getChild(index) as GradientDrawable).cornerRadius, 0.5f)
        }
        assertTrue(start.bottom <= card.bottom && start.top >= card.top)
        assertEquals(0, activity.resources.getIdentifier("backend_url", "id", activity.packageName))
        assertEquals(0, activity.resources.getIdentifier("web_url", "id", activity.packageName))
        assertEquals(0, activity.resources.getIdentifier("settings_button", "id", activity.packageName))
        // 浮动窗口只缩小根布局，卡片／字号仍按显示尺寸映射，不能被重复缩放。
        repeat(3) {
            page.root.measure(View.MeasureSpec.makeMeasureSpec(988, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(856, View.MeasureSpec.EXACTLY))
            page.root.layout(0, 0, 988, 856)
        }
        assertEquals(988, page.setupCard.width)
        assertEquals(856, page.setupCard.height)
        assertEquals(48f, page.setupTitle.textSize, 0f)
        val status = bounds(page.root, page.status)
        assertTrue(status.top >= 0 && status.bottom <= 856)
        assertSame(page.setupCard, page.status.parent)
        assertEquals(View.VISIBLE, page.close.visibility)
        assertFixed(page.start)
        page.showSetupMode(true)
        assertEquals(View.VISIBLE, page.targetContainer.visibility)
        assertEquals(View.VISIBLE, page.swapLanguages.visibility)
        assertEquals(View.GONE, page.presentation.visibility)
        assertEquals("AI双语互译", page.setupTitle.text.toString())
        assertEquals("（实时将两种语言进行互译）", page.root.findViewById<TextView>(R.id.setup_subtitle).text.toString())
    }

    @Test fun missingTranslationAndSummaryHideOnlyThoseEntrancesAndKeepCaptions() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val page = MeetingPageViews.DefaultFactory().create(activity)

        page.showCapabilityOptions(summaryAvailable = false, translationAvailable = false)
        page.showSetupMode(translation = false, summaryAvailable = false)

        assertEquals(View.GONE, page.translationMode.visibility)
        assertEquals(View.GONE, page.summary.visibility)
        assertEquals(View.VISIBLE, page.transcriptMode.visibility)
        assertTrue(page.transcriptMode.isChecked)
        assertEquals("AI字幕", page.setupTitle.text.toString())
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, page.transcriptMode.layoutParams.width)

        page.showCapabilityOptions(summaryAvailable = true, translationAvailable = true)
        page.showSetupMode(translation = false, summaryAvailable = true)
        assertEquals(View.VISIBLE, page.translationMode.visibility)
        assertEquals(View.VISIBLE, page.summary.visibility)
        assertEquals("AI会议纪要", page.setupTitle.text.toString())
        assertEquals("AI双语互译", page.translationMode.text.toString())
        assertNotEquals(ViewGroup.LayoutParams.MATCH_PARENT, page.transcriptMode.layoutParams.width)
        page.translationMode.performClick()
        assertTrue(page.translationMode.isChecked)
        page.showSetupMode(translation = true, summaryAvailable = true)
        assertTrue(page.targetContainer.visibility == View.VISIBLE)
        page.showCapabilityOptions(summaryAvailable = true, translationAvailable = false)
        page.showSetupMode(translation = false, summaryAvailable = true)
        assertEquals(View.GONE, page.translationMode.visibility)
        assertFalse(page.targetLanguage.isEnabled)
        assertTrue(page.transcriptMode.isChecked)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, page.transcriptMode.layoutParams.width)

        page.showCapabilityOptions(summaryAvailable = true, translationAvailable = true, transcriptAvailable = false)
        page.showSetupMode(translation = false, summaryAvailable = true, transcriptAvailable = false)
        assertEquals(View.GONE, page.modes.visibility)
        assertEquals(View.GONE, page.transcriptMode.visibility)
        assertEquals(View.GONE, page.translationMode.visibility)
        assertEquals(View.GONE, page.summary.visibility)
        assertEquals("实时字幕不可用", page.setupTitle.text.toString())
        assertEquals("当前部署未提供实时字幕能力", page.root.findViewById<TextView>(R.id.language_hint).text.toString())
    }

    @Test fun pendingStopOffersConfirmationWithoutRestoringTheMeetingPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val page = MeetingPageViews.DefaultFactory().create(activity)
        val stops = AtomicInteger()
        page.stopConfirmation.setOnClickListener { stops.incrementAndGet() }

        page.showMeeting(true)
        assertTrue("会中页面保持亮屏", page.root.keepScreenOn)
        page.showMeeting(false)
        assertFalse("退会中即解除保亮屏，不延续到后台收尾", page.root.keepScreenOn)
        page.showStopConfirmation(true)
        measure(page.root)

        assertEquals(View.VISIBLE, page.setupCard.visibility)
        assertEquals(View.GONE, page.meetingPanel.visibility)
        assertEquals(View.VISIBLE, page.stopConfirmation.visibility)
        assertEquals(View.GONE, page.start.visibility)
        assertEquals("重新确认停止", page.stopConfirmation.text.toString())
        assertFixed(page.stopConfirmation)
        assertTrue(bounds(page.root, page.stopConfirmation).bottom <= bounds(page.root, page.setupCard).bottom)
        page.stopConfirmation.performClick()
        assertEquals(1, stops.get())

        page.showStopConfirmation(false)
        assertEquals(View.GONE, page.stopConfirmation.visibility)
        assertEquals(View.VISIBLE, page.start.visibility)
        assertEquals(View.GONE, page.meetingPanel.visibility)
    }

    @Test fun accountEntryRemainsWithoutAConnectionConfigurationRoute() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        // 装配仍可由伙伴开发者／测试替换；终端用户没有填写部署地址的页面。
        MeetingAppAssembly.configureBackend("https://backend.example.test")
        activity.findViewById<View>(R.id.account_button).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals("账号登录", dialog.findViewById<TextView>(R.id.login_title).text.toString())
        dialog.dismiss()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(0, activity.resources.getIdentifier("settings_button", "id", activity.packageName))
        assertFalse(activity.findViewById<View>(R.id.start_meeting).isEnabled)
    }

    @Test fun subtitleTranslationAndFixedStopUseSameProjectionAndServiceCallbacks() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val page = field(activity, "page") as MeetingPageViews
        val single = display(false, false, false)
        render(activity, single, 1, 47)
        measure(page.root)
        assertEquals(View.VISIBLE, page.meetingPanel.visibility)
        assertEquals("AI字幕", page.meetingTitle.text.toString())
        assertEquals(47, page.level.progress)
        assertEquals(47, page.voiceLevel.getLevel())
        assertEquals(View.GONE, page.levelText.visibility)
        assertEquals(View.GONE, page.capabilities.visibility)
        val subtitle = page.transcriptList.getChildAt(1) as SubtitleTextView
        assertEquals("source", subtitle.getParts()[0].text().toString())
        assertFalse(subtitle.getParts()[0].isFinal())
        render(activity, display(false, false, true), 2, 48)
        assertEquals(2, page.transcriptList.childCount)
        assertTrue(subtitle.getParts()[0].isFinal())
        render(activity, display(true, false, false), 3, 55)
        measure(page.root)
        assertEquals("source", (page.leftList.getChildAt(0) as SubtitleTextView).getParts()[0].text().toString())
        assertEquals("译文", (page.leftList.getChildAt(1) as SubtitleTextView).getParts()[0].text().toString())
        assertEquals(0, page.rightList.childCount)
        assertFalse((page.leftList.getChildAt(0) as SubtitleTextView).getParts()[0].isFinal())
        assertTrue((page.leftList.getChildAt(1) as SubtitleTextView).getParts()[0].isFinal())
        render(activity, display(true, true, false), 4, 55)
        assertEquals(0, page.leftList.childCount)
        assertEquals("source", (page.rightList.getChildAt(0) as SubtitleTextView).getParts()[0].text().toString())
        assertEquals("译文", (page.rightList.getChildAt(1) as SubtitleTextView).getParts()[0].text().toString())
        assertFixed(page.stop)
        assertFixed(page.swapColumns)
        assertFixed(page.overlay)
        assertTrue(bounds(page.root, page.stop).bottom <= 1080)
        assertFalse(page.account.isEnabled)
        val controller = Robolectric.buildService(MeetingService::class.java).create()
        val service = controller.get()
        val stops = AtomicInteger()
        val swaps = AtomicInteger()
        val session = Proxy.newProxyInstance(MeetingSession::class.java.classLoader,
            arrayOf(MeetingSession::class.java)) { _, method, _ ->
            if (method.name == "requestStop") stops.incrementAndGet()
            if (method.name == "swapDisplayColumns") swaps.incrementAndGet()
            null
        } as MeetingSession
        field(service, "active", true)
        field(service, "canStop", true)
        field(service, "display", display(true, true, false))
        field(service, "session", session)
        field(activity, "meetingService", service)
        page.swapColumns.performClick()
        page.stop.performClick()
        assertEquals(1, swaps.get())
        assertEquals(1, stops.get())
        assertFalse(field(service, "canStop") as Boolean)
        assertNull(field(service, "audioLevel"))
        controller.destroy()
    }

    @Test fun defaultWebSettingsFreezeCurrentUrlAtStartThenKeepSameMeeting() {
        val app = RuntimeEnvironment.getApplication()
        MeetingAppAssembly.configureResultWeb("https://before.example.test/")
        val settings = WebResultActionPlugin().createSettings(app)
        assertEquals(R.id.view_previous_web_result, settings.view()!!.id)
        MeetingAppAssembly.configureResultWeb("https://start.example.test/index.html?x=1#part")
        val prepared = settings.freeze().get().prepare(MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT,
            "zh-CN", "en-US", false, ResultOptions.action("web"), "", emptyList(), false).toOptions())
        assertTrue(prepared.allowed())
        MeetingAppAssembly.configureResultWeb("https://after.example.test/")
        val ready = MeetingResult("mtg_same", "本场", 555, MeetingResult.Status.READY, setOf("transcript.final"), null)
        val unusedAccess = object : ResultActionAccess {
            override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) { fail() }
            override fun sendEmail(emails: List<String>, resultWebUrl: String, callback: MeetingClient.Callback<Void?>) { fail() }
        }
        prepared.execution()!!.execute(ResultActionContext(ready, unusedAccess, emptySet(), { it.run() }, { it.run() }),
            object : MeetingClient.Callback<ResultActionOutcome> {
                override fun onSuccess(result: ResultActionOutcome) { assertEquals(ResultActionOutcome.Status.SUCCEEDED, result.status()) }
                override fun onError(message: String) { fail(message) }
            })
        val opened = shadowOf(app).nextStartedActivity
        assertEquals("https://start.example.test/index.html?x=1&meetingId=mtg_same&genTime=555#part",
            opened.getStringExtra(ResultActivity.EXTRA_RESULT_URL))
    }

    @Test fun bilingualRevisionsReuseAllHistoryViewsAndUpdateOnlyChangedSourceOrTranslation() {
        val host = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = host.get()
        val page = field(activity, "page") as MeetingPageViews
        fun snapshot(text: String, sourceFinal: Boolean, translationFinal: Boolean, swapped: Boolean = false): MeetingDisplaySnapshot {
            val history = (0 until 80).map { index -> MeetingDisplaySnapshot.Row("history-$index",
                TranscriptEvent("history-$index", 1, index.toLong(), index + 1L, "zh-CN", "历史$index", true),
                TranslationEvent("translation-$index", "history-$index", 1, "zh-CN", "ja-JP", "履歴$index", true),
                MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE) }
            val current = MeetingDisplaySnapshot.Row("current",
                TranscriptEvent("current", 2, 100, 200, "zh-CN", text, sourceFinal),
                TranslationEvent("current-translation", "current", 1, "zh-CN", "ja-JP", "現在", translationFinal),
                MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE)
            return MeetingDisplaySnapshot(true, "zh-CN", "ja-JP", swapped, history + current, "current")
        }
        try {
            render(activity, snapshot("临时", false, false), 1, 40)
            val historicalViews = (0 until page.leftList.childCount).map { page.leftList.getChildAt(it) as SubtitleTextView }
            val historicalParts = historicalViews.map { it.getParts()[0] }
            val source = page.rightList.getChildAt(0) as SubtitleTextView
            val translated = page.rightList.getChildAt(1) as SubtitleTextView
            val originalTranslationPart = translated.getParts()[0]
            for (revision in 2..12) render(activity, snapshot("临时$revision", false, false), revision, revision)
            for ((index, view) in historicalViews.withIndex()) {
                assertSame("历史View不重复创建", view, page.leftList.getChildAt(index))
                assertSame("未改变的历史不重复setParts", historicalParts[index], view.getParts()[0])
            }
            assertSame(source, page.rightList.getChildAt(0))
            assertSame(translated, page.rightList.getChildAt(1))
            assertSame("只有原文更新时译文不重复排版", originalTranslationPart, translated.getParts()[0])
            assertFalse(source.getParts()[0].isFinal())
            render(activity, snapshot("原文完成", true, false), 13, 65)
            assertTrue(source.getParts()[0].isFinal()); assertFalse(translated.getParts()[0].isFinal())
            val finalSourcePart = source.getParts()[0]
            render(activity, snapshot("原文完成", true, true, true), 14, 65)
            assertSame(source, page.leftList.getChildAt(0)); assertSame(translated, page.leftList.getChildAt(1))
            assertSame(finalSourcePart, source.getParts()[0]); assertTrue(translated.getParts()[0].isFinal())
            assertSame(historicalViews[0], page.rightList.getChildAt(0))
            // 同一个显示版本的电平推送不重画字幕。
            val finalTranslationPart = translated.getParts()[0]
            render(activity, snapshot("原文完成", true, true, true), 14, 17)
            assertSame(finalTranslationPart, translated.getParts()[0]); assertEquals(17, page.voiceLevel.getLevel())
        } finally { host.destroy() }
    }

    @Test fun singleRevisionsKeepUnchangedPartsAndEmptyProjectionClearsOnlyVisibleRows() {
        val host = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = host.get()
        val page = field(activity, "page") as MeetingPageViews
        try {
            render(activity, display(false, false, false), 1, 40)
            val row = page.transcriptList.getChildAt(1) as SubtitleTextView
            val part = row.getParts()[0]
            render(activity, display(false, false, false), 2, 45)
            assertSame(row, page.transcriptList.getChildAt(1)); assertSame(part, row.getParts()[0])
            render(activity, display(false, false, true), 3, 50)
            assertSame(row, page.transcriptList.getChildAt(1)); assertTrue(row.getParts()[0].isFinal())
            val empty = MeetingDisplaySnapshot(false, "zh-CN", "en-US", false, emptyList(), null)
            render(activity, empty, 4, 0)
            assertEquals(1, page.transcriptList.childCount); assertEquals(View.VISIBLE, (field(activity, "transcriptView") as View).visibility)
            assertEquals("source", row.getParts()[0].text().toString())
            render(activity, display(true, false, false), 5, 20)
            assertEquals(2, page.leftList.childCount)
            render(activity, MeetingDisplaySnapshot(true, "zh-CN", "en-US", false, emptyList(), null), 6, 0)
            assertEquals(0, page.leftList.childCount); assertEquals(0, page.rightList.childCount)
            render(activity, display(true, false, true), 7, 20)
            assertEquals("source", (page.leftList.getChildAt(0) as SubtitleTextView).getParts()[0].text().toString())
            assertTrue((page.leftList.getChildAt(0) as SubtitleTextView).getParts()[0].isFinal())
        } finally { host.destroy() }
    }

    @Test fun necessaryCaptureNoticesRenderThroughRealServiceStateWithoutShowingDiagnostics() {
        val serviceHost = Robolectric.buildService(MeetingService::class.java).create()
        val service = serviceHost.get()
        val app = RuntimeEnvironment.getApplication()
        // 真实可见生命周期会执行bindService；为Robolectric提供本场实际LocalBinder。
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MeetingService::class.java), service.onBind(Intent()))
        val activityHost = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val activity = activityHost.get()
        val page = field(activity, "page") as MeetingPageViews
        try {
            field(service, "active", true)
            invoke(service, "publish")
            assertEquals(View.GONE, page.presentationNotice.visibility)
            val compatibility = "当前系统仅支持整屏截图，演示图片会包含悬浮窗"
            field(service, "presentationNotice", compatibility)
            invoke(service, "publish")
            measure(page.root)
            assertEquals(View.VISIBLE, page.presentationNotice.visibility)
            assertEquals(compatibility, page.presentationNotice.text.toString())
            assertTrue(page.presentationNotice.isShown)
            // SDK阶段更新只修改status，真实publish→Activity渲染应保留专用消息。
            field(service, "status", "会议进行中 · savedEventCount=42")
            invoke(service, "publish")
            assertEquals(compatibility, page.presentationNotice.text.toString())
            assertEquals(View.GONE, page.status.visibility)
            assertEquals(View.GONE, page.capabilities.visibility)
            invoke(service, "presentationFailed", String::class.java, "fixture底层采集失败诊断")
            assertEquals("演示截图采集失败；字幕会议继续", page.presentationNotice.text.toString())
            assertTrue(page.presentationNotice.isShown)
            invoke(service, "onPresentationStopped")
            assertEquals("系统已结束屏幕共享，字幕会议继续", page.presentationNotice.text.toString())
            assertTrue(page.presentationNotice.isShown)
            field(service, "active", false)
            invoke(service, "publish")
            assertEquals(View.GONE, page.presentationNotice.visibility)
            assertEquals(View.VISIBLE, page.status.visibility)
        } finally {
            activityHost.pause().stop().destroy()
            serviceHost.destroy()
        }
    }

    @Test fun backgroundReadyWebSurvivesServiceDestroyAndVisibleActivityConsumesOnce() {
        ResultUiFixture(false).use { fixture ->
            fixture.ready()
            idleMain()
            assertEquals("后台READY需要唤起唯一可见消费者", MainActivity::class.java.name,
                shadowOf(fixture.old).nextStartedActivity.component!!.className)
            assertEquals(0, fixture.successes.get())
            fixture.destroyOld()
            fixture.returnToVisibleActivity()
            idleMain()
            fixture.assertOpenedOnce()
            fixture.activity!!.pause().resume()
            idleMain()
            fixture.assertNoFurtherOpen()
            // 完成通知也是普通Runnable，旧宿主关闭不能关掉它的Executor。
            val ordinary = AtomicInteger()
            invoke(fixture.old, "queueUiOperation", Runnable::class.java, Runnable { ordinary.incrementAndGet() })
            idleMain()
            assertEquals(1, ordinary.get())
            assertEquals(fixture.oldStatus, field(fixture.old, "status"))
            assertNull(field(fixture.old, "actionOutcome"))
            assertEquals(1, fixture.closes.get())
            assertEquals(1, fixture.accesses.get())
            assertEquals(0, fixture.windowCreates.get())
        }
    }

    @Test fun latePreparedWebFromDestroyedServiceNotifiesNewVisibleConsumer() {
        ResultUiFixture(false, true).use { fixture ->
            fixture.ready()
            idleMain() // 真实Web已准备UiOperation，fixture仅延迟向原Executor交接。
            fixture.destroyOld()
            fixture.returnToVisibleActivity()
            assertEquals(0, fixture.successes.get())
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            fixture.preparedUiHandoff!!.run()
            idleMain() // 必须通知当前可见新宿主，而非已销毁Service。
            fixture.assertOpenedOnce()
            assertEquals(fixture.oldStatus, field(fixture.old, "status"))
            assertEquals(1, fixture.closes.get())
            assertEquals(1, fixture.accesses.get())
            fixture.activity!!.pause().resume()
            idleMain()
            fixture.assertNoFurtherOpen()
        }
    }

    @Test fun visibleWebFailureKeepsOriginalSessionForExplicitRetryThenReleasesOnce() {
        ResultUiFixture(true).use { fixture ->
            ShadowToast.reset()
            fixture.returnToVisibleActivity(fixture.old)
            fixture.ready()
            idleMain()
            assertEquals(1, fixture.errors.get())
            assertEquals(0, fixture.successes.get())
            assertEquals(ResultActionOutcome.Status.FAILED, (field(fixture.old, "actionOutcome") as ResultActionOutcome).status())
            assertNotNull(field(fixture.old, "session"))
            assertEquals(0, fixture.closes.get())
            assertEquals("READY和首次打开失败不提示结果已可查看", 0, ShadowToast.shownToastCount())
            fixture.old.retryResultAction()
            idleMain()
            fixture.assertOpenedOnce()
            assertEquals(2, fixture.accesses.get())
            assertNull(field(fixture.old, "session"))
            assertEquals(1, fixture.closes.get())
            assertEquals("会议已结束，可查看本场结果", ShadowToast.getTextOfLatestToast())
            assertEquals(1, ShadowToast.shownToastCount())
            fixture.old.retryResultAction()
            idleMain()
            fixture.assertNoFurtherOpen()
            assertEquals("同场结果提示只出现一次", 1, ShadowToast.shownToastCount())
        }
    }

    /** fixture只提供READY／窄协议句柄，执行真实Web闭包、Registry和Service，不访问网络。 */
    private class ResultUiFixture(failFirstOpen: Boolean, delayUiHandoff: Boolean = false) : AutoCloseable {
        val successes = AtomicInteger()
        val errors = AtomicInteger()
        val closes = AtomicInteger()
        val accesses = AtomicInteger()
        val windowCreates = AtomicInteger()
        val oldController = Robolectric.buildService(MeetingService::class.java).create()
        val old = oldController.get()
        private var next: ServiceController<MeetingService>? = null
        var activity: ActivityController<MainActivity>? = null
        private var oldDestroyed = false
        var oldStatus: Any? = null
        var preparedUiHandoff: Runnable? = null

        init {
            val app = RuntimeEnvironment.getApplication()
            MeetingAppAssembly.configureResultWeb(WEB)
            val setup = MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US", false,
                ResultOptions.action("web"), "", emptyList(), false)
            var web: ResultActionPlugin = WebResultActionPlugin().createSettings(app).freeze().get()
            if (failFirstOpen) {
                val attempts = AtomicInteger()
                web = WebResultActionPlugin(WEB) { url ->
                    if (attempts.incrementAndGet() == 1) throw IllegalStateException("fixture首次打开失败")
                    app.startActivity(Intent(app, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_RESULT_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            val frozen = MeetingAppAssembly.prepare(setup,
                MeetingDiscovery(DeploymentOptions(emptySet(), 10_000L, false), emptySet()), web).meeting()
            assertNotNull(frozen)
            val original = frozen!!.action()!!
            val counted = ResultActionRegistry.PreparedAction(original.id(), original.requiredArtifactTypes(),
                original.requiredServiceCapabilities()) { context, callback ->
                val executionContext = if (delayUiHandoff) ResultActionContext(context.result(), context.access(),
                    context.serviceCapabilities(), context.workExecutor(), Executor { command ->
                        if (command is AppResultActionPlugin.UiOperation) {
                            preparedUiHandoff = Runnable { context.uiExecutor().execute(command) }
                        } else context.uiExecutor().execute(command)
                    }) else context
                original.execution().execute(executionContext, object : MeetingClient.Callback<ResultActionOutcome> {
                    override fun onSuccess(result: ResultActionOutcome) { successes.incrementAndGet(); callback.onSuccess(result) }
                    override fun onError(message: String) { errors.incrementAndGet(); callback.onError(message) }
                })
            }
            field(old, "preparedMeeting", MeetingAppAssembly.PreparedMeeting(setup, counted, frozen.serviceCapabilities()))
            field(old, "readyResult", MeetingResult("mtg_same", "fixture", 555, MeetingResult.Status.READY, setOf("transcript.final"), null))
            val unused = object : ResultActionAccess {
                override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) { fail("Web不读正文") }
                override fun sendEmail(emails: List<String>, resultWebUrl: String, callback: MeetingClient.Callback<Void?>) { fail("Web不发邮件") }
            }
            field(old, "session", Proxy.newProxyInstance(MeetingSession::class.java.classLoader,
                arrayOf(MeetingSession::class.java)) { _, method, _ ->
                assertEquals("resultActionAccess", method.name)
                assertEquals("关闭后不得再次访问Session", 0, closes.get())
                accesses.incrementAndGet()
                unused
            })
            field(old, "client", Proxy.newProxyInstance(MeetingClient::class.java.classLoader,
                arrayOf(MeetingClient::class.java)) { _, method, _ ->
                assertEquals("close", method.name)
                closes.incrementAndGet()
                null
            })
            val defaultWindow = OverlayViewFactory()
            MeetingAppAssembly.registerOverlayFactory { context, open, swap, stop ->
                windowCreates.incrementAndGet()
                defaultWindow.create(context, open, swap, stop)
            }
            MeetingAppAssembly.configureResultWeb("https://next.example.test/")
        }

        fun ready() = invoke(old, "resultReady")
        fun destroyOld() {
            oldStatus = field(old, "status")
            oldController.destroy()
            oldDestroyed = true
            assertNull(field(old, "session"))
            assertTrue((field(old, "platformWork") as ExecutorService).isShutdown)
            assertFalse((field(old, "overlay") as MeetingOverlay).isShowing())
        }
        fun returnToVisibleActivity() {
            next = Robolectric.buildService(MeetingService::class.java).create()
            returnToVisibleActivity(next!!.get())
        }
        fun returnToVisibleActivity(service: MeetingService) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            activity = controller
            // 使用真实LocalBinder／ServiceConnection和onResume，不直接poll或run UI操作。
            (field(controller.get(), "serviceConnection") as ServiceConnection).onServiceConnected(
                ComponentName(RuntimeEnvironment.getApplication(), MeetingService::class.java), service.onBind(Intent()))
            controller.resume()
        }
        fun assertOpenedOnce() {
            val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertNotNull("旧Service销毁后必须恢复本场实际Web操作", opened)
            assertEquals(ResultActivity::class.java.name, opened.component!!.className)
            assertEquals("https://frozen.example.test/index.html?x=1&meetingId=mtg_same&genTime=555#part",
                opened.getStringExtra(ResultActivity.EXTRA_RESULT_URL))
            assertEquals(1, successes.get())
            assertNoFurtherOpen()
        }
        fun assertNoFurtherOpen() {
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(1, successes.get())
        }
        override fun close() {
            activity?.pause()?.destroy()
            next?.destroy()
            if (!oldDestroyed) oldController.destroy()
        }
        companion object { const val WEB = "https://frozen.example.test/index.html?x=1#part" }
    }

    @Test fun nativeWebBackRetainsOriginalUrl() {
        val url = "https://result.example.test/?meetingId=mtg_same&genTime=555"
        val result = Robolectric.buildActivity(ResultActivity::class.java,
            Intent().putExtra(ResultActivity.EXTRA_RESULT_URL, url)).create().get()
        assertEquals(url, shadowOf(result.findViewById<WebView>(R.id.result_web)).lastLoadedUrl)
        result.findViewById<View>(R.id.result_back).performClick()
        assertTrue(result.isFinishing)
    }

    @Test fun domesticBlueReminderRendersExistingNoticeAndConfirmsWithoutChangingClock() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val confirmations = AtomicInteger()
        val popup = MeetingReminderPopup(activity, false) { confirmations.incrementAndGet() }
        val screen = FrameLayout(activity)
        screen.addView(popup.view(), FrameLayout.LayoutParams(-1, -1))
        popup.render(MeetingReminderPresentation.Notice(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
            "长时间无转写", "会议将在一分钟后自动结束"))
        measure(screen)
        val card = screen.findViewById<View>(R.id.reminder_card)
        assertEquals(500, card.width)
        assertEquals(360, card.height)
        val title = screen.findViewById<TextView>(R.id.reminder_title)
        val message = screen.findViewById<TextView>(R.id.reminder_message)
        assertEquals(36f, title.textSize, 0f)
        assertEquals(24f, message.textSize, 0f)
        assertEquals(Color.WHITE, title.currentTextColor)
        assertEquals("长时间无转写", title.text.toString())
        assertEquals("会议将在一分钟后自动结束", message.text.toString())
        assertEquals(View.VISIBLE, popup.view().visibility)
        assertEquals(activity.getColor(R.color.reference_blue), (card.background as GradientDrawable).color!!.defaultColor)
        val confirm = screen.findViewById<View>(R.id.reminder_confirm)
        assertEquals(180, confirm.width)
        assertEquals(50, confirm.height)
        confirm.performClick()
        assertEquals(1, confirmations.get())
        popup.render(MeetingReminderPresentation.Notice.NONE)
        assertEquals(View.GONE, popup.view().visibility)
    }

    companion object {
        private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()
        private fun invoke(target: Any, name: String) {
            target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
        }
        private fun invoke(target: Any, name: String, type: Class<*>, argument: Any) {
            target.javaClass.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(target, argument)
        }
        private fun display(bilingual: Boolean, swapped: Boolean, complete: Boolean) =
            MeetingDisplaySnapshot(bilingual, "zh-CN", "en-US", swapped, listOf(MeetingDisplaySnapshot.Row("source",
                TranscriptEvent("source", 2, 0, 100, "zh-CN", "source", complete), TranslationEvent("translation", "source", 1,
                    "zh-CN", "en-US", "译文", true), MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE)), "source")

        private fun render(activity: MainActivity, display: MeetingDisplaySnapshot, version: Int, level: Int,
            capturingPresentation: Boolean = false, active: Boolean = true) {
            MainActivity::class.java.getDeclaredMethod("renderMeetingState", MeetingService.State::class.java)
                .apply { isAccessible = true }.invoke(activity, MeetingService.State(display, "会议中", "本场功能",
                    true, active, true, false, version, null, null, false, "已进行 00:01:00", MeetingReminderPresentation.Notice.NONE,
                    if (display.bilingual()) MeetingSetup.Mode.LIVE_TRANSLATION else MeetingSetup.Mode.LIVE_TRANSCRIPT,
                    false, AudioInputLevel(level, 1000), "", capturingPresentation))
        }
        private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target)
        }
        private fun field(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
        private fun measure(root: View) {
            repeat(3) {
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
            }
        }
        private fun bounds(root: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).also {
            root.offsetDescendantRectToMyCoords(child, it)
        }
        private fun assertFixed(view: View) {
            var parent = view.parent
            while (parent != null) {
                assertFalse("操作不得位于文本滚动区", parent is ScrollView)
                parent = parent.parent
            }
        }
    }
}
