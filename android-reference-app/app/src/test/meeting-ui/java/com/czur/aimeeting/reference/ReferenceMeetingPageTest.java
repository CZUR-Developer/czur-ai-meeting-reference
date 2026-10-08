package com.czur.aimeeting.reference;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import com.czur.aimeeting.client.*;
import com.czur.aimeeting.client.display.*;
import com.czur.aimeeting.client.result.*;
import com.czur.aimeeting.model.*;
import com.czur.aimeeting.reference.meeting.*;
import com.czur.aimeeting.ui.SubtitleTextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAlertDialog;

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
    }

    @Test public void centeredCardHasSingleLanguageAndFixedStartOutsideScroll() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        MeetingPageViews page = new MeetingPageViews.DefaultFactory().create(activity);
        measure(page.root);
        Rect card = bounds(page.root, page.setupCard);
        assertEquals(988, card.width());
        assertEquals(856, card.height());
        assertEquals(960, card.centerX());
        assertEquals(View.GONE, page.targetContainer.getVisibility());
        assertEquals(View.GONE, page.swapLanguages.getVisibility());
        assertFixed(page.start);
        Rect start = bounds(page.root, page.start);
        assertTrue(start.bottom <= card.bottom && start.top >= card.top);
        assertNull(page.root.findViewById(R.id.backend_url));
        assertNull(page.root.findViewById(R.id.web_url));
        page.showSetupMode(true);
        assertEquals(View.VISIBLE, page.targetContainer.getVisibility());
        assertEquals(View.VISIBLE, page.swapLanguages.getVisibility());
        assertEquals(View.GONE, page.presentation.getVisibility());
        assertEquals("AI 双语互译", page.setupTitle.getText().toString());
    }

    @Test public void accountEntryAndConnectionReturnCloseOldClientAndClearOldCapabilities() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        activity.findViewById(R.id.account_button).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertEquals("参考账号登录", shadowOf(dialog).getTitle().toString());
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
        SubtitleTextView subtitle = (SubtitleTextView) page.transcriptList.getChildAt(1);
        assertEquals("source", subtitle.getParts().get(0).text().toString());
        assertFalse(subtitle.getParts().get(0).isFinal());
        render(activity, display(false, false, true), 2, 48);
        assertEquals(2, page.transcriptList.getChildCount());
        assertTrue(subtitle.getParts().get(0).isFinal());
        render(activity, display(true, false, false), 3, 55);
        measure(page.root);
        assertEquals("source", ((SubtitleTextView)page.leftList.getChildAt(0)).getParts().get(0).text().toString());
        assertEquals("译文", ((SubtitleTextView)page.rightList.getChildAt(0)).getParts().get(0).text().toString());
        assertFalse(((SubtitleTextView)page.leftList.getChildAt(0)).getParts().get(0).isFinal());
        assertTrue(((SubtitleTextView)page.rightList.getChildAt(0)).getParts().get(0).isFinal());
        render(activity, display(true, true, false), 4, 55);
        assertEquals("译文", ((SubtitleTextView)page.leftList.getChildAt(0)).getParts().get(0).text().toString());
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
                false, new AudioInputLevel(level, 1000)));
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
