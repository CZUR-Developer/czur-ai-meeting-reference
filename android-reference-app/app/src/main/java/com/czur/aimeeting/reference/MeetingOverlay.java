package com.czur.aimeeting.reference;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory;

/**
 * 参考 App 的薄悬浮窗宿主。窗口内容由集中装配的工厂提供，仍读取 Service 的同一快照，
 * Stop／交换回调沿用原入口；权限申请、前台 Service、提醒及屏采继续由 App 持有。
 * 全部入口由原 Service 在主线程调用，隐藏／释放不停止会议或改变音源。
 */
final class MeetingOverlay {
    private static final String TAG = "AiMeetingReference";
    private final Context context;
    private final WindowManager windowManager;
    private MeetingOverlayFactory.Window window;
    private View reminderScrim;
    private MeetingReminderPopup reminderPopup;
    private Runnable onConfirmReminder;

    MeetingOverlay(Context context) {
        this.context = context;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() { return window != null && window.isShowing(); }

    void show(MeetingDisplaySnapshot state, Runnable onOpenApp, Runnable onSwapColumns,
            Runnable onConfirmReminder, Runnable onStop) {
        if (isShowing()) return;
        this.onConfirmReminder = onConfirmReminder;
        window = MeetingAppAssembly.overlayFactory().create(context, onOpenApp, onSwapColumns, onStop);
        if (!window.show(state)) {
            window.dismiss();
            window = null;
            Log.w(TAG, "跨应用窗口需要显示权限");
        }
    }

    void render(MeetingDisplaySnapshot state, MeetingSetup.Mode mode, String timeText,
            MeetingReminderPresentation.Notice reminder, boolean canStop, boolean allowOverlayReminder) {
        render(state, mode, mode == MeetingSetup.Mode.MEETING_MINUTES, timeText, reminder,
                canStop, allowOverlayReminder);
    }

    /** 同一会中模式可选择总结；窗口不改变模型或收口流程。 */
    void render(MeetingDisplaySnapshot state, MeetingSetup.Mode mode, boolean summaryEnabled,
            String timeText, MeetingReminderPresentation.Notice reminder, boolean canStop,
            boolean allowOverlayReminder) {
        render(state, mode, summaryEnabled, timeText, reminder, canStop, allowOverlayReminder, 0);
    }

    /** 电平来自 Service 的同场快照；窗口不订阅音源，提醒仍是 App 的原窗口。 */
    void render(MeetingDisplaySnapshot state, MeetingSetup.Mode mode, boolean summaryEnabled,
            String timeText, MeetingReminderPresentation.Notice reminder, boolean canStop,
            boolean allowOverlayReminder, int audioLevel) {
        if (window == null) return;
        window.render(state, mode, summaryEnabled, timeText, canStop, audioLevel);
        renderReminder(reminder, allowOverlayReminder && window.isShowing());
    }

    private void renderReminder(MeetingReminderPresentation.Notice notice, boolean allowed) {
        if (!allowed || notice.kind() == MeetingReminderPresentation.Kind.NONE) {
            clearReminder();
            return;
        }
        if (reminderPopup == null) {
            try {
                reminderScrim = new View(context);
                reminderScrim.setBackgroundColor(0xB8000000);
                windowManager.addView(reminderScrim, new WindowManager.LayoutParams(
                        -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT));
                reminderPopup = new MeetingReminderPopup(context, true, onConfirmReminder);
                WindowManager.LayoutParams cardParams = new WindowManager.LayoutParams(
                        Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(48), dp(440)),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        android.graphics.PixelFormat.TRANSLUCENT);
                cardParams.gravity = Gravity.CENTER;
                reminderPopup.render(notice);
                windowManager.addView(reminderPopup.view(), cardParams);
            } catch (RuntimeException unavailable) {
                clearReminder();
                Log.w(TAG, "跨应用提醒窗不可用 reason=" + unavailable.getClass().getSimpleName());
            }
        } else reminderPopup.render(notice);
    }

    private void clearReminder() {
        if (reminderPopup != null) {
            if (reminderPopup.view().getParent() != null) windowManager.removeView(reminderPopup.view());
            reminderPopup = null;
        }
        if (reminderScrim != null) {
            if (reminderScrim.getParent() != null) windowManager.removeView(reminderScrim);
            reminderScrim = null;
        }
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    /** 宿主结束／退出时只释放显示资源；正常 Stop 仍由原 Service 请求。 */
    void dismiss() {
        clearReminder();
        if (window != null) window.dismiss();
        window = null;
        onConfirmReminder = null;
    }
}
