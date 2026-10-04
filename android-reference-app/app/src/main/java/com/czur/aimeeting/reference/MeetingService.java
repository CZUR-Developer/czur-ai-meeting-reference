package com.czur.aimeeting.reference;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import com.czur.aimeeting.reference.meeting.CreatedMeeting;
import com.czur.aimeeting.reference.meeting.HttpReferenceMeetingClient;
import com.czur.aimeeting.reference.meeting.MeetingCapabilityState;
import com.czur.aimeeting.reference.meeting.MeetingDisplayState;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeBridge;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.meeting.MeetingTimeRule;
import com.czur.aimeeting.reference.meeting.ReferenceMeetingClient;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单场参考会议的所有者。前台 Service 持有模型会话、标准事件提交、悬浮窗和停止收口；
 * Activity 只负责发起会前请求、观察显示状态和发出停止指令。退出页面不会关闭 MIC。
 */
public final class MeetingService extends Service {
    static final String ACTION_BEGIN = "com.czur.aimeeting.reference.BEGIN";
    static final String EXTRA_BACKEND = "backend";
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_TARGET = "target";
    static final String EXTRA_PRESENTATION = "presentation";
    static final String EXTRA_RESULT_WEB = "resultWeb";
    private static final String TAG = "AiMeetingReference";
    private static final String CHANNEL = "ai_meeting_active";
    private static final int NOTIFICATION_ID = 100;

    /** UI 订阅回调只在主线程调用；重新绑定时立即收到当前整场快照。 */
    interface Observer { void onMeetingState(State state); }

    static final class State {
        final MeetingDisplayState display;
        final String status;
        final String capabilityText;
        final boolean busy;
        final boolean active;
        final boolean canStop;
        final boolean overlayShowing;
        final int transcriptVersion;
        final String resultUrl;
        final String timeText;
        final String reminderText;

        State(MeetingDisplayState display, String status, String capabilityText, boolean busy,
                boolean active, boolean canStop, boolean overlayShowing,
                int transcriptVersion, String resultUrl, String timeText, String reminderText) {
            this.display = display;
            this.status = status;
            this.capabilityText = capabilityText;
            this.busy = busy;
            this.active = active;
            this.canStop = canStop;
            this.overlayShowing = overlayShowing;
            this.transcriptVersion = transcriptVersion;
            this.resultUrl = resultUrl;
            this.timeText = timeText;
            this.reminderText = reminderText;
        }
    }

