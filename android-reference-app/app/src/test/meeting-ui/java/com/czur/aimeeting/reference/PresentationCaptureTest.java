package com.czur.aimeeting.reference;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import com.czur.aimeeting.client.MeetingSession;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory;
import com.czur.aimeeting.reference.ui.OverlayViewFactory;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

/** 正常SDK图像接入、JPEG策略和Service异步末帧；不操作设备或连接会议后端。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w1920dp-h1080dp-mdpi")
public class PresentationCaptureTest {
    @After public void resetFactory() { MeetingAppAssembly.registerOverlayFactory(new OverlayViewFactory()); }

    @Test public void jpegRetains1280AspectRatioOffsetAndRecyclesDeliveredBitmap() throws Exception {
        Bitmap captured = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888);
        captured.eraseColor(Color.RED);
        PresentationCapture.Frame frame = PresentationCapture.encode(captured, 12345);
        assertEquals(12345, frame.offsetMs());
        assertTrue(captured.isRecycled());
        assertTrue(frame.jpeg().length < 750_000);
        Bitmap decoded = BitmapFactory.decodeByteArray(frame.jpeg(), 0, frame.jpeg().length);
        assertEquals(1280, decoded.getWidth());
        assertEquals(720, decoded.getHeight());
        decoded.recycle();
    }

    @Test public void periodicAndFinalFramesSubmitThenCloseBeforeContinuationWithoutWaiting() throws Exception {
        var host = Robolectric.buildService(MeetingService.class).create();
        MeetingService service = host.get();
        List<String> events = new ArrayList<>();
        FakeSource source = new FakeSource(true, events);
        WorkQueue work = new WorkQueue(), callbacks = new WorkQueue();
        PresentationCapture presentation = new PresentationCapture(source,
                SystemClock.elapsedRealtime() - 1200, work, callbacks);
        set(service, "presentation", presentation);
        set(service, "active", true);
        set(service, "session", new RecordingSession(events));
        try {
            // 实际10秒tick中的请求与Stop末帧共用原Service和同一JPEG工作队列。
            ((Runnable)get(service, "presentationTick")).run();
            service.stopPresentationBefore(() -> events.add("continue"));
            assertEquals(2, source.pending.size());
            assertTrue(events.isEmpty());
            Bitmap first = source.deliver(), last = source.deliver();
            assertTrue(events.isEmpty());
            assertFalse(first.isRecycled());
            assertFalse(last.isRecycled());
            work.drain();
            assertTrue(first.isRecycled());
            assertTrue(last.isRecycled());
            assertTrue(events.isEmpty());
            callbacks.drain();
            assertEquals(List.of("submit:1200", "submit:1200", "close", "continue"), events);
            assertNull(get(service, "presentation"));
        } finally { host.destroy(); }
    }

    @Test public void eachCaptureCollectsMountedWindowAndReminderTokens() throws Exception {
        ShadowSettings.setCanDrawOverlays(true);
        MeetingOverlay overlay = new MeetingOverlay(RuntimeEnvironment.getApplication());
        MeetingDisplaySnapshot display = emptyDisplay();
        try {
            assertEquals(0, overlay.captureWindowTokens().length);
            overlay.show(display, () -> {}, () -> {}, () -> {}, () -> {});
            overlay.render(display, MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:10",
                    new MeetingReminderPresentation.Notice(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
                            "长时间无转写", "请确认"), true, true, 20);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            MeetingOverlayFactory.Window window = (MeetingOverlayFactory.Window)get(overlay, "window");
            View scrim = (View)get(overlay, "reminderScrim");
            MeetingReminderPopup popup = (MeetingReminderPopup)get(overlay, "reminderPopup");
            IBinder[] tokens = overlay.captureWindowTokens();
            assertEquals(3, tokens.length);
            assertArrayEquals(new IBinder[] { window.windowToken(), scrim.getWindowToken(),
                    popup.view().getWindowToken() }, tokens);
            for (IBinder token : tokens) assertNotNull(token);
            overlay.dismiss();
            assertEquals(0, overlay.captureWindowTokens().length);
            overlay.show(display, () -> {}, () -> {}, () -> {}, () -> {});
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            MeetingOverlayFactory.Window reattached = (MeetingOverlayFactory.Window)get(overlay, "window");
            assertArrayEquals(new IBinder[] { reattached.windowToken() }, overlay.captureWindowTokens());
        } finally { overlay.dismiss(); }
    }

    @Test public void oldPlatformSelectsEmptyExclusionsBeforeCapture() throws Exception {
        ShadowSettings.setCanDrawOverlays(true);
        var host = Robolectric.buildService(MeetingService.class).create();
        MeetingService service = host.get();
        FakeSource source = new FakeSource(false, new ArrayList<>());
        WorkQueue work = new WorkQueue(), callbacks = new WorkQueue();
        set(service, "presentation", new PresentationCapture(source, 0, work, callbacks));
        set(service, "session", new RecordingSession(new ArrayList<>()));
        MeetingOverlay overlay = (MeetingOverlay)get(service, "overlay");
        overlay.show(emptyDisplay(), () -> {}, () -> {}, () -> {}, () -> {});
        try {
            assertEquals(1, overlay.captureWindowTokens().length);
            service.stopPresentationBefore(() -> {});
            assertEquals(0, source.excluded.length);
            source.deliver(); work.drain(); callbacks.drain();
        } finally { host.destroy(); }
    }

    @Test public void closingHostRecyclesDeliveredImageStillQueuedForEncoding() {
        FakeSource source = new FakeSource(true, new ArrayList<>());
        WorkQueue work = new WorkQueue(), callbacks = new WorkQueue();
        PresentationCapture capture = new PresentationCapture(source, 0, work, callbacks);
        capture.capture(1000, new IBinder[0], new PresentationCapture.Callback() {
            public void onSuccess(PresentationCapture.Frame frame) { fail("已关闭宿主不再交付此帧"); }
            public void onError(String message) {}
        });
        Bitmap delivered = source.deliver();
        assertFalse(delivered.isRecycled());
        capture.close();
        assertTrue(delivered.isRecycled());
        work.drain(); callbacks.drain();
    }

    private static MeetingDisplaySnapshot emptyDisplay() {
        return new MeetingDisplaySnapshot(false, "zh-CN", "en-US", false, List.of(), null);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static final class WorkQueue implements Executor {
        final Queue<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queued.add(command); }
        void drain() { while (!queued.isEmpty()) queued.remove().run(); }
    }
    private static final class FakeSource implements ScreenCaptureSource {
        final boolean exclusion;
        final List<String> events;
        final Queue<Callback> pending = new ArrayDeque<>();
        IBinder[] excluded;
        FakeSource(boolean exclusion, List<String> events) { this.exclusion = exclusion; this.events = events; }
        @Override public String mode() { return exclusion ? "SYSTEM_CAPTURE" : "MEDIA_PROJECTION"; }
        @Override public boolean supportsWindowExclusion() { return exclusion; }
        @Override public void captureBitmap(IBinder[] tokens, Callback callback) {
            excluded = tokens;
            pending.add(callback);
        }
        Bitmap deliver() {
            Bitmap bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            pending.remove().onSuccess(bitmap);
            return bitmap;
        }
        @Override public void close() { events.add("close"); }
    }
    private static final class RecordingSession implements MeetingSession {
        final List<String> events;
        RecordingSession(List<String> events) { this.events = events; }
        @Override public void submitPresentation(long offsetMs, byte[] jpeg) { events.add("submit:" + offsetMs); }
        @Override public void requestStop() {}
        @Override public void swapDisplayColumns() {}
        @Override public void close() {}
    }
}
