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
import com.czur.aimeeting.client.result.ResultActionContext;
import com.czur.aimeeting.client.result.ResultActionOutcome;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;
import com.czur.aimeeting.model.AudioInputLevel;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.meeting.MeetingTimeRule;
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单场参考会议的平台和 UI 所有者。完整 SDK 持有模型、音频、显示规则和 Server 流程；
 * Service仅接收类型化快照，管理前台通知、悬浮窗、计时、屏采与READY后的通用动作。
 * Activity 离开只解除观察，持续会议由 Service 持有。所有业务回调使用主线程串行队列。
 */
public final class MeetingService extends Service {
    static final String ACTION_BEGIN = "com.czur.aimeeting.reference.BEGIN";
    static final String ACTION_DEBUG_STOP = "com.czur.aimeeting.reference.DEBUG_STOP";
    static final String EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode";
    static final String EXTRA_PROJECTION_DATA = "projectionData";
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
        final MeetingResult readyResult;
        final ResultActionOutcome actionOutcome;
        final boolean actionRunning;
        final String timeText;
        final MeetingReminderPresentation.Notice reminder;
        final MeetingSetup.Mode mode;
        final boolean summaryEnabled;
        /** 同场最近一次实际输入电平；尚未实采或停止后为 null，显示方据此复位为 0。 */
        final AudioInputLevel audioLevel;

