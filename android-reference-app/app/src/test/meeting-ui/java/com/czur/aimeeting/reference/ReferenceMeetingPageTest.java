package com.czur.aimeeting.reference;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import com.czur.aimeeting.client.*;
import com.czur.aimeeting.client.display.*;
import com.czur.aimeeting.client.result.*;
import com.czur.aimeeting.client.server.DeploymentOptions;
import com.czur.aimeeting.model.*;
import com.czur.aimeeting.reference.meeting.*;
import com.czur.aimeeting.ui.SubtitleTextView;
import com.czur.aimeeting.reference.ui.OverlayViewFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;

/** 实际原生布局与正常接线；不建会、不联网、不启动音源或操作实体设备。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReferenceMeetingPageTest {
    @After public void resetAssembly() {
        MeetingAppAssembly.configureBackend("");
        MeetingAppAssembly.configureResultWeb("");
        MeetingAppAssembly.clearReferenceSession();
        MeetingAppAssembly.registerPageFactory(new MeetingPageViews.DefaultFactory());
        MeetingAppAssembly.registerOverlayFactory(new OverlayViewFactory());
        // 清理同进程交接，不执行其他测试留下的平台操作。
        var cleanup = Robolectric.buildService(MeetingService.class).create();
        while (cleanup.get().takeUiOperation() != null) { }
        cleanup.destroy();
    }

    // 同一个会前正常用例使用1.5密度：能捕获shape逐角／selector遗漏换算的实际视觉差异。
    @Config(qualifiers = "w1280dp-h720dp-hdpi")
    @Test public void centeredCardHasSingleLanguageAndFixedStartOutsideScroll() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        MeetingPageViews page = new MeetingPageViews.DefaultFactory().create(activity);
        measure(page.root);
        Rect card = bounds(page.root, page.setupCard);
        assertEquals(988, card.width());
        assertEquals(856, card.height());
        assertEquals(960, card.centerX());
        assertEquals(540, card.centerY());
        assertEquals(48f, page.setupTitle.getTextSize(), 0f);
        assertEquals(30f, ((TextView)page.root.findViewById(R.id.setup_subtitle)).getTextSize(), 0f);
        assertEquals(View.GONE, page.targetContainer.getVisibility());
        assertEquals(View.GONE, page.swapLanguages.getVisibility());
        assertFixed(page.start);
        Rect start = bounds(page.root, page.start);
        assertEquals(180, start.width());
        assertEquals(50, start.height());
        var header = (android.graphics.drawable.GradientDrawable)
                page.root.findViewById(R.id.setup_header).getBackground();
        assertArrayEquals(new float[] {40, 40, 40, 40, 0, 0, 0, 0}, header.getCornerRadii(), 0f);
        var buttons = (android.graphics.drawable.DrawableContainer.DrawableContainerState)
                page.start.getBackground().getConstantState();
        assertEquals(3, buttons.getChildCount());
        // Android inflate圆角先取整像素（hdpi的15dp成为23px），设计换算允许不足半像素舍入。
        for (int index = 0; index < buttons.getChildCount(); index++)
            assertEquals(15f, ((android.graphics.drawable.GradientDrawable)buttons.getChild(index)).getCornerRadius(), 0.5f);
        assertTrue(start.bottom <= card.bottom && start.top >= card.top);
        assertNull(page.root.findViewById(R.id.backend_url));
        assertNull(page.root.findViewById(R.id.web_url));
        page.showSetupMode(true);
        assertEquals(View.VISIBLE, page.targetContainer.getVisibility());
        assertEquals(View.VISIBLE, page.swapLanguages.getVisibility());
        assertEquals(View.GONE, page.presentation.getVisibility());
        assertEquals("AI双语互译", page.setupTitle.getText().toString());
        assertEquals("（实时将两种语言进行互译）",
                ((TextView)page.root.findViewById(R.id.setup_subtitle)).getText().toString());
    }

    @Test public void accountEntryAndConnectionReturnCloseOldClientAndClearOldCapabilities() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        activity.findViewById(R.id.account_button).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertEquals("账号登录", ((TextView)dialog.findViewById(R.id.login_title)).getText().toString());
        dialog.dismiss();
        activity.findViewById(R.id.settings_button).performClick();
        assertEquals(ConnectionSettingsActivity.class.getName(), shadowOf(activity)
                .getNextStartedActivityForResult().intent.getComponent().getClassName());
        AtomicInteger closes = new AtomicInteger();
        MeetingClient old = (MeetingClient) Proxy.newProxyInstance(MeetingClient.class.getClassLoader(),
                new Class<?>[] {MeetingClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) closes.incrementAndGet();
                    else fail("旧客户端不得继续访问后端");
                    return null;
                });
        field(activity, "preMeetingClient", old);
        field(activity, "deploymentCapabilities", Set.of(MeetingCapabilityState.TRANSCRIPT));
        activity.onActivityResult(1003, Activity.RESULT_OK, null);
        assertEquals(1, closes.get());
        assertNull(field(activity, "preMeetingClient"));
        assertEquals(Set.of(), field(activity, "deploymentCapabilities"));
        assertFalse(activity.findViewById(R.id.start_meeting).isEnabled());
    }

    @Test public void subtitleTranslationAndFixedStopUseSameProjectionAndServiceCallbacks() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        MeetingPageViews page = (MeetingPageViews) field(activity, "page");
        var single = display(false, false, false);
        render(activity, single, 1, 47);
        measure(page.root);
        assertEquals(View.VISIBLE, page.meetingPanel.getVisibility());
        assertEquals(47, page.level.getProgress());
        assertEquals(47, page.voiceLevel.getLevel());
        assertEquals(View.GONE, page.levelText.getVisibility());
        assertEquals(View.GONE, page.capabilities.getVisibility());
        SubtitleTextView subtitle = (SubtitleTextView) page.transcriptList.getChildAt(1);
        assertEquals("source", subtitle.getParts().get(0).text().toString());
        assertFalse(subtitle.getParts().get(0).isFinal());
        render(activity, display(false, false, true), 2, 48);
        assertEquals(2, page.transcriptList.getChildCount());
        assertTrue(subtitle.getParts().get(0).isFinal());
        render(activity, display(true, false, false), 3, 55);
        measure(page.root);
        assertEquals("source", ((SubtitleTextView)page.leftList.getChildAt(0)).getParts().get(0).text().toString());
        assertEquals("译文", ((SubtitleTextView)page.leftList.getChildAt(1)).getParts().get(0).text().toString());
        assertEquals(0, page.rightList.getChildCount());
        assertFalse(((SubtitleTextView)page.leftList.getChildAt(0)).getParts().get(0).isFinal());
        assertTrue(((SubtitleTextView)page.leftList.getChildAt(1)).getParts().get(0).isFinal());
        render(activity, display(true, true, false), 4, 55);
        assertEquals(0, page.leftList.getChildCount());
        assertEquals("source", ((SubtitleTextView)page.rightList.getChildAt(0)).getParts().get(0).text().toString());
        assertEquals("译文", ((SubtitleTextView)page.rightList.getChildAt(1)).getParts().get(0).text().toString());
        assertFixed(page.stop);
        assertFixed(page.swapColumns);
        assertFixed(page.overlay);
        assertTrue(bounds(page.root, page.stop).bottom <= 1080);
        assertFalse(page.account.isEnabled());
        assertFalse(page.settings.isEnabled());
        var controller = Robolectric.buildService(MeetingService.class).create();
        MeetingService service = controller.get();
        AtomicInteger stops = new AtomicInteger(), swaps = new AtomicInteger();
        MeetingSession session = (MeetingSession)Proxy.newProxyInstance(MeetingSession.class.getClassLoader(),
                new Class<?>[] {MeetingSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("requestStop")) stops.incrementAndGet();
                    if (method.getName().equals("swapDisplayColumns")) swaps.incrementAndGet();
                    return null;
                });
        field(service, "active", true);
        field(service, "canStop", true);
        field(service, "display", display(true, true, false));
        field(service, "session", session);
        field(activity, "meetingService", service);
        page.swapColumns.performClick();
        page.stop.performClick();
        assertEquals(1, swaps.get());
        assertEquals(1, stops.get());
        assertFalse((boolean)field(service, "canStop"));
        assertNull(field(service, "audioLevel"));
        controller.destroy();
    }

    @Test public void defaultWebSettingsFreezeCurrentUrlAtStartThenKeepSameMeeting() {
        var app = RuntimeEnvironment.getApplication();
        MeetingAppAssembly.configureResultWeb("https://before.example.test/");
        var settings = new WebResultActionPlugin().createSettings(app);
        assertNull(settings.view());
        MeetingAppAssembly.configureResultWeb("https://start.example.test/index.html?x=1#part");
        var prepared = settings.freeze().get().prepare(new MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT,
                "zh-CN", "en-US", false, ResultOptions.action("web"), "", List.of(), false).toOptions());
        assertTrue(prepared.allowed());
        MeetingAppAssembly.configureResultWeb("https://after.example.test/");
        MeetingResult ready = new MeetingResult("mtg_same", "本场", 555, MeetingResult.Status.READY,
                Set.of("transcript.final"), null);
        ResultActionAccess unusedAccess = new ResultActionAccess() {
            @Override public void readContent(MeetingClient.Callback<MeetingResultContent> callback) { fail(); }
            @Override public void sendEmail(List<String> emails, String url, MeetingClient.Callback<Void> callback) { fail(); }
        };
        prepared.execution().execute(new ResultActionContext(ready, unusedAccess, Set.of(), Runnable::run, Runnable::run),
                new MeetingClient.Callback<ResultActionOutcome>() {
                    @Override public void onSuccess(ResultActionOutcome result) {
                        assertEquals(ResultActionOutcome.Status.SUCCEEDED, result.status());
                    }
                    @Override public void onError(String message) { fail(message); }
                });
        Intent opened = shadowOf(app).getNextStartedActivity();
        assertEquals("https://start.example.test/index.html?x=1&meetingId=mtg_same&genTime=555#part",
                opened.getStringExtra(ResultActivity.EXTRA_RESULT_URL));
    }

    @Test public void necessaryCaptureNoticesRenderThroughRealServiceStateWithoutShowingDiagnostics() throws Exception {
        var serviceHost = Robolectric.buildService(MeetingService.class).create();
        MeetingService service = serviceHost.get();
        // 真实可见生命周期会执行bindService；为Robolectric提供本场实际LocalBinder。
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName(RuntimeEnvironment.getApplication(), MeetingService.class), service.onBind(new Intent()));
        var activityHost = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        MainActivity activity = activityHost.get();
        MeetingPageViews page = (MeetingPageViews) field(activity, "page");
        try {
            field(service, "active", true);
            invoke(service, "publish");
            assertEquals(View.GONE, page.presentationNotice.getVisibility());
            String compatibility = "当前系统仅支持整屏截图，演示图片会包含悬浮窗";
            field(service, "presentationNotice", compatibility);
            invoke(service, "publish");
            measure(page.root);
            assertEquals(View.VISIBLE, page.presentationNotice.getVisibility());
            assertEquals(compatibility, page.presentationNotice.getText().toString());
            assertTrue(page.presentationNotice.isShown());
            // SDK阶段更新只修改status；用真实publish→Activity渲染确认专用消息继续存在。
            field(service, "status", "会议进行中 · savedEventCount=42");
            invoke(service, "publish");
            assertEquals(compatibility, page.presentationNotice.getText().toString());
            assertEquals(View.GONE, page.status.getVisibility());
            assertEquals(View.GONE, page.capabilities.getVisibility());
            invoke(service, "presentationFailed", String.class, "fixture底层采集失败诊断");
            assertEquals("演示截图采集失败；字幕会议继续", page.presentationNotice.getText().toString());
            assertTrue(page.presentationNotice.isShown());
            invoke(service, "onPresentationStopped");
            assertEquals("系统已结束屏幕共享，字幕会议继续", page.presentationNotice.getText().toString());
            assertTrue(page.presentationNotice.isShown());
            field(service, "active", false);
            invoke(service, "publish");
            assertEquals(View.GONE, page.presentationNotice.getVisibility());
            assertEquals(View.VISIBLE, page.status.getVisibility());
        } finally {
            activityHost.pause().stop().destroy();
            serviceHost.destroy();
        }
    }

    @Test public void backgroundReadyWebSurvivesServiceDestroyAndVisibleActivityConsumesOnce() throws Exception {
        try (var fixture = new ResultUiFixture(false)) {
            fixture.ready();
            idleMain();
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
            assertEquals(0, fixture.successes.get());
            fixture.destroyOld();
            fixture.returnToVisibleActivity();
            idleMain();
            fixture.assertOpenedOnce();
            fixture.activity.pause().resume();
            idleMain();
            fixture.assertNoFurtherOpen();
            // Registry完成通知也是普通Runnable；旧宿主关闭不能关掉它的Executor。
            AtomicInteger ordinary = new AtomicInteger();
            invoke(fixture.old, "queueUiOperation", Runnable.class, (Runnable) ordinary::incrementAndGet);
            idleMain();
            assertEquals(1, ordinary.get());
            assertEquals(fixture.oldStatus, field(fixture.old, "status"));
            assertNull(field(fixture.old, "actionOutcome"));
            assertEquals(1, fixture.closes.get());
            assertEquals(1, fixture.accesses.get());
            assertEquals(0, fixture.windowCreates.get());
        }
    }

    @Test public void latePreparedWebFromDestroyedServiceNotifiesNewVisibleConsumer() throws Exception {
        try (var fixture = new ResultUiFixture(false, true)) {
            fixture.ready();
            idleMain(); // 真实Web已准备UiOperation，fixture仅延迟向原Executor交接。
            fixture.destroyOld();
            fixture.returnToVisibleActivity();
            assertEquals(0, fixture.successes.get());
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
            fixture.preparedUiHandoff.run(); // 交给原Service Executor，不直接执行UiOperation。
            idleMain(); // 必须通知当前可见新宿主，而非已销毁Service。
            fixture.assertOpenedOnce();
            assertEquals(fixture.oldStatus, field(fixture.old, "status"));
            assertEquals(1, fixture.closes.get());
            assertEquals(1, fixture.accesses.get());
            fixture.activity.pause().resume();
            idleMain();
            fixture.assertNoFurtherOpen();
        }
    }

    @Test public void visibleWebFailureKeepsOriginalSessionForExplicitRetryThenReleasesOnce() throws Exception {
        try (var fixture = new ResultUiFixture(true)) {
            fixture.returnToVisibleActivity(fixture.old);
            fixture.ready();
            idleMain();
            assertEquals(1, fixture.errors.get());
            assertEquals(0, fixture.successes.get());
            assertEquals(ResultActionOutcome.Status.FAILED,
                    ((ResultActionOutcome) field(fixture.old, "actionOutcome")).status());
            assertNotNull(field(fixture.old, "session"));
            assertEquals(0, fixture.closes.get());
            fixture.old.retryResultAction();
            idleMain();
            fixture.assertOpenedOnce();
            assertEquals(2, fixture.accesses.get());
            assertNull(field(fixture.old, "session"));
            assertEquals(1, fixture.closes.get());
            fixture.old.retryResultAction();
            idleMain();
            fixture.assertNoFurtherOpen();
        }
    }

    /** fixture READY／窄协议句柄；执行真实Web冻结闭包、Registry和Service，不建会或访问网络。 */
    private static final class ResultUiFixture implements AutoCloseable {
        static final String WEB = "https://frozen.example.test/index.html?x=1#part";
        final AtomicInteger successes = new AtomicInteger(), errors = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger(), accesses = new AtomicInteger(), windowCreates = new AtomicInteger();
        final ServiceController<MeetingService> oldController = Robolectric.buildService(MeetingService.class).create();
        final MeetingService old = oldController.get();
        ServiceController<MeetingService> next;
        ActivityController<MainActivity> activity;
        boolean oldDestroyed;
        Object oldStatus;
        Runnable preparedUiHandoff;

        ResultUiFixture(boolean failFirstOpen) throws Exception { this(failFirstOpen, false); }
        ResultUiFixture(boolean failFirstOpen, boolean delayUiHandoff) throws Exception {
            var app = RuntimeEnvironment.getApplication();
            MeetingAppAssembly.configureResultWeb(WEB);
            var setup = new MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US", false,
                    ResultOptions.action("web"), "", List.of(), false);
            ResultActionPlugin web = new WebResultActionPlugin().createSettings(app).freeze().get();
            if (failFirstOpen) {
                AtomicInteger attempts = new AtomicInteger();
                web = new WebResultActionPlugin(WEB, url -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("fixture首次打开失败");
                    app.startActivity(new Intent(app, ResultActivity.class)
                            .putExtra(ResultActivity.EXTRA_RESULT_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                });
            }
            var frozen = MeetingAppAssembly.prepare(setup, new MeetingDiscovery(
                    new DeploymentOptions(Set.of(), 10_000L, false), Set.of()), web).meeting();
            assertNotNull(frozen);
            var original = frozen.action();
            var counted = new ResultActionRegistry.PreparedAction(original.id(), original.requiredArtifactTypes(),
                    original.requiredServiceCapabilities(), (context, callback) -> {
                        var executionContext = delayUiHandoff ? new ResultActionContext(context.result(), context.access(),
                                context.serviceCapabilities(), context.workExecutor(), command -> {
                                    if (command instanceof AppResultActionPlugin.UiOperation)
                                        preparedUiHandoff = () -> context.uiExecutor().execute(command);
                                    else context.uiExecutor().execute(command);
                                }) : context;
                        original.execution().execute(executionContext, new MeetingClient.Callback<ResultActionOutcome>() {
                                @Override public void onSuccess(ResultActionOutcome outcome) {
                                    successes.incrementAndGet();
                                    callback.onSuccess(outcome);
                                }
                                @Override public void onError(String message) {
                                    errors.incrementAndGet();
                                    callback.onError(message);
                                }
                            });
                    });
            field(old, "preparedMeeting", new MeetingAppAssembly.PreparedMeeting(setup, counted, frozen.serviceCapabilities()));
            field(old, "readyResult", new MeetingResult("mtg_same", "fixture", 555, MeetingResult.Status.READY,
                    Set.of("transcript.final"), null));
            ResultActionAccess unused = new ResultActionAccess() {
                @Override public void readContent(MeetingClient.Callback<MeetingResultContent> callback) { fail("Web不读正文"); }
                @Override public void sendEmail(List<String> emails, String url, MeetingClient.Callback<Void> callback) { fail("Web不发邮件"); }
            };
            field(old, "session", Proxy.newProxyInstance(MeetingSession.class.getClassLoader(),
                    new Class<?>[] {MeetingSession.class}, (proxy, method, args) -> {
                        assertEquals("resultActionAccess", method.getName());
                        assertEquals("关闭后不得再次访问Session", 0, closes.get());
                        accesses.incrementAndGet();
                        return unused;
                    }));
            field(old, "client", Proxy.newProxyInstance(MeetingClient.class.getClassLoader(),
                    new Class<?>[] {MeetingClient.class}, (proxy, method, args) -> {
                        assertEquals("close", method.getName());
                        closes.incrementAndGet();
                        return null;
                    }));
            var defaultWindow = new OverlayViewFactory();
            MeetingAppAssembly.registerOverlayFactory((context, open, swap, stop) -> {
                windowCreates.incrementAndGet();
                return defaultWindow.create(context, open, swap, stop);
            });
            MeetingAppAssembly.configureResultWeb("https://next.example.test/");
        }

        void ready() throws Exception { invoke(old, "resultReady"); }
        void destroyOld() throws Exception {
            oldStatus = field(old, "status");
            oldController.destroy();
            oldDestroyed = true;
            assertNull(field(old, "session"));
            assertTrue(((ExecutorService) field(old, "platformWork")).isShutdown());
            assertFalse(((MeetingOverlay) field(old, "overlay")).isShowing());
        }
        void returnToVisibleActivity() throws Exception {
            next = Robolectric.buildService(MeetingService.class).create();
            returnToVisibleActivity(next.get());
        }
        void returnToVisibleActivity(MeetingService service) throws Exception {
            activity = Robolectric.buildActivity(MainActivity.class).create();
            // 使用真实LocalBinder／ServiceConnection和onResume，不直接poll或run UI操作。
            ((ServiceConnection) field(activity.get(), "serviceConnection")).onServiceConnected(
                    new ComponentName(RuntimeEnvironment.getApplication(), MeetingService.class), service.onBind(new Intent()));
            activity.resume();
        }
        void assertOpenedOnce() {
            Intent opened = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertNotNull("旧Service销毁后必须恢复本场实际Web操作", opened);
            assertEquals(ResultActivity.class.getName(), opened.getComponent().getClassName());
            assertEquals("https://frozen.example.test/index.html?x=1&meetingId=mtg_same&genTime=555#part",
                    opened.getStringExtra(ResultActivity.EXTRA_RESULT_URL));
            assertEquals(1, successes.get());
            assertNoFurtherOpen();
        }
        void assertNoFurtherOpen() {
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
            assertEquals(1, successes.get());
        }
        @Override public void close() {
            if (activity != null) activity.pause().destroy();
            if (next != null) next.destroy();
            if (!oldDestroyed) oldController.destroy();
        }
    }

    private static void idleMain() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
    private static void invoke(Object target, String name, Class<?> type, Object argument) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(target, argument);
    }

    @Test public void independentSettingsSaveAndNativeWebBackRetainOriginalUrl() {
        var settings = Robolectric.buildActivity(ConnectionSettingsActivity.class).create().get();
        ((EditText)settings.findViewById(R.id.backend_url)).setText("https://backend.example.test");
        ((EditText)settings.findViewById(R.id.web_url)).setText("https://result.example.test/index.html");
        settings.findViewById(R.id.connection_save).performClick();
        assertEquals(Activity.RESULT_OK, shadowOf(settings).getResultCode());
        assertTrue(settings.isFinishing());
        assertEquals("https://backend.example.test", MeetingAppAssembly.serverUrl());
        assertEquals("https://result.example.test/index.html", MeetingAppAssembly.resultWebUrl());
        String url = "https://result.example.test/?meetingId=mtg_same&genTime=555";
        var result = Robolectric.buildActivity(ResultActivity.class, new Intent().putExtra(ResultActivity.EXTRA_RESULT_URL, url)).create().get();
        android.webkit.WebView web = result.findViewById(R.id.result_web);
        assertEquals(url, shadowOf(web).getLastLoadedUrl());
        result.findViewById(R.id.result_back).performClick();
        assertTrue(result.isFinishing());
    }

    @Test public void domesticBlueReminderRendersExistingNoticeAndConfirmsWithoutChangingClock() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        AtomicInteger confirmations = new AtomicInteger();
        MeetingReminderPopup popup = new MeetingReminderPopup(activity, false, confirmations::incrementAndGet);
        android.widget.FrameLayout screen = new android.widget.FrameLayout(activity);
        screen.addView(popup.view(), new android.widget.FrameLayout.LayoutParams(-1, -1));
        popup.render(new MeetingReminderPresentation.Notice(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
                "长时间无转写", "会议将在一分钟后自动结束"));
        measure(screen);
        View card = screen.findViewById(R.id.reminder_card);
        assertEquals(500, card.getWidth());
        assertEquals(360, card.getHeight());
        TextView title = screen.findViewById(R.id.reminder_title);
        TextView message = screen.findViewById(R.id.reminder_message);
        assertEquals(36f, title.getTextSize(), 0f);
        assertEquals(24f, message.getTextSize(), 0f);
        assertEquals(android.graphics.Color.WHITE, title.getCurrentTextColor());
        assertEquals("长时间无转写", title.getText().toString());
        assertEquals("会议将在一分钟后自动结束", message.getText().toString());
        assertEquals(View.VISIBLE, popup.view().getVisibility());
        assertEquals(activity.getColor(R.color.reference_blue),
                ((android.graphics.drawable.GradientDrawable)card.getBackground()).getColor().getDefaultColor());
        View confirm = screen.findViewById(R.id.reminder_confirm);
        assertEquals(180, confirm.getWidth());
        assertEquals(50, confirm.getHeight());
        confirm.performClick();
        assertEquals(1, confirmations.get());
        popup.render(MeetingReminderPresentation.Notice.NONE);
        assertEquals(View.GONE, popup.view().getVisibility());
    }

    private static MeetingDisplaySnapshot display(boolean bilingual, boolean swapped, boolean complete) {
        return new MeetingDisplaySnapshot(bilingual, "zh-CN", "en-US", swapped, List.of(
                new MeetingDisplaySnapshot.Row("source", new TranscriptEvent("source", 2, 0, 100,
                        "zh-CN", "source", complete), new TranslationEvent("translation", "source", 1,
                        "zh-CN", "en-US", "译文", true), MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE)), "source");
    }
    private static void render(MainActivity activity, MeetingDisplaySnapshot display, int version, int level) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("renderMeetingState", MeetingService.State.class);
        method.setAccessible(true);
        method.invoke(activity, new MeetingService.State(display, "会议中", "本场功能", true, true, true, false,
                version, null, null, false, "已进行 00:01:00", MeetingReminderPresentation.Notice.NONE,
                display.bilingual() ? MeetingSetup.Mode.LIVE_TRANSLATION : MeetingSetup.Mode.LIVE_TRANSCRIPT,
                false, new AudioInputLevel(level, 1000), ""));
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void measure(View root) {
        for (int i = 0; i < 3; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080);
        }
    }
    private static Rect bounds(ViewGroup root, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        root.offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }
    private static void assertFixed(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            assertFalse("操作不得位于文本滚动区", parent instanceof ScrollView);
    }
}