    final class LocalBinder extends Binder {
        MeetingService service() { return MeetingService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final MeetingDisplayState display = new MeetingDisplayState();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger saved = new AtomicInteger();
    private final AtomicBoolean appendFailed = new AtomicBoolean();
    private final AtomicBoolean completionQueued = new AtomicBoolean();
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
                    Log.i(TAG, "自动停止会议 meetingId=" + meetingId
                            + " cause=" + now.stopCause());
                    requestStop(now.stopCause() == MeetingTimeRule.StopCause.TIME_LIMIT
                            ? "已到本场时长上限，正在等待模型最终字幕…"
                            : "连续5分钟无转写，正在等待模型最终字幕…");
                } else {
                    publish();
                }
            }
            if (active) main.postDelayed(this, 1000L);
        }
    };
    private MeetingRuntimeBridge runtime;
    private MeetingOverlay overlay;
    private Observer observer;
    private ReferenceMeetingClient client;
    private ModelSession session;
    private MeetingTimeRule timing;
    private MeetingSetup setup;
    private String meetingId;
    private String status = "请先登录，再开始会议";
    private String capabilityText = "本场功能将在创建会议后按后端能力显示";
    private String resultUrl;
    private int transcriptVersion;
    private boolean busy;
    private boolean active;
    private boolean canStop;
    private volatile boolean stopRequested;
    private volatile boolean modelFinished;
    private boolean limitReminderLogged;
    private boolean idleReminderLogged;

    @Override public void onCreate() {
        super.onCreate();
        runtime = MeetingRuntimeFactory.create(this);
        overlay = new MeetingOverlay(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "AI 会议进行中", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_BEGIN.equals(intent.getAction())) return START_NOT_STICKY;
        if (busy) return START_NOT_STICKY;
        startForegroundForMeeting();
        MeetingSetup requested = new MeetingSetup(MeetingSetup.Mode.valueOf(
                intent.getStringExtra(EXTRA_MODE)), intent.getStringExtra(EXTRA_SOURCE),
                intent.getStringExtra(EXTRA_TARGET),
                intent.getBooleanExtra(EXTRA_PRESENTATION, false),
                MeetingSetup.ResultDelivery.WEB, intent.getStringExtra(EXTRA_RESULT_WEB));
        begin(intent.getStringExtra(EXTRA_BACKEND), intent.getStringExtra(EXTRA_TOKEN), requested);
        return START_NOT_STICKY;
    }

    private void startForegroundForMeeting() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("AI 会议进行中")
                .setContentText("点击返回会议页面")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = runtime.waitsForModelFinish()
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** 当前 Service 唯一的 UI 观察者；Activity 离开后，悬浮窗仍由 Service 更新。 */
    void observe(Observer next) {
        observer = next;
        publish();
    }

    void unobserve(Observer old) {
        if (observer == old) observer = null;
    }

    String takeResultUrl() {
        String result = resultUrl;
        resultUrl = null;
        return result;
    }

    void toggleOverlay() {
        if (!active) return;
        if (overlay.isShowing()) {
            overlay.dismiss();
            Log.i(TAG, "跨应用字幕窗已关闭");
        } else {
            overlay.show(display, this::openMeetingPage, this::swapDisplayColumns, () -> {
                if (canStop) {
                    openMeetingPage();
                    requestStop();
                }
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

    /** 页面和悬浮窗共用本场显示状态；交换仅影响呈现位置。 */
    void swapDisplayColumns() {
        if (!active || !display.isBilingual()) return;
        display.swapColumns();
        transcriptVersion++;
        Log.i(TAG, "双语分栏左右显示已交换 meetingId=" + meetingId);
        publish();
    }

    /** P6 音频 SDK 的稳定方向角输入口；P5 标准 MIC 无有效角度时不会调用它。 */
    void onStableAudioDirection(float angle) {
        display.updateStableDirection(angle);
        Log.d(TAG, "方向角样本已更新 angle=" + angle);
    }

    /** P6 可在对应片段首次显示前提交与 ASR 片段关联的方向侧。 */
    void onSegmentAudioDirection(String segmentId, int side) {
        display.setSegmentDirection(segmentId, side);
    }

    private void begin(String backend, String token, MeetingSetup requested) {
        busy = true;
        active = false;
        canStop = false;
        resultUrl = null;
        status = "正在创建会议…";
        publish();
        network.execute(() -> {
            try {
                ReferenceMeetingClient nextClient = new HttpReferenceMeetingClient(backend, token);
                CreatedMeeting created = nextClient.createMeeting(requested);
                String id = created.meetingId();
                Log.i(TAG, "会议已创建 meetingId=" + id + " mode=" + requested.mode()
                        + " sourceLanguage=" + requested.sourceLanguage());
                if (!created.capabilities().canUse(requested.mode().requiredCapability(),
                        runtime.setupCapabilities())) {
                    throw new IOException("本场后端未开放" + requested.mode().displayName());
                }
                ModelSessionGrant grant = runtime.waitsForModelFinish()
                        ? nextClient.requestModelSession(id) : null;
                if (grant != null) Log.i(TAG, "模型会话材料已领取 meetingId=" + id
                        + " adapterId=" + grant.adapterId());
                CreatedMeeting ready = created.withModelSession(grant);
                if (!ready.capabilities().canUse(requested.mode().requiredCapability(),
                        runtime.realtimeCapabilities(ready))) {
                    throw new IOException("本场模型 Adapter 未开放" + requested.mode().displayName());
                }
                nextClient.startClient(id);
                Log.i(TAG, "客户端会议已启动 meetingId=" + id);
                main.post(() -> startModelMeeting(nextClient, requested, ready));
            } catch (IOException failure) {
                Log.e(TAG, "创建或启动会议失败", failure);
                main.post(() -> finishWithStatus(failure.getMessage()));
            }
        });
    }

    private void startModelMeeting(ReferenceMeetingClient nextClient, MeetingSetup requested,
            CreatedMeeting created) {
        client = nextClient;
        setup = requested;
        meetingId = created.meetingId();
        sequence.set(0);
        saved.set(0);
        appendFailed.set(false);
        completionQueued.set(false);
        stopRequested = false;
        modelFinished = false;
        long startedAtMs = SystemClock.elapsedRealtime();
        timing = new MeetingTimeRule(startedAtMs, created.maxMeetingDurationMs());
        limitReminderLogged = false;
        idleReminderLogged = false;
        display.start(startedAtMs,
                requested.mode() == MeetingSetup.Mode.LIVE_TRANSLATION,
                requested.sourceLanguage(), requested.targetLanguage());
        transcriptVersion++;
        Set<String> local = runtime.realtimeCapabilities(created);
        MeetingCapabilityState capabilities = created.capabilities();
        capabilityText = "本场功能：实时字幕"
                + (capabilities.canUse(MeetingCapabilityState.TRANSLATION, local)
                        ? "、实时翻译" : "；实时翻译已关闭")
                + (capabilities.canUse(MeetingCapabilityState.SUMMARY, local)
                        ? "、会议纪要" : "；会议纪要已关闭");
        try {
            session = runtime.open(created, event -> {
                main.post(() -> showTranscript(event));
                int clientSequence = sequence.incrementAndGet();
                network.execute(() -> appendTranscript(nextClient, created.meetingId(),
                        clientSequence, event));
            }, new ModelSessionListener() {
                @Override public void onTranslation(TranslationEvent event) {
                    main.post(() -> showTranslation(event));
                    if (requested.mode() == MeetingSetup.Mode.LIVE_TRANSLATION) {
                        // 字幕与译文共用序号和单线程网络队列，模型完成后 Stop 排在所有事件之后。
                        int clientSequence = sequence.incrementAndGet();
                        network.execute(() -> appendTranslation(nextClient, created.meetingId(),
                                clientSequence, event));
                    }
                }

                @Override public void onFinished() {
                    modelFinished = true;
                    Log.i(TAG, "模型最终输出已接收 meetingId=" + created.meetingId());
                    if (stopRequested) scheduleComplete();
                    else main.post(() -> {
                        status = "模型已完成，可结束会议";
                        publish();
                    });
                }

                @Override public void onError(String message) {
                    Log.e(TAG, "模型会话失败 meetingId=" + created.meetingId());
                    main.post(() -> {
                        status = "模型会话失败，请检查连接与模型配置";
                        publish();
                    });
                }
            });
            session.start();
            active = true;
            canStop = runtime.waitsForModelFinish();
            status = "会议 " + meetingId + " 已开始，正在接收" + runtime.displayName() + "字幕";
            Log.i(TAG, "开始模型会话 runtime=" + runtime.displayName() + " meetingId=" + meetingId);
            main.removeCallbacks(clockTick);
            main.post(clockTick);
            publish();
        } catch (RuntimeException failure) {
            Log.e(TAG, "打开模型会话失败 meetingId=" + meetingId
                    + " errorType=" + failure.getClass().getSimpleName());
            finishWithStatus("打开模型会话失败，请检查模型接入配置");
        }
    }

    private void showTranscript(TranscriptEvent event) {
        if (timing != null && event.text() != null && !event.text().isBlank()) {
            timing.onTranscript(SystemClock.elapsedRealtime(), event.text());
            if (idleReminderLogged) {
                idleReminderLogged = false;
                Log.i(TAG, "转写恢复，无转写提醒已解除 meetingId=" + meetingId);
            }
        }
        boolean firstRoute = display.isBilingual() && display.route(event.segmentId()) == null;
        display.apply(event);
        if (firstRoute && display.route(event.segmentId()) != null)
            Log.i(TAG, "互译片段分栏 segmentId=" + event.segmentId()
                + " route=" + display.route(event.segmentId()));
        transcriptVersion++;
        Log.d(TAG, "字幕片段=" + event.segmentId() + " 修订=" + event.revision()
                + " 最终=" + event.isFinal());
        publish();
    }

    private void showTranslation(TranslationEvent event) {
        boolean firstRoute = display.isBilingual()
                && display.route(event.sourceSegmentId()) == null;
        display.apply(event);
        if (firstRoute && display.route(event.sourceSegmentId()) != null)
            Log.i(TAG, "译文先到时锁定分栏 segmentId=" + event.sourceSegmentId()
                + " route=" + display.route(event.sourceSegmentId()));
        transcriptVersion++;
        Log.d(TAG, "译文片段=" + event.translationId() + " 原文片段="
                + event.sourceSegmentId() + " 修订=" + event.revision()
                + " 最终=" + event.isFinal());
        publish();
    }

    private void appendTranscript(ReferenceMeetingClient nextClient, String id,
            int clientSequence, TranscriptEvent event) {
        try {
            nextClient.appendTranscript(id, clientSequence, event);
            int count = saved.incrementAndGet();
            Log.i(TAG, "标准字幕事件已保存 meetingId=" + id + " clientSequence="
                    + clientSequence + " segmentId=" + event.segmentId()
                    + " revision=" + event.revision());
            main.post(() -> {
                status = "字幕已送达后端：" + count;
                if (!runtime.waitsForModelFinish() && event.isFinal() && count == 3) {
                    canStop = true;
                }
                publish();
            });
        } catch (IOException failure) {
            appendFailed.set(true);
            Log.e(TAG, "字幕事件提交失败 meetingId=" + id
                    + " clientSequence=" + clientSequence, failure);
            main.post(() -> {
                status = "字幕提交失败：" + failure.getMessage();
                publish();
            });
        }
    }

    private void appendTranslation(ReferenceMeetingClient nextClient, String id,
            int clientSequence, TranslationEvent event) {
        try {
            nextClient.appendTranslation(id, clientSequence, event);
            Log.i(TAG, "标准译文事件已保存 meetingId=" + id + " clientSequence="
                    + clientSequence + " translationId=" + event.translationId()
                    + " revision=" + event.revision());
        } catch (IOException failure) {
            appendFailed.set(true);
            Log.e(TAG, "译文事件提交失败 meetingId=" + id
                    + " clientSequence=" + clientSequence, failure);
            main.post(() -> {
                status = "译文提交失败：" + failure.getMessage();
                publish();
            });
        }
    }

    /** 页面和悬浮窗使用同一停止入口，Service 决定模型与后端收口顺序。 */
    void requestStop() {
        requestStop("正在等待模型最终字幕…");
    }

    /** 自动停止和用户点击停止都进入同一模型完成、事件提交、后端 Stop 顺序。 */
    private void requestStop(String progressText) {
        if (!active || !canStop) return;
        stopRequested = true;
        canStop = false;
        status = progressText;
        publish();
        network.execute(() -> {
            try {
                session.stopInput();
                if (!runtime.waitsForModelFinish() || modelFinished) scheduleComplete();
            } catch (RuntimeException failure) {
                Log.e(TAG, "停止模型输入失败 meetingId=" + meetingId
                        + " errorType=" + failure.getClass().getSimpleName());
                main.post(() -> {
                    status = "停止模型输入失败";
                    canStop = true;
                    publish();
                });
            }
        });
    }

    private void scheduleComplete() {
        if (completionQueued.compareAndSet(false, true)) {
            // 同一单线程队列先提交模型完成前收到的字幕与译文，再请求后端 Stop。
            network.execute(this::completeMeeting);
        }
    }

    private void completeMeeting() {
        if (appendFailed.get()) {
            main.post(() -> {
                status = "标准事件提交失败，无法完成会议";
                publish();
            });
            return;
        }
        try {
            if (!client.stopMeeting(meetingId)) {
                completionQueued.set(false);
                main.post(() -> {
                    status = "后端仍在收口，请稍后再点停止";
                    canStop = true;
                    publish();
                });
                return;
            }
            session.close();
            String finishedUrl = Uri.parse(setup.resultWebUrl()).buildUpon()
                    .appendQueryParameter("meetingId", meetingId)
                    .appendQueryParameter("genTime", Long.toString(System.currentTimeMillis()))
                    .build().toString();
            Log.i(TAG, "会议结束，结果已就绪 meetingId=" + meetingId);
            main.post(() -> {
                resultUrl = finishedUrl;
                finishWithStatus("会议已结束，可查看结果页");
            });
        } catch (IOException failure) {
            completionQueued.set(false);
            Log.e(TAG, "结束会议失败 meetingId=" + meetingId, failure);
            main.post(() -> {
                status = "结束会议失败：" + failure.getMessage();
                canStop = true;
                publish();
            });
        }
    }

    private void finishWithStatus(String message) {
        active = false;
        busy = false;
        canStop = false;
        status = message;
        main.removeCallbacks(clockTick);
        overlay.dismiss();
        if (session != null) {
            session.close();
            session = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        publish();
    }

    private void publish() {
        MeetingTimeRule.Snapshot clock = timing == null ? null
                : timing.at(SystemClock.elapsedRealtime());
        String timeText = clock == null ? "" : "已进行 " + MeetingTimeRule.format(clock.elapsedMs())
                + " · 剩余 " + MeetingTimeRule.format(clock.remainingMs());
        String reminderText = "";
        if (clock != null) {
            if (clock.limitWarning()) reminderText = "本场会议剩余不足20分钟";
            if (clock.noTranscriptWarning()) {
                if (!reminderText.isEmpty()) reminderText += "\n";
                reminderText += "连续4分钟无转写，距自动结束还剩 "
                        + MeetingTimeRule.format(clock.noTranscriptRemainingMs());
            }
        }
        State state = new State(display, status, capabilityText, busy, active, canStop,
                overlay.isShowing(), transcriptVersion, resultUrl, timeText, reminderText);
        overlay.render(display, timeText, reminderText, canStop);
        if (observer != null) observer.onMeetingState(state);
    }

    @Override public void onDestroy() {
        main.removeCallbacks(clockTick);
        overlay.dismiss();
        if (session != null) session.close();
        network.shutdownNow();
        super.onDestroy();
    }
}