        State(MeetingDisplaySnapshot display, String status, String capabilityText, boolean busy,
                boolean active, boolean canStop, boolean overlayShowing,
                int transcriptVersion, MeetingResult readyResult, ResultActionOutcome actionOutcome,
                boolean actionRunning,
                String timeText, MeetingReminderPresentation.Notice reminder, MeetingSetup.Mode mode,
                boolean summaryEnabled, AudioInputLevel audioLevel) {
            this.display = display;
            this.status = status;
            this.capabilityText = capabilityText;
            this.busy = busy;
            this.active = active;
            this.canStop = canStop;
            this.overlayShowing = overlayShowing;
            this.transcriptVersion = transcriptVersion;
            this.readyResult = readyResult;
            this.actionOutcome = actionOutcome;
            this.actionRunning = actionRunning;
            this.timeText = timeText;
            this.reminder = reminder;
            this.mode = mode;
            this.summaryEnabled = summaryEnabled;
            this.audioLevel = audioLevel;
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
    private MeetingTimeRule.Snapshot stoppedClock;
    private volatile PresentationCapture presentation;
    private MeetingSetup setup;
    private String meetingId;
    private String status = "请先登录，再开始会议";
    private String capabilityText = "本场功能将在创建会议后按后端能力显示";
    private AudioInputMode audioInputMode;
    private String audioErrorText;
    private AudioInputLevel audioLevel;
    private MeetingAppAssembly.PreparedMeeting preparedMeeting;
    private MeetingResult readyResult;
    private ResultActionOutcome actionOutcome;
    private boolean actionRunning;
    /** 通用UI操作只在页面可呈现时消费；离开／重新绑定保留同一待执行操作。 */
    private final ArrayDeque<Runnable> pendingUi = new ArrayDeque<>();
    private int transcriptVersion;
    private boolean busy;
    private boolean active;
    private boolean canStop;
    private boolean stopRequested;
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
        MeetingAppAssembly.PreparedMeeting prepared = MeetingAppAssembly.consumePreparedMeeting();
        if (prepared == null) {
            status = "缺少本场会前准备，会议未创建";
            publish();
            stopSelf();
            return START_NOT_STICKY;
        }
        MeetingSetup requested = prepared.setup();
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
        begin(prepared);
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

    /** Activity在主线程逐项消费；Service不识别插件页面或通知方式。 */
    Runnable takeUiOperation() { return pendingUi.poll(); }

    private void queueUiOperation(Runnable command) {
        main.post(() -> {
            if (command instanceof AppResultActionPlugin.UiOperation) {
                pendingUi.add(command);
                publish();
            } else command.run();
        });
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

    private void begin(MeetingAppAssembly.PreparedMeeting prepared) {
        releaseResultAccess();
        MeetingSetup requested = prepared.setup();
        preparedMeeting = prepared;
        setup = requested;
        busy = true;
        active = false;
        canStop = false;
        stopRequested = false;
        readyResult = null;
        actionOutcome = null;
        actionRunning = false;
        timing = null;
        stoppedClock = null;
        meetingId = null;
        audioInputMode = null;
        audioErrorText = null;
        audioLevel = null;
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
                    active = next.active();
                    canStop = next.canStop();
                    if (next.phase() != MeetingSessionState.Phase.RUNNING) audioLevel = null;
                    status = next.message();
                    Log.d(TAG, "组合会议状态 phase=" + next.phase()
                            + " savedEventCount=" + next.savedEventCount());
                    switch (next.phase()) {
                        case FAILED, ENDED, CLOSED -> finishWithStatus(next.message());
                        case READY -> resultReady();
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

                @Override public void onAudioLevel(AudioInputLevel level) {
                    // 页面、窗口共用此值；Stop 点击即关门，忽略主线程队列中的在途电平。
                    if (!active || stopRequested || audioErrorText != null) return;
                    audioLevel = level;
                    Log.d(TAG, "同场输入电平 level=" + level.level()
                            + " sampledAtMs=" + level.sampledAtElapsedRealtimeMs());
                    publish();
                }

                @Override public void onAudioError(AudioInputErrorCode code, String message) {
                    audioLevel = null;
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

                @Override public void onResult(MeetingResult result) {
                    // onResult先于READY状态：只存事实，不能提前关闭client或跳转页面。
                    readyResult = result;
                    Log.i(TAG, "已接收本场READY清单 meetingId=" + result.meetingId());
                }
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
        audioLevel = null;
        canStop = false;
        status = progressText;
        publish();
        session.requestStop();
    }

    /** 真实READY通知后才派发，先停止所有会议UI资源，不提前释放结果协议访问。 */
    private void resultReady() {
        stopMeetingUi();
        status = "本场结果已就绪";
        publish();
        executeResultAction();
    }

    /** 普通失败由用户手动复用同一READY／genTime／准备闭包，不重新开会或Stop。 */
    void retryResultAction() {
        if (actionOutcome != null && actionOutcome.status() == ResultActionOutcome.Status.FAILED)
            executeResultAction();
    }

    private void executeResultAction() {
        if (actionRunning || readyResult == null || session == null || preparedMeeting == null
                || preparedMeeting.action() == null) return;
        actionRunning = true;
        busy = true;
        actionOutcome = null;
        status = "本场结果已就绪，正在执行结果动作…";
        publish();
        var context = new ResultActionContext(readyResult, session.resultActionAccess(),
                preparedMeeting.serviceCapabilities(), platformWork, this::queueUiOperation);
        MeetingAppAssembly.resultRegistry().execute(preparedMeeting.action(), context, new MeetingClient.Callback<>() {
            @Override public void onSuccess(ResultActionOutcome outcome) {
                actionRunning = false;
                busy = false;
                actionOutcome = outcome;
                status = "本场结果已就绪；" + outcome.message();
                if (outcome.status() == ResultActionOutcome.Status.FAILED)
                    Log.w(TAG, "结果动作失败，会议READY保持 id=" + outcome.actionId());
                if (outcome.status() == ResultActionOutcome.Status.SUCCEEDED) {
                    releaseResultAccess();
                    stopSelf();
                }
                publish();
            }
            @Override public void onError(String message) {
                onSuccess(ResultActionOutcome.failure(preparedMeeting.action().id(), message));
            }
        });
    }

    /** 用户放弃失败动作或开启下一场前关闭旧访问；不触发后端Stop。 */
    void endResultInteraction() {
        if (readyResult == null || actionRunning) return;
        releaseResultAccess();
        actionOutcome = null;
        busy = false;
        status = "本场结果已就绪，结果交互已结束";
        stopSelf();
        publish();
    }

    private void releaseResultAccess() {
        if (client != null) { client.close(); client = null; }
        session = null;
        pendingUi.clear();
    }

    private void stopMeetingUi() {
        audioLevel = null;
        if (timing != null && stoppedClock == null) stoppedClock = timing.at(SystemClock.elapsedRealtime());
        active = false;
        canStop = false;
        main.removeCallbacks(clockTick);
        main.removeCallbacks(presentationTick);
        closePresentation();
        projectionData = null;
        overlay.dismiss();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void finishWithStatus(String message) {
        stopMeetingUi();
        busy = false;
        canStop = false;
        status = message;
        releaseResultAccess();
        stopSelf();
        publish();
    }

    private void publish() {
        MeetingTimeRule.Snapshot clock = stoppedClock != null ? stoppedClock : timing == null ? null
                : timing.at(SystemClock.elapsedRealtime());
        String timeText = clock == null ? "" : "已进行 " + MeetingTimeRule.format(clock.elapsedMs())
                + " · 剩余 " + MeetingTimeRule.format(clock.remainingMs());
        MeetingReminderPresentation.Notice reminder = active && clock != null
                ? reminderPresentation.at(clock) : MeetingReminderPresentation.Notice.NONE;
        String audioText = audioInputMode == null ? "" : "\n音频来源：" + audioInputMode;
        if (audioErrorText != null) audioText += "\n" + audioErrorText;
        State state = new State(display, status, capabilityText + audioText, busy, active, canStop,
                overlay.isShowing(), transcriptVersion, readyResult, actionOutcome, actionRunning, timeText, reminder,
                setup == null ? MeetingSetup.Mode.LIVE_TRANSCRIPT : setup.mode(),
                setup != null && setup.summaryEnabled(), audioLevel);
        overlay.render(display, state.mode, state.summaryEnabled, timeText, reminder, canStop,
                observer == null, audioLevel == null ? 0 : audioLevel.level());
        if (observer != null) observer.onMeetingState(state);
    }

    @Override public void onDestroy() {
        audioLevel = null;
        if (debugStopReceiverRegistered) {
            unregisterReceiver(debugStopReceiver);
            debugStopReceiverRegistered = false;
            Log.d(TAG, "调试正常停止命令已注销");
        }
        main.removeCallbacks(clockTick);
        main.removeCallbacks(presentationTick);
        closePresentation();
        overlay.dismiss();
        releaseResultAccess();
        platformWork.shutdownNow();
        super.onDestroy();
    }
}
