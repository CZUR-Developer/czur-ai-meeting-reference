package com.czur.aimeeting.reference;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.czur.aimeeting.client.MeetingCapabilityState;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingListener;
import com.czur.aimeeting.client.MeetingResult;
import com.czur.aimeeting.client.MeetingSession;
import com.czur.aimeeting.client.MeetingSessionState;
import com.czur.aimeeting.client.MeetingStarted;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.meeting.MeetingTimeRule;
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单场参考会议的平台和 UI 所有者。完整 SDK 持有模型、音频、显示规则和 Server 流程；
 * Service 仅接收类型化快照，管理前台通知、悬浮窗、计时、屏采与本地 PDF。
 * Activity 离开只解除观察，持续会议由 Service 持有。所有业务回调使用主线程串行队列。
 */
public final class MeetingService extends Service {
    static final String ACTION_BEGIN = "com.czur.aimeeting.reference.BEGIN";
    static final String ACTION_DEBUG_STOP = "com.czur.aimeeting.reference.DEBUG_STOP";
    static final String EXTRA_MODE = "mode";
    /** 字幕／互译模式之外独立携带总结选择，旧纪要模式的Intent仍默认开启。 */
    static final String EXTRA_SUMMARY = "summaryEnabled";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_TARGET = "target";
    static final String EXTRA_PRESENTATION = "presentation";
    static final String EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode";
    static final String EXTRA_PROJECTION_DATA = "projectionData";
    static final String EXTRA_RESULT_WEB = "resultWeb";
    static final String EXTRA_RESULT_DELIVERY = "resultDelivery";
    static final String EXTRA_RESULT_EMAILS = "resultEmails";
    private static final String TAG = "AiMeetingReference";
    private static final String CHANNEL = "ai_meeting_active";
    private static final int NOTIFICATION_ID = 100;
    private static final long PRESENTATION_INTERVAL_MS = 10_000L;

    /** UI 订阅回调只在主线程调用；重新绑定时立即收到当前整场不可变显示快照。 */
    interface Observer { void onMeetingState(State state); }

    static final class State {
        final MeetingDisplaySnapshot display;
        final String status;
        final String capabilityText;
        final boolean busy;
        final boolean active;
        final boolean canStop;
        final boolean overlayShowing;
        final int transcriptVersion;
        final String resultUrl;
        final LocalPdfWriter.SavedPdf resultPdf;
        final String timeText;
        final MeetingReminderPresentation.Notice reminder;
        final MeetingSetup.Mode mode;
        final boolean summaryEnabled;

        State(MeetingDisplaySnapshot display, String status, String capabilityText, boolean busy,
                boolean active, boolean canStop, boolean overlayShowing,
                int transcriptVersion, String resultUrl, LocalPdfWriter.SavedPdf resultPdf,
                String timeText, MeetingReminderPresentation.Notice reminder, MeetingSetup.Mode mode,
                boolean summaryEnabled) {
            this.display = display;
            this.status = status;
            this.capabilityText = capabilityText;
            this.busy = busy;
            this.active = active;
            this.canStop = canStop;
            this.overlayShowing = overlayShowing;
            this.transcriptVersion = transcriptVersion;
            this.resultUrl = resultUrl;
            this.resultPdf = resultPdf;
            this.timeText = timeText;
            this.reminder = reminder;
            this.mode = mode;
            this.summaryEnabled = summaryEnabled;
        }
    }

