package com.czur.aimeeting.reference

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.czur.aimeeting.client.*
import com.czur.aimeeting.client.result.ResultActionContext
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.AudioInputMode
import com.czur.aimeeting.model.AudioInputLevel
import com.czur.aimeeting.reference.meeting.*
import java.util.concurrent.Executors

/**
 * 单场参考会议的平台与 UI 所有者。SDK 持有模型、音源、显示规则和服务端流程；
 * 此 Service 只消费类型快照，管理通知、窗口、计时、截图及 READY 后的通用动作。
 * Activity 离开仅解除观察，所有会议回调仍走主线程串行队列。
 */
class MeetingService : Service() {
    fun interface Observer {
        fun onMeetingState(state: State)
        /** 主线程逐帧读取当前页面窗口；离开页面解除观察后，不保留旧句柄。 */
        fun captureWindowTokens(): Array<IBinder?> = emptyArray()
    }
    class State(
        @JvmField val display: MeetingDisplaySnapshot, @JvmField val status: String,
        @JvmField val capabilityText: String, @JvmField val busy: Boolean, @JvmField val active: Boolean,
        @JvmField val canStop: Boolean, @JvmField val overlayShowing: Boolean, @JvmField val transcriptVersion: Int,
        @JvmField val readyResult: MeetingResult?, @JvmField val actionOutcome: ResultActionOutcome?,
        @JvmField val actionRunning: Boolean, @JvmField val timeText: String,
        @JvmField val reminder: MeetingReminderPresentation.Notice, @JvmField val mode: MeetingSetup.Mode,
        @JvmField val summaryEnabled: Boolean, @JvmField val audioLevel: AudioInputLevel?,
        @JvmField val presentationNotice: String,
        @JvmField val capturingPresentation: Boolean = false
    )
    inner class LocalBinder : Binder() { fun service() = this@MeetingService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val debugStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_DEBUG_OVERLAY) {
                // 标准 uiautomator 可能只返回底层 Activity。只读可见按钮坐标供脚本
                // 发真实触摸，不绕过点击处理，也不暴露字幕、凭据或模型内部数据。
                val controls = org.json.JSONObject()
                overlay.controlBounds().forEach { (label, bounds) ->
                    controls.put(label, org.json.JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                }
                resultData = org.json.JSONObject().put("controls", controls).toString()
                resultCode = Activity.RESULT_OK
                return
            }
            if (intent.action != ACTION_DEBUG_STOP) return
            Log.d(TAG, "收到调试正常停止命令 active=$active canStop=$canStop")
            requestStop()
        }
    }
    /**
     * 息屏属于 App 的会议使用规则；SDK 只提供同场正常停止能力，不监听产品电源策略。
     * 用户主动关闭屏幕后仍走 requestStop，让音源释放、末帧和结果沿原收尾流程完成。
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) { openPendingResultHost(); return }
            if (intent.action != Intent.ACTION_SCREEN_OFF || !active) return
            Log.i(TAG, "设备已息屏，正常停止当前会议 meetingId=$meetingId")
            requestStop("屏幕已关闭，正在等待模型最终字幕…")
        }
    }
    /** 仅用于结果动作的本地 HTML/PDF 工作；截图处理已全部由 SDK 接管。 */
    private val platformWork = Executors.newSingleThreadExecutor()
    private var display = MeetingDisplaySnapshot(false, "zh-CN", "en-US", false, emptyList(), null)
    private var reminderPresentation = MeetingReminderPresentation()
    private val clockTick = object : Runnable {
        override fun run() {
            val currentTiming = timing
            if (active && currentTiming != null) {
                val now = currentTiming.at(SystemClock.elapsedRealtime())
                if (now.clearVisibleText() && !visibleTextCleared) {
                    visibleTextCleared = true
                    display = withoutVisibleText(display)
                    transcriptVersion++
                    Log.d(TAG, "超过10秒无新原文，已清会中可见字幕；标准事件与结果保持 meetingId=$meetingId")
                }
                if (now.limitWarning() && !limitReminderLogged) { limitReminderLogged = true; Log.i(TAG, "本场剩余20分钟提醒 meetingId=$meetingId") }
                if (now.noTranscriptWarning() && !idleReminderLogged) { idleReminderLogged = true; Log.i(TAG, "连续4分钟无转写提醒 meetingId=$meetingId") }
                if (canStop && now.stopCause() != MeetingTimeRule.StopCause.NONE) {
                    Log.i(TAG, "自动停止会议 meetingId=$meetingId cause=${now.stopCause()}")
                    requestStop(if (now.stopCause() == MeetingTimeRule.StopCause.TIME_LIMIT)
                        "已到本场时长上限，正在等待模型最终字幕…" else "连续5分钟无转写，正在等待模型最终字幕…")
                } else publish()
            }
            if (active) main.postDelayed(this, 1000L)
        }
    }
    private val presentationTick = object : Runnable {
        override fun run() {
            if (!active || stopRequested || presentation == null) return
            captureAndSubmit(null)
            main.postDelayed(this, PRESENTATION_INTERVAL_MS)
        }
    }
    private lateinit var overlay: MeetingOverlay
    private var observer: Observer? = null
    private var client: MeetingClient? = null
    private var session: MeetingSession? = null
    private var timing: MeetingTimeRule? = null
    private var stoppedClock: MeetingTimeRule.Snapshot? = null
    @Volatile private var presentation: PresentationCapture? = null
    /** 正常停止末帧的唯一续接点，授权撤销同样必须结束等待。 */
    private var presentationStopContinuation: Runnable? = null
    /** 仅窗口停止登记；末帧完成后才回页面，避免末帧截到自己的整页。 */
    private var returnToMeetingAfterPresentation = false
    private var presentationNotice = ""
    private var announcedPresentationNotice = ""
    private var setup: MeetingSetup? = null
    private var meetingId: String? = null
    /** 空闲绑定不发布账号／部署提示；实际创建会议后才由 Service 提供会议消息。 */
    private var status = ""
    private var capabilityText = "本场功能将在创建会议后按后端能力显示"
    private var audioInputMode: AudioInputMode? = null
    private var audioErrorText: String? = null
    private var audioLevel: AudioInputLevel? = null
    private var preparedMeeting: MeetingAppAssembly.PreparedMeeting? = null
    private var readyResult: MeetingResult? = null
    private var actionOutcome: ResultActionOutcome? = null
    private var actionRunning = false
    /** 只在本场结果动作已成功执行后提示一次；READY 本身不代表结果页面已打开。 */
    private var resultHintShown = false
    private val resultUiAvailable = Runnable(::publish)
    private var transcriptVersion = 0
    /** 清屏只是本地呈现状态；新原文解除，迟到译文或窗口重绘不会恢复旧字幕。 */
    private var visibleTextCleared = false
    private var busy = false
    private var active = false
    private var canStop = false
    private var stopRequested = false
    private var limitReminderLogged = false
    private var idleReminderLogged = false
    private var debugStopReceiverRegistered = false
    private var destroyed = false
    private var projectionData: Intent? = null
    private var projectionResultCode = 0

    override fun onCreate() {
        super.onCreate()
        overlay = MeetingOverlay(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AI 会议进行中", NotificationManager.IMPORTANCE_LOW))
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        // 最低 Android 14：系统息屏广播不向其他 App 开放；Debug 停止入口保留 ADB 调试能力。
        registerReceiver(screenOffReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val filter = IntentFilter(ACTION_DEBUG_STOP).apply { addAction(ACTION_DEBUG_OVERLAY) }
            registerReceiver(debugStopReceiver, filter, Context.RECEIVER_EXPORTED)
            debugStopReceiverRegistered = true
            Log.d(TAG, "调试正常停止命令已注册")
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != ACTION_BEGIN || busy) return START_NOT_STICKY
        val prepared = MeetingAppAssembly.consumePreparedMeeting()
        if (prepared == null) { status = "缺少本场会前准备，会议未创建"; publish(); stopSelf(); return START_NOT_STICKY }
        val requested = prepared.setup()
        if (requested.presentationCapture()) {
            if (!ScreenCaptureSourceFactory.isSupported()) {
                status = "Mock构建不提供屏幕截图，请使用SDK构建，会议未创建"; publish(); stopSelf(); return START_NOT_STICKY
            }
            projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            projectionResultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
            if (projectionData == null) { status = "缺少本场系统屏幕采集授权，会议未创建"; publish(); stopSelf(); return START_NOT_STICKY }
        }
        startForegroundForMeeting(requested.presentationCapture())
        begin(prepared)
        return START_NOT_STICKY
    }
    private fun startForegroundForMeeting(withPresentation: Boolean) {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AI 会议进行中").setContentText("点击返回会议页面").setContentIntent(pending).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = if (MeetingRuntimeFactory.requiresMicrophone()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (withPresentation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }
    fun observe(next: Observer) {
        if (destroyed) return
        observer = next; MeetingAppAssembly.observeResultUi(resultUiAvailable); publish()
    }
    fun unobserve(old: Observer) {
        if (observer === old) { observer = null; MeetingAppAssembly.unobserveResultUi(resultUiAvailable); publish() }
    }
    fun confirmReminder() { reminderPresentation.confirm(); publish() }
    fun takeUiOperation() = MeetingAppAssembly.consumeResultUiOperation()
    private fun queueUiOperation(command: Runnable) {
        main.post {
            if (command is AppResultActionPlugin.UiOperation) {
                MeetingAppAssembly.queueResultUiOperation(command)
                openPendingResultHost()
            }
            else command.run() // 原宿主关闭不吞掉 Registry 的普通完成闭包。
        }
    }
    /** 默认会中没有 Activity。结果插件需要 UI 时才唤起宿主；息屏时等唤醒，不点亮屏幕。 */
    private fun openPendingResultHost() {
        if (!destroyed && observer == null && MeetingAppAssembly.hasPendingResultUi() &&
            getSystemService(PowerManager::class.java).isInteractive) openMeetingPage()
    }
    /** 幂等显示，不切换已存在窗口的 Mini／大窗形态，不重建会议或重新申请音源。 */
    fun showOverlay(): Boolean {
        if (!active || stopRequested) return false
        if (!overlay.isShowing()) {
            overlay.show(display, Runnable { openMeetingPage(details = true) }, Runnable(::swapDisplayColumns),
                Runnable(::confirmReminder), Runnable(::requestStopFromOverlay))
            Log.i(TAG, "跨应用字幕窗挂载 showing=${overlay.isShowing()}")
            publish()
        }
        return overlay.isShowing()
    }
    fun toggleOverlay() {
        if (!active || stopRequested) return
        if (overlay.isShowing()) { overlay.dismiss(); Log.i(TAG, "跨应用字幕窗已关闭") }
        else {
            showOverlay()
        }
        publish()
    }
    private fun requestStopFromOverlay() {
        if (!active || !canStop || session == null) return
        val waitForFinalFrame = presentation != null
        returnToMeetingAfterPresentation = waitForFinalFrame
        Log.i(TAG, "悬浮窗请求正常停止 waitForFinalFrame=$returnToMeetingAfterPresentation meetingId=$meetingId")
        requestStop()
        // 先退窗并改变会中状态，再打开收尾宿主，避免它按RUNNING重新回到浮窗。
        if (!waitForFinalFrame) openMeetingPage()
    }
    private fun openMeetingPage(details: Boolean = false) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_MEETING_DETAILS, details)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    fun swapDisplayColumns() {
        if (!active || !display.bilingual() || session == null) return
        session?.swapDisplayColumns()
        Log.i(TAG, "双语分栏左右交换已请求 meetingId=$meetingId")
    }
    private fun begin(prepared: MeetingAppAssembly.PreparedMeeting) {
        releaseResultAccess()
        val requested = prepared.setup()
        preparedMeeting = prepared; setup = requested; busy = true; active = false; canStop = false
        stopRequested = false; presentationStopContinuation = null; returnToMeetingAfterPresentation = false
        presentationNotice = ""; announcedPresentationNotice = ""; readyResult = null; actionOutcome = null; actionRunning = false; resultHintShown = false
        visibleTextCleared = false
        timing = null; stoppedClock = null; meetingId = null; audioInputMode = null; audioErrorText = null; audioLevel = null
        display = MeetingDisplaySnapshot(requested.mode() == MeetingSetup.Mode.LIVE_TRANSLATION,
            requested.sourceLanguage(), requested.targetLanguage(), false, emptyList(), null)
        transcriptVersion++; status = "正在创建会议…"; capabilityText = "本场功能将在创建会议后按后端能力显示"
        publish()
        try {
            val config = MeetingAppAssembly.clientConfig { main.post(it) }
            val created = MeetingRuntimeFactory.create(this, config)
            client = created
            session = created.start(requested.toOptions(), object : MeetingListener {
                override fun onStarted(started: MeetingStarted) {
                    meetingId = started.meetingId(); timing = MeetingTimeRule(started.startedAtMs(), started.maxMeetingDurationMs())
                    reminderPresentation = MeetingReminderPresentation(); limitReminderLogged = false; idleReminderLogged = false
                    capabilityText = describeCapabilities(started.availableCapabilities())
                    if (requested.presentationCapture()) startPresentation(started.startedAtMs())
                    main.removeCallbacks(clockTick); main.post(clockTick)
                    Log.i(TAG, "组合会议已启动 meetingId=$meetingId mode=${requested.mode()}")
                }
                override fun onState(next: MeetingSessionState) {
                    val enteringRunning = next.phase() == MeetingSessionState.Phase.RUNNING && !active && !stopRequested
                    active = next.active() && !stopRequested; canStop = next.canStop()
                    if (next.phase() != MeetingSessionState.Phase.RUNNING) audioLevel = null
                    status = next.message()
                    Log.d(TAG, "组合会议状态 phase=${next.phase()} savedEventCount=${next.savedEventCount()}")
                    if (enteringRunning) showOverlay()
                    when (next.phase()) {
                        MeetingSessionState.Phase.STOPPING, MeetingSessionState.Phase.FINALIZING,
                        MeetingSessionState.Phase.STOP_PENDING -> {
                            stopRequested = true
                            stopMeetingPresentation()
                            publish()
                        }
                        MeetingSessionState.Phase.FAILED, MeetingSessionState.Phase.ENDED, MeetingSessionState.Phase.CLOSED -> finishWithStatus(next.message())
                        MeetingSessionState.Phase.READY -> resultReady()
                        else -> publish()
                    }
                }
                override fun onDisplay(next: MeetingDisplaySnapshot) = updateDisplay(next)
                override fun onTranscriptActivity(nowMs: Long) = recordTranscriptActivity(nowMs)
                override fun onAudioInputMode(mode: AudioInputMode) {
                    audioInputMode = mode; Log.i(TAG, "本场实际音频来源 mode=$mode meetingId=$meetingId"); publish()
                }
                override fun onAudioLevel(level: AudioInputLevel) {
                    if (!active || stopRequested || audioErrorText != null) return
                    audioLevel = level
                    Log.d(TAG, "同场输入电平 level=${level.level()} sampledAtMs=${level.sampledAtElapsedRealtimeMs()}")
                    publish()
                }
                override fun onAudioError(code: AudioInputErrorCode, message: String) {
                    audioLevel = null
                    audioErrorText = if (code == AudioInputErrorCode.PERMISSION_DENIED)
                        "没有麦克风权限，请在应用中授予权限后重新开始会议" else "音频输入失败：$message"
                    Log.w(TAG, "音频输入失败 code=$code meetingId=$meetingId"); publish()
                }
                override fun beforeStop(continuation: Runnable) = stopPresentationBefore(continuation)
                override fun onResult(result: MeetingResult) {
                    // 早于 READY 状态，只保存事实，不提前关闭 client 或跳转。
                    readyResult = result; Log.i(TAG, "已接收本场READY清单 meetingId=${result.meetingId()}")
                }
            })
        } catch (failure: RuntimeException) {
            Log.e(TAG, "打开组合会议失败 errorType=${failure.javaClass.simpleName}")
            finishWithStatus("打开会议失败，请检查接入配置")
        }
    }
    /** 只投影 SDK 快照，不修改它的分栏、标准事件或结果历史。 */
    private fun updateDisplay(next: MeetingDisplaySnapshot) {
        display = if (visibleTextCleared) withoutVisibleText(next) else next
        transcriptVersion++
        Log.d(TAG, "已路由显示快照 rows=${display.rows().size} swapped=${next.swapped()} textCleared=$visibleTextCleared")
        publish()
    }
    private fun recordTranscriptActivity(nowMs: Long) {
        val clock = timing ?: return
        clock.onTranscriptActivity(nowMs)
        if (visibleTextCleared) Log.d(TAG, "新原文已恢复会中字幕显示 meetingId=$meetingId")
        visibleTextCleared = false
        if (idleReminderLogged) { idleReminderLogged = false; Log.i(TAG, "转写恢复，无转写提醒已解除 meetingId=$meetingId") }
    }
    private fun withoutVisibleText(source: MeetingDisplaySnapshot) = MeetingDisplaySnapshot(
        source.bilingual(), source.languageA(), source.languageB(), source.swapped(), emptyList(), null)
    private fun startPresentation(startedAtMs: Long) {
        try {
            val source = ScreenCaptureSourceFactory.create(this, projectionResultCode, projectionData, { main.post(it) }, Runnable(::onPresentationStopped))
            presentation = PresentationCapture(source, startedAtMs)
            presentationNotice = if (source.supportsWindowExclusion()) "" else "当前系统仅支持整屏截图，演示图片会包含悬浮窗"
            main.postDelayed(presentationTick, PRESENTATION_INTERVAL_MS)
            Log.i(TAG, "SDK演示采集已开始 mode=${source.mode()} excludeWindows=${source.supportsWindowExclusion()} meetingId=$meetingId")
        } catch (failure: RuntimeException) {
            Log.e(TAG, "演示文稿屏幕采集启动失败 errorType=${failure.javaClass.simpleName}")
            presentationNotice = "屏幕采集启动失败；字幕会议继续，可停止后重试"
        } finally { projectionData = null }
    }
    /** 周期帧→真实保存ACK→末帧→关闭→SDK收口；全部异步，不阻塞主线程。 */
    private fun captureAndSubmit(completed: Runnable?) {
        val current = presentation
        val currentSession = session
        if (current == null || currentSession == null) { completed?.run(); return }
        try {
            // 会中主页面和停止后同一窗口内的会前页也属于本 App 的 UI，和悬浮窗一并排除。
            // Activity 只交窗口句柄，算法／图像处理仍完整留在 SDK；普通平台仍明确整屏采集。
            val excluded = if (current.supportsWindowExclusion())
                overlay.captureWindowTokens() + (observer?.captureWindowTokens() ?: emptyArray())
            else emptyArray()
            // Debug 只记录公共调用是否完成，不暴露 SDK 的内部判断或跳过原因。
            if (BuildConfig.DEBUG) Log.d(TAG, "演示截图请求 final=${completed != null} excludedWindows=${excluded.size}")
            current.capture(SystemClock.elapsedRealtime(), excluded, object : PresentationCapture.Callback {
                override fun onSuccess(frame: PresentationCapture.Frame?) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "演示截图返回 final=${completed != null} hasImage=${frame != null}")
                    if (frame == null) { completed?.run(); return }
                    if (destroyed || presentation !== current || session !== currentSession) {
                        frame.close()
                        return
                    }
                    try {
                        // 两参旧入口只代表入队；必须等三参入口的后端保存ACK才确认SDK图片。
                        currentSession.submitPresentation(frame.offsetMs(), frame.jpeg(), object : MeetingClient.Callback<Void?> {
                            override fun onSuccess(result: Void?) {
                                try {
                                    if (!destroyed && presentation === current && session === currentSession) {
                                        frame.confirmAccepted()
                                        Log.i(TAG, "演示截图已由业务接受 offsetMs=${frame.offsetMs()}")
                                    }
                                } finally {
                                    frame.close()
                                    if (!destroyed && presentation === current && session === currentSession) completed?.run()
                                }
                            }
                            override fun onError(message: String) {
                                try {
                                    if (presentation === current && session === currentSession) presentationFailed(message)
                                } finally {
                                    frame.close()
                                    if (!destroyed && presentation === current && session === currentSession) completed?.run()
                                }
                            }
                        })
                    } catch (failure: RuntimeException) {
                        try { presentationFailed("演示截图提交无法执行") }
                        finally {
                            frame.close()
                            if (!destroyed && presentation === current && session === currentSession) completed?.run()
                        }
                    }
                }
                override fun onError(message: String) {
                    try { presentationFailed(message) } finally { completed?.run() }
                }
            })
        } catch (failure: RuntimeException) {
            try { presentationFailed("SDK截图请求无法执行") } finally { completed?.run() }
        }
    }
    private fun presentationFailed(message: String) {
        if (destroyed) return
        Log.w(TAG, "演示截图未完成：$message"); main.removeCallbacks(presentationTick)
        if (!stopRequested) presentationNotice = "演示截图采集失败；字幕会议继续"
        publish()
    }
    /** beforeStop主线程入口；末帧排在周期ACK后，无新图／失败也关闭并续接原停止。 */
    fun stopPresentationBefore(continuation: Runnable) {
        main.removeCallbacks(presentationTick)
        presentationStopContinuation = continuation
        captureAndSubmit(Runnable(::finishPresentationStop))
    }
    private fun finishPresentationStop() {
        val continuation = presentationStopContinuation
        presentationStopContinuation = null
        try {
            closePresentation()
            if (returnToMeetingAfterPresentation) {
                returnToMeetingAfterPresentation = false
                Log.i(TAG, "停止末帧处理结束，返回会议页面 meetingId=$meetingId")
                openMeetingPage()
            }
        } finally { continuation?.run() }
    }
    private fun onPresentationStopped() {
        closePresentation(); main.removeCallbacks(presentationTick)
        if (presentationStopContinuation != null) finishPresentationStop()
        if (active && !stopRequested) { presentationNotice = "系统已结束屏幕共享，字幕会议继续"; publish() }
    }
    private fun closePresentation() { val current = presentation; presentation = null; current?.close() }
    fun requestStop() = requestStop("正在等待模型最终字幕…")
    private fun requestStop(progressText: String) {
        val current = session
        if (!canStop || current == null) return
        stopRequested = true; canStop = false; status = progressText
        stopMeetingPresentation()
        publish()
        current.requestStop()
    }
    private fun resultReady() { stopMeetingUi(); status = "本场结果已就绪"; publish(); executeResultAction() }
    fun retryResultAction() { if (actionOutcome?.status() == ResultActionOutcome.Status.FAILED) executeResultAction() }
    private fun executeResultAction() {
        val result = readyResult ?: return
        val executingSession = session ?: return
        val prepared = preparedMeeting ?: return
        val action = prepared.action() ?: return
        if (actionRunning) return
        actionRunning = true; busy = true; actionOutcome = null
        status = "本场结果已就绪，正在执行结果动作…"; publish()
        val context = ResultActionContext(result, executingSession.resultActionAccess(), prepared.serviceCapabilities(), platformWork, ::queueUiOperation)
        MeetingAppAssembly.resultRegistry().execute(action, context, object : MeetingClient.Callback<ResultActionOutcome> {
            override fun onSuccess(outcome: ResultActionOutcome) {
                if (destroyed || session !== executingSession) return
                actionRunning = false; busy = false; actionOutcome = outcome
                status = "本场结果已就绪；${outcome.message()}"
                if (outcome.status() == ResultActionOutcome.Status.FAILED) Log.w(TAG, "结果动作失败，会议READY保持 id=${outcome.actionId()}")
                if (outcome.status() == ResultActionOutcome.Status.SUCCEEDED) {
                    if (!resultHintShown) {
                        resultHintShown = true
                        // 动作可替换，提示不推断邮件／扫码等渠道，也不声称已送达外部收件人。
                        Toast.makeText(applicationContext, "会议已结束，可查看本场结果", Toast.LENGTH_LONG).show()
                        Log.i(TAG, "已显示本场结果提示 meetingId=$meetingId action=${outcome.actionId()}")
                    }
                    releaseResultAccess(); stopSelf()
                }
                publish()
            }
            override fun onError(message: String) = onSuccess(ResultActionOutcome.failure(action.id(), message))
        })
    }
    fun endResultInteraction() {
        if (readyResult == null || actionRunning) return
        releaseResultAccess(); actionOutcome = null; busy = false; status = "本场结果已就绪，结果交互已结束"
        stopSelf(); publish()
    }
    private fun releaseResultAccess() { client?.close(); client = null; session = null }
    /**
     * Stop 一经接受就结束会中呈现，不等待模型、上传或服务端 READY。
     * 这里只退窗和冻结显示计时；会话、前台 Service 与截图末帧仍由原收尾链持有。
     */
    private fun stopMeetingPresentation() {
        if (stoppedClock == null && timing != null) {
            stoppedClock = timing!!.at(SystemClock.elapsedRealtime())
            Log.i(TAG, "会中呈现已停止，保留同场会话等待结果 meetingId=$meetingId")
        }
        active = false; audioLevel = null
        main.removeCallbacks(clockTick); main.removeCallbacks(presentationTick)
        overlay.dismiss()
    }
    private fun stopMeetingUi() {
        stopMeetingPresentation()
        returnToMeetingAfterPresentation = false; canStop = false
        closePresentation(); projectionData = null; stopForeground(STOP_FOREGROUND_REMOVE)
    }
    private fun finishWithStatus(message: String) {
        stopMeetingUi(); busy = false; canStop = false; status = message
        releaseResultAccess(); stopSelf(); publish()
    }
    private fun publish() {
        if (destroyed) return
        val clock = stoppedClock ?: timing?.at(SystemClock.elapsedRealtime())
        val timeText = if (clock == null) "" else "已进行 ${MeetingTimeRule.format(clock.elapsedMs())} · 剩余 ${MeetingTimeRule.format(clock.remainingMs())}"
        val reminder = if (active && clock != null) reminderPresentation.at(clock) else MeetingReminderPresentation.Notice.NONE
        var audioText = if (audioInputMode == null) "" else "\n音频来源：$audioInputMode"
        audioErrorText?.let { audioText += "\n$it" }
        val state = State(display, status, capabilityText + audioText, busy, active, canStop,
            overlay.isShowing(), transcriptVersion, readyResult, actionOutcome, actionRunning, timeText, reminder,
            setup?.mode() ?: MeetingSetup.Mode.LIVE_TRANSCRIPT, setup?.summaryEnabled() == true, audioLevel, presentationNotice,
            presentation != null)
        overlay.render(display, state.mode, state.summaryEnabled, timeText, reminder, canStop, observer == null, audioLevel?.level() ?: 0)
        // 无会中页面时保留必要提示；同一条消息只显示一次，不随字幕／电平刷新刷屏。
        if (active && presentationNotice.isNotEmpty() && presentationNotice != announcedPresentationNotice) {
            announcedPresentationNotice = presentationNotice
            Toast.makeText(applicationContext, presentationNotice, Toast.LENGTH_LONG).show()
        }
        observer?.onMeetingState(state)
    }
    override fun onDestroy() {
        destroyed = true; presentationStopContinuation = null; returnToMeetingAfterPresentation = false; observer = null
        MeetingAppAssembly.unobserveResultUi(resultUiAvailable); audioLevel = null
        if (debugStopReceiverRegistered) {
            unregisterReceiver(debugStopReceiver); debugStopReceiverRegistered = false; Log.d(TAG, "调试正常停止命令已注销")
        }
        unregisterReceiver(screenOffReceiver)
        main.removeCallbacks(clockTick); main.removeCallbacks(presentationTick); closePresentation()
        overlay.dismiss(); releaseResultAccess(); platformWork.shutdownNow(); super.onDestroy()
    }
    companion object {
        const val ACTION_BEGIN = "com.czur.aimeeting.reference.BEGIN"
        const val ACTION_DEBUG_STOP = "com.czur.aimeeting.reference.DEBUG_STOP"
        const val ACTION_DEBUG_OVERLAY = "com.czur.aimeeting.reference.DEBUG_OVERLAY"
        const val EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        private const val TAG = "AiMeetingReference"
        private const val CHANNEL = "ai_meeting_active"
        private const val NOTIFICATION_ID = 100
        private const val PRESENTATION_INTERVAL_MS = 10_000L
        private fun describeCapabilities(available: Set<String>) = "本场功能：实时字幕" +
            (if (available.contains(MeetingCapabilityState.TRANSLATION)) "、实时翻译" else "；实时翻译已关闭") +
            (if (available.contains(MeetingCapabilityState.SPEAKER)) "、本场发言人" else "；本场发言人已关闭") +
            (if (available.contains(MeetingCapabilityState.SUMMARY)) "、会议纪要" else "；会议纪要已关闭")
    }
}
