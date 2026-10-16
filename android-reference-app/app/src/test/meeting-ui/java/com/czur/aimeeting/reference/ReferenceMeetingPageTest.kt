package com.czur.aimeeting.reference

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
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

/** 实际原生布局与正常接线；不建会、不联网、不启动音源或操作实体设备。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceMeetingPageTest {
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
        assertNull(page.root.findViewById<View>(R.id.backend_url))
        assertNull(page.root.findViewById<View>(R.id.web_url))
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
    }

    @Test fun accountEntryAndConnectionReturnCloseOldClientAndClearOldCapabilities() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.findViewById<View>(R.id.account_button).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals("账号登录", dialog.findViewById<TextView>(R.id.login_title).text.toString())
        dialog.dismiss()
        activity.findViewById<View>(R.id.settings_button).performClick()
        assertEquals(ConnectionSettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivityForResult.intent.component!!.className)
        val closes = AtomicInteger()
        val old = Proxy.newProxyInstance(MeetingClient::class.java.classLoader,
            arrayOf(MeetingClient::class.java)) { _, method, _ ->
            if (method.name == "close") closes.incrementAndGet() else fail("旧客户端不得继续访问后端")
            null
        } as MeetingClient
        field(activity, "preMeetingClient", old)
        field(activity, "deploymentCapabilities", setOf(MeetingCapabilityState.TRANSCRIPT))
        // Kotlin的protected不包含同包访问；仍调用原生命周期入口，不改App的可见性。
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(activity, 1003, Activity.RESULT_OK, null)
        assertEquals(1, closes.get())
        assertNull(field(activity, "preMeetingClient"))
        assertEquals(emptySet<String>(), field(activity, "deploymentCapabilities"))
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
        assertFalse(page.settings.isEnabled)
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
        assertNull(settings.view())
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
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
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
            fixture.returnToVisibleActivity(fixture.old)
            fixture.ready()
            idleMain()
            assertEquals(1, fixture.errors.get())
            assertEquals(0, fixture.successes.get())
            assertEquals(ResultActionOutcome.Status.FAILED, (field(fixture.old, "actionOutcome") as ResultActionOutcome).status())
            assertNotNull(field(fixture.old, "session"))
            assertEquals(0, fixture.closes.get())
            fixture.old.retryResultAction()
            idleMain()
            fixture.assertOpenedOnce()
            assertEquals(2, fixture.accesses.get())
            assertNull(field(fixture.old, "session"))
            assertEquals(1, fixture.closes.get())
            fixture.old.retryResultAction()
            idleMain()
            fixture.assertNoFurtherOpen()
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

    @Test fun independentSettingsSaveAndNativeWebBackRetainOriginalUrl() {
        val settings = Robolectric.buildActivity(ConnectionSettingsActivity::class.java).create().get()
        settings.findViewById<EditText>(R.id.backend_url).setText("https://backend.example.test")
        settings.findViewById<EditText>(R.id.web_url).setText("https://result.example.test/index.html")
        settings.findViewById<View>(R.id.connection_save).performClick()
        assertEquals(Activity.RESULT_OK, shadowOf(settings).resultCode)
        assertTrue(settings.isFinishing)
        assertEquals("https://backend.example.test", MeetingAppAssembly.serverUrl())
        assertEquals("https://result.example.test/index.html", MeetingAppAssembly.resultWebUrl())
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

        private fun render(activity: MainActivity, display: MeetingDisplaySnapshot, version: Int, level: Int) {
            MainActivity::class.java.getDeclaredMethod("renderMeetingState", MeetingService.State::class.java)
                .apply { isAccessible = true }.invoke(activity, MeetingService.State(display, "会议中", "本场功能",
                    true, true, true, false, version, null, null, false, "已进行 00:01:00", MeetingReminderPresentation.Notice.NONE,
                    if (display.bilingual()) MeetingSetup.Mode.LIVE_TRANSLATION else MeetingSetup.Mode.LIVE_TRANSCRIPT,
                    false, AudioInputLevel(level, 1000), ""))
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