    final class LocalBinder extends Binder {
        MeetingService service() { return MeetingService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver debugStopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_DEBUG_STOP.equals(intent.getAction())) return;
            // 注册时不指定后台调度器：主线程直接复用页面的正常 Stop 和 canStop 门控。
            Log.d(TAG, "收到调试正常停止命令 active=" + active + " canStop=" + canStop);
            requestStop();
        }
    };
    /** 只运行 JPEG/PDF 平台工作，不承载 HTTP、模型、事件序号或 Stop 编排。 */
    private final ExecutorService platformWork = Executors.newSingleThreadExecutor();
    private MeetingDisplaySnapshot display =
            new MeetingDisplaySnapshot(false, "zh-CN", "en-US", false, List.of(), null);
    private MeetingReminderPresentation reminderPresentation = new MeetingReminderPresentation();
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (active && timing != null) {
                MeetingTimeRule.Snapshot now = timing.at(SystemClock.elapsedRealtime());
                if (now.limitWarning() && !limitReminderLogged) {
                    limitReminderLogged = true;
                    Log.i(TAG, "本场剩余20分钟提醒 meetingId=" + meetingId);
                }
                if (now.noTranscriptWarning() && !idleReminderLogged) {
                    idleReminderLogged = true;
                    Log.i(TAG, "连续4分钟无转写提醒 meetingId=" + meetingId);
                }
                if (canStop && now.stopCause() != MeetingTimeRule.StopCause.NONE) {
                    Log.i(TAG, "自动停止会议 meetingId=" + meetingId + " cause=" + now.stopCause());
                    requestStop(now.stopCause() == MeetingTimeRule.StopCause.TIME_LIMIT
                            ? "已到本场时长上限，正在等待模型最终字幕…"
                            : "连续5分钟无转写，正在等待模型最终字幕…");
                } else publish();
            }
            if (active) main.postDelayed(this, 1000L);
        }
    };
    private final Runnable presentationTick = new Runnable() {
        @Override public void run() {
            if (!active || stopRequested || presentation == null) return;
            platformWork.execute(MeetingService.this::captureAndSubmit);
            main.postDelayed(this, PRESENTATION_INTERVAL_MS);
        }
    };
    private MeetingOverlay overlay;
    private Observer observer;
    private MeetingClient client;
    private MeetingSession session;
    private MeetingTimeRule timing;
    private volatile PresentationCapture presentation;
    private MeetingSetup setup;
    private String meetingId;
    private String status = "请先登录，再开始会议";
    private String capabilityText = "本场功能将在创建会议后按后端能力显示";
    private AudioInputMode audioInputMode;
    private String audioErrorText;
    private String resultUrl;
    private LocalPdfWriter.SavedPdf resultPdf;
    private int transcriptVersion;
    private boolean busy;
    private boolean active;
    private boolean canStop;
    private boolean stopRequested;
    /** onResult 先于 READY，PDF 写入完成前不能停止 Service 或关闭平台工作队列。 */
    private boolean localResultPending;
    private boolean limitReminderLogged;
    private boolean idleReminderLogged;
    private boolean debugStopReceiverRegistered;
    private Intent projectionData;
    private int projectionResultCode;

    @Override public void onCreate() {
        super.onCreate();
        overlay = new MeetingOverlay(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "AI 会议进行中", NotificationManager.IMPORTANCE_LOW));
        // 本 App 未生成 BuildConfig；只在可调试 APK 的 Service 存活期间开放调试命令。
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            IntentFilter filter = new IntentFilter(ACTION_DEBUG_STOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // adb shell 来自应用进程外；Android 13+ 需要显式允许外部广播。
                registerReceiver(debugStopReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(debugStopReceiver, filter);
            }
            debugStopReceiverRegistered = true;
            Log.d(TAG, "调试正常停止命令已注册");
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_BEGIN.equals(intent.getAction()) || busy)
            return START_NOT_STICKY;
        String[] emails = intent.getStringArrayExtra(EXTRA_RESULT_EMAILS);
        MeetingSetup.Mode mode = MeetingSetup.Mode.valueOf(intent.getStringExtra(EXTRA_MODE));
        MeetingSetup requested = new MeetingSetup(mode, intent.getStringExtra(EXTRA_SOURCE),
                intent.getStringExtra(EXTRA_TARGET), intent.getBooleanExtra(EXTRA_PRESENTATION, false),
                MeetingSetup.ResultDelivery.valueOf(intent.getStringExtra(EXTRA_RESULT_DELIVERY)),
                intent.getStringExtra(EXTRA_RESULT_WEB),
                emails == null ? List.of() : java.util.Arrays.asList(emails),
                intent.getBooleanExtra(EXTRA_SUMMARY, mode == MeetingSetup.Mode.MEETING_MINUTES));
        if (requested.presentationCapture()) {
            projectionData = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class)
                    : intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            projectionResultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0);
            if (projectionData == null) {
                status = "缺少本场系统屏幕采集授权，会议未创建";
                publish();
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        startForegroundForMeeting(requested.presentationCapture());
        begin(requested);
        return START_NOT_STICKY;
    }

    private void startForegroundForMeeting(boolean withPresentation) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("AI 会议进行中").setContentText("点击返回会议页面")
                .setContentIntent(pending).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = MeetingRuntimeFactory.requiresMicrophone()
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (withPresentation) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIFICATION_ID, notification, type);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    void observe(Observer next) { observer = next; publish(); }
    void unobserve(Observer old) { if (observer == old) { observer = null; publish(); } }
    void confirmReminder() { reminderPresentation.confirm(); publish(); }

    String takeResultUrl() {
        String result = resultUrl;
        resultUrl = null;
        return result;
    }

    LocalPdfWriter.SavedPdf takeResultPdf() {
        LocalPdfWriter.SavedPdf result = resultPdf;
        resultPdf = null;
        return result;
    }

    void toggleOverlay() {
        if (!active) return;
        if (overlay.isShowing()) {
            overlay.dismiss();
            Log.i(TAG, "跨应用字幕窗已关闭");
        } else {
            overlay.show(display, this::openMeetingPage, this::swapDisplayColumns,
                    this::confirmReminder, () -> {
                        if (canStop) { openMeetingPage(); requestStop(); }
                    });
            Log.i(TAG, "跨应用字幕窗已显示");
        }
        publish();
    }

    private void openMeetingPage() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    /** 交换由组合会话更新同一份快照；页面和悬浮窗不各自维护交换状态。 */
    void swapDisplayColumns() {
        if (!active || !display.bilingual() || session == null) return;
        session.swapDisplayColumns();
        Log.i(TAG, "双语分栏左右交换已请求 meetingId=" + meetingId);
    }

    private void begin(MeetingSetup requested) {
        setup = requested;
        busy = true;
        active = false;
        canStop = false;
        stopRequested = false;
        localResultPending = false;
        resultUrl = null;
        resultPdf = null;
        timing = null;
        meetingId = null;
        audioInputMode = null;
        audioErrorText = null;
        display = new MeetingDisplaySnapshot(
                requested.mode() == MeetingSetup.Mode.LIVE_TRANSLATION,
                requested.sourceLanguage(), requested.targetLanguage(), false, List.of(), null);
        transcriptVersion++;
        status = "正在创建会议…";
        capabilityText = "本场功能将在创建会议后按后端能力显示";
        publish();
        try {
            MeetingClientConfig config = MeetingAppAssembly.clientConfig(command -> main.post(command));
            client = MeetingRuntimeFactory.create(this, config);
            session = client.start(requested.toOptions(), new MeetingListener() {
                @Override public void onStarted(MeetingStarted started) {
                    meetingId = started.meetingId();
                    timing = new MeetingTimeRule(started.startedAtMs(), started.maxMeetingDurationMs());
                    reminderPresentation = new MeetingReminderPresentation();
                    limitReminderLogged = false;
                    idleReminderLogged = false;
                    capabilityText = describeCapabilities(started.availableCapabilities());
                    if (requested.presentationCapture()) startPresentation(started.startedAtMs());
                    main.removeCallbacks(clockTick);
                    main.post(clockTick);
                    Log.i(TAG, "组合会议已启动 meetingId=" + meetingId + " mode=" + requested.mode());
                }

                @Override public void onState(MeetingSessionState next) {
                    // 本地 PDF 已在 onResult 排到平台队列；READY 不关闭队列，也不覆盖保存提示。
                    if (next.phase() == MeetingSessionState.Phase.READY && localResultPending) {
                        active = false;
                        canStop = false;
                        main.removeCallbacks(clockTick);
                        overlay.dismiss();
                        publish();
                        return;
                    }
                    active = next.active();
                    canStop = next.canStop();
                    status = next.message();
                    Log.d(TAG, "组合会议状态 phase=" + next.phase()
                            + " savedEventCount=" + next.savedEventCount());
                    switch (next.phase()) {
                        case FAILED, ENDED, CLOSED -> finishWithStatus(next.message());
                        case READY -> { /* 正常交付由先到的 onResult 完成，保留对应提示。 */ }
                        default -> publish();
                    }
                }

                @Override public void onDisplay(MeetingDisplaySnapshot next) {
                    display = next;
                    transcriptVersion++;
                    Log.d(TAG, "已路由显示快照 rows=" + next.rows().size()
                            + " swapped=" + next.swapped());
                    publish();
                }

                @Override public void onTranscriptActivity(long nowMs) {
                    if (timing == null) return;
                    timing.onTranscriptActivity(nowMs);
                    if (idleReminderLogged) {
                        idleReminderLogged = false;
                        Log.i(TAG, "转写恢复，无转写提醒已解除 meetingId=" + meetingId);
                    }
                }

                @Override public void onAudioInputMode(AudioInputMode mode) {
                    audioInputMode = mode;
                    Log.i(TAG, "本场实际音频来源 mode=" + mode + " meetingId=" + meetingId);
                    publish();
                }

                @Override public void onAudioError(AudioInputErrorCode code, String message) {
                    audioErrorText = code == AudioInputErrorCode.PERMISSION_DENIED
                            ? "没有麦克风权限，请在应用中授予权限后重新开始会议"
                            : "音频输入失败：" + message;
                    Log.w(TAG, "音频输入失败 code=" + code + " meetingId=" + meetingId);
                    // 后台 Service 只呈现错误，权限申请仍由 Activity 发起。
                    publish();
                }

                @Override public void beforeStop(Runnable continuation) {
                    main.removeCallbacks(presentationTick);
                    // 和周期截图共用一条平台队列：先做已排任务及最后有效帧，再续接 SDK 收口。
                    platformWork.execute(() -> {
                        try { captureAndSubmit(); }
                        finally {
                            try { closePresentation(); }
                            finally { continuation.run(); }
                        }
                    });
                }

                @Override public void onResult(MeetingResult result) { deliverResult(result); }
            });
        } catch (RuntimeException failure) {
            Log.e(TAG, "打开组合会议失败 errorType=" + failure.getClass().getSimpleName());
            finishWithStatus("打开会议失败，请检查接入配置");
        }
    }

    private static String describeCapabilities(Set<String> available) {
        return "本场功能：实时字幕"
                + (available.contains(MeetingCapabilityState.TRANSLATION)
                        ? "、实时翻译" : "；实时翻译已关闭")
                + (available.contains(MeetingCapabilityState.SPEAKER)
                        ? "、本场发言人" : "；本场发言人已关闭")
                + (available.contains(MeetingCapabilityState.SUMMARY)
                        ? "、会议纪要" : "；会议纪要已关闭");
    }

    private void startPresentation(long startedAtMs) {
        try {
            presentation = new PresentationCapture(this, projectionResultCode,
                    projectionData, startedAtMs, this::onPresentationStopped);
            main.postDelayed(presentationTick, PRESENTATION_INTERVAL_MS);
            Log.i(TAG, "演示文稿屏幕采集已开始 meetingId=" + meetingId);
        } catch (RuntimeException failure) {
            Log.e(TAG, "演示文稿屏幕采集启动失败 errorType=" + failure.getClass().getSimpleName());
            status = "屏幕采集启动失败；字幕会议继续，可停止后重试";
        } finally { projectionData = null; }
    }

    private void captureAndSubmit() {
        PresentationCapture current = presentation;
        MeetingSession currentSession = session;
        if (current == null || currentSession == null) return;
        try {
            PresentationCapture.Frame frame = current.capture(SystemClock.elapsedRealtime());
            if (frame == null) return;
            currentSession.submitPresentation(frame.offsetMs(), frame.jpeg());
            Log.i(TAG, "演示截图已提交组合会话 offsetMs=" + frame.offsetMs());
        } catch (IOException | RuntimeException failure) {
            Log.e(TAG, "演示截图采集失败 errorType=" + failure.getClass().getSimpleName());
            main.post(() -> {
                main.removeCallbacks(presentationTick);
                status = "演示截图采集失败；字幕会议继续";
                publish();
            });
        }
    }

    private void onPresentationStopped() {
        presentation = null;
        main.removeCallbacks(presentationTick);
        if (active && !stopRequested) {
            status = "系统已结束屏幕共享，字幕会议继续";
            publish();
        }
    }

    private void closePresentation() {
        PresentationCapture current = presentation;
        presentation = null;
        if (current != null) current.close();
    }

    /** 自动停止、页面和悬浮窗统一调用组合会话，不在 App 编排模型/Server 顺序。 */
    void requestStop() { requestStop("正在等待模型最终字幕…"); }

    private void requestStop(String progressText) {
        if (!active || !canStop || session == null) return;
        stopRequested = true;
        canStop = false;
        status = progressText;
        publish();
        session.requestStop();
    }

    private void deliverResult(MeetingResult result) {
        Log.i(TAG, "组合会议结果已就绪 delivery=" + result.deliveryStatus()
                + " meetingId=" + result.meetingId());
        switch (result.deliveryStatus()) {
            case LOCAL_READY -> {
                localResultPending = true;
                active = false;
                canStop = false;
                main.removeCallbacks(clockTick);
                overlay.dismiss();
                status = "会议已结束，正在保存本地 PDF…";
                publish();
                platformWork.execute(() -> {
                    try {
                        LocalPdfWriter.SavedPdf savedPdf =
                                new LocalPdfWriter(this).write(result.meetingId(), result);
                        LocalResultActivity.remember(this, savedPdf, savedPdf.uri() != null);
                        main.post(() -> {
                            localResultPending = false;
                            resultPdf = savedPdf;
                            finishWithStatus(savedPdf.uri() == null
                                    ? "会议已结束，PDF 已生成，请选择公共下载位置"
                                    : "会议已结束，PDF 已保存到 下载/AI会议纪要");
                        });
                    } catch (IOException | RuntimeException failure) {
                        Log.e(TAG, "本地 PDF 生成失败 errorType=" + failure.getClass().getSimpleName());
                        main.post(() -> {
                            localResultPending = false;
                            finishWithStatus("会议已结束，但本地 PDF 保存失败");
                        });
                    }
                });
            }
            case EMAIL_SENT -> finishWithStatus("会议已结束，结果链接已发送到邮箱");
            case EMAIL_FAILED -> {
                resultUrl = result.resultUrl();
                finishWithStatus("会议已结束，邮件发送失败，可先查看网页结果");
            }
            case WEB_AVAILABLE -> {
                resultUrl = result.resultUrl();
                finishWithStatus("会议已结束，可查看结果页");
            }
            case NOT_REQUESTED -> finishWithStatus("会议已结束");
        }
    }

    private void finishWithStatus(String message) {
        active = false;
        busy = false;
        canStop = false;
        status = message;
        main.removeCallbacks(clockTick);
        main.removeCallbacks(presentationTick);
        closePresentation();
        projectionData = null;
        overlay.dismiss();
        if (client != null) { client.close(); client = null; }
        session = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        publish();
    }

    private void publish() {
        MeetingTimeRule.Snapshot clock = timing == null ? null
                : timing.at(SystemClock.elapsedRealtime());
        String timeText = clock == null ? "" : "已进行 " + MeetingTimeRule.format(clock.elapsedMs())
                + " · 剩余 " + MeetingTimeRule.format(clock.remainingMs());
        MeetingReminderPresentation.Notice reminder = active && clock != null
                ? reminderPresentation.at(clock) : MeetingReminderPresentation.Notice.NONE;
        String audioText = audioInputMode == null ? "" : "\n音频来源：" + audioInputMode;
        if (audioErrorText != null) audioText += "\n" + audioErrorText;
        State state = new State(display, status, capabilityText + audioText, busy, active, canStop,
                overlay.isShowing(), transcriptVersion, resultUrl, resultPdf, timeText, reminder,
                setup == null ? MeetingSetup.Mode.LIVE_TRANSCRIPT : setup.mode(),
                setup != null && setup.summaryEnabled());
        overlay.render(display, state.mode, state.summaryEnabled, timeText, reminder, canStop, observer == null);
        if (observer != null) observer.onMeetingState(state);
    }

    @Override public void onDestroy() {
        if (debugStopReceiverRegistered) {
            unregisterReceiver(debugStopReceiver);
            debugStopReceiverRegistered = false;
            Log.d(TAG, "调试正常停止命令已注销");
        }
        main.removeCallbacks(clockTick);
        main.removeCallbacks(presentationTick);
        closePresentation();
        overlay.dismiss();
        if (client != null) client.close();
        platformWork.shutdownNow();
        super.onDestroy();
    }
}
