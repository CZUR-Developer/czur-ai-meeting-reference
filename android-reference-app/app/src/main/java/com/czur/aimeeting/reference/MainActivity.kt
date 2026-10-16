package com.czur.aimeeting.reference

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.result.ResultOptions
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.server.ReferenceLoginSession
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.reference.meeting.*
import com.czur.aimeeting.reference.ui.SubtitleViewFactory
import java.util.Locale

/** 参考入口只持有登录、会前设置和呈现，会议流程仍由完整 SDK 客户端管理。 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val observer = MeetingService.Observer(::renderMeetingState)
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            meetingService = (binder as MeetingService.LocalBinder).service()
            meetingService?.observe(observer)
        }
        override fun onServiceDisconnected(name: ComponentName) { meetingService = null }
    }
    /** 会前客户端独立于 Service，离开页面不会关闭持续会议。 */
    private var preMeetingClient: MeetingClient? = null
    private lateinit var startCondition: MeetingStartCondition
    private var meetingService: MeetingService? = null
    private var serviceBound = false
    private lateinit var page: MeetingPageViews
    private var loginDialog: AlertDialog? = null
    private var renderedTranscriptVersion = -1
    private lateinit var usernameView: EditText
    private lateinit var passwordView: EditText
    private lateinit var resultGroup: RadioGroup
    private val resultActionIds = linkedMapOf<Int, String>()
    private val resultSettings = linkedMapOf<String, AppResultActionPlugin.Settings>()
    private lateinit var transcriptView: TextView
    private lateinit var meetingTitle: TextView
    private lateinit var transcriptList: LinearLayout
    private lateinit var leftLanguageTitle: TextView
    private lateinit var rightLanguageTitle: TextView
    private val transcriptRows = linkedMapOf<String, TextView>()
    private lateinit var elapsedView: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var audioLevelView: TextView
    private lateinit var reminderPopup: MeetingReminderPopup
    private lateinit var statusView: TextView
    private lateinit var capabilityView: TextView
    private lateinit var sourceLanguageView: Spinner
    private lateinit var targetLanguageView: Spinner
    private lateinit var presentationCaptureView: CheckBox
    private lateinit var summaryEnabledView: CheckBox
    private lateinit var transcriptModeView: RadioButton
    private lateinit var translationModeView: RadioButton
    private lateinit var startButton: Button
    private lateinit var retryActionButton: Button
    private lateinit var endResultButton: Button
    private var resumed = false
    private lateinit var stopButton: Button
    private lateinit var overlayButton: Button
    private lateinit var swapColumnsButton: Button
    private lateinit var loginButton: Button
    private lateinit var settingsButton: Button
    /** 只用于页面入口，开始会议仍重新读取 Provider 的真实凭据。 */
    private var displayedIdentity: IdentitySession? = null
    private var meetingBusy = false
    private var deploymentCapabilities: Set<String> = emptySet()
    private var deploymentLanguages: List<String> = emptyList()
    private var discovery: MeetingDiscovery? = null
    private var pendingPresentationMeeting: MeetingAppAssembly.PreparedMeeting? = null
    private var hasReadyResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先恢复后端地址与默认登录插件，再创建页面和 Service 共用的客户端。
        MeetingAppAssembly.initialize(this)
        startCondition = MeetingStartConditions.create(applicationContext)
        page = MeetingAppAssembly.pageFactory().create(this)
        loginButton = page.account; settingsButton = page.settings; resultGroup = page.results
        transcriptView = page.placeholder; meetingTitle = page.meetingTitle; transcriptList = page.transcriptList
        leftLanguageTitle = page.leftTitle; rightLanguageTitle = page.rightTitle; elapsedView = page.elapsed
        audioLevelBar = page.level; audioLevelView = page.levelText; statusView = page.status
        capabilityView = page.capabilities; sourceLanguageView = page.sourceLanguage; targetLanguageView = page.targetLanguage
        presentationCaptureView = page.presentation; summaryEnabledView = page.summary
        transcriptModeView = page.transcriptMode; translationModeView = page.translationMode
        startButton = page.start; stopButton = page.stop; overlayButton = page.overlay
        swapColumnsButton = page.swapColumns; retryActionButton = page.retry; endResultButton = page.endResult
        loginButton.setOnClickListener { if (displayedIdentity != null) logout(); showLoginDialog() }
        settingsButton.setOnClickListener { startActivityForResult(Intent(this, ConnectionSettingsActivity::class.java), CONNECTION_SETTINGS_REQUEST) }
        page.modes.setOnCheckedChangeListener { _, checkedId ->
            val translation = checkedId == translationModeView.id
            targetLanguageView.isEnabled = translation && deploymentLanguages.size >= 2
            if (translation) presentationCaptureView.isChecked = false
            presentationCaptureView.isEnabled = !translation && ScreenCaptureSourceFactory.isSupported()
            page.showSetupMode(translation, summaryCapabilityAvailable())
        }
        summaryEnabledView.setOnCheckedChangeListener { _, _ ->
            page.showSetupMode(translationModeView.isChecked, summaryCapabilityAvailable())
        }
        page.swapLanguages.setOnClickListener {
            val source = sourceLanguageView.selectedItemPosition
            val target = targetLanguageView.selectedItemPosition
            if (source >= 0 && target >= 0) { sourceLanguageView.setSelection(target); targetLanguageView.setSelection(source) }
        }
        refreshLanguageOptions(emptyList())
        refreshModeAvailability()
        // 只呈现实际注册的动作，各插件自己的设置保留独立槽位。
        for (plugin in MeetingAppAssembly.resultActions()) {
            val choice = RadioButton(this).apply { id = View.generateViewId(); text = plugin.displayName() }
            page.styleResultChoice(choice)
            resultActionIds[choice.id] = plugin.id()
            resultGroup.addView(choice)
            val settings = plugin.createSettings(this)
            resultSettings[plugin.id()] = settings
            settings.view()?.let { it.visibility = View.GONE; page.pluginSettingsHost.addView(it) }
        }
        resultGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = resultActionIds[checkedId]
            resultSettings.forEach { (id, settings) -> settings.view()?.visibility = if (id == selected) View.VISIBLE else View.GONE }
        }
        refreshResultAvailability()
        startButton.setOnClickListener {
            if (MeetingRuntimeFactory.requiresMicrophone() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
            else createAndStartMeeting()
        }
        stopButton.setOnClickListener { meetingService?.requestStop() }
        swapColumnsButton.setOnClickListener { meetingService?.swapDisplayColumns() }
        overlayButton.setOnClickListener { toggleOverlay() }
        retryActionButton.setOnClickListener { meetingService?.retryResultAction() }
        endResultButton.setOnClickListener { meetingService?.endResultInteraction() }
        audioLevelView.text = if (MeetingRuntimeFactory.requiresMicrophone()) "输入电平：等待输入" else "输入电平：Mock 未实采"
        reminderPopup = MeetingReminderPopup(this, false, Runnable { meetingService?.confirmReminder() })
        page.root.addView(reminderPopup.view(), FrameLayout.LayoutParams(-1, -1))
        setContentView(page.root)
    }

    /** 独立参考账号入口，不依赖系统账号；凭据只交给当前进程 Provider。 */
    private fun showLoginDialog() {
        val fields = LayoutInflater.from(this).inflate(R.layout.reference_login_dialog, null)
        ReferenceUiScale.bindDialog(fields)
        usernameView = fields.findViewById(R.id.login_username)
        passwordView = fields.findViewById(R.id.login_password)
        val dialog = AlertDialog.Builder(this).setView(fields).create()
        loginDialog = dialog
        fields.findViewById<View>(R.id.login_close).setOnClickListener { dialog.dismiss() }
        fields.findViewById<View>(R.id.login_submit).setOnClickListener { login() }
        dialog.show()
        dialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window!!.setLayout(ReferenceUiScale.px(this, 760), -2)
    }
    private fun loginMessage(message: String) {
        statusView.text = message
        loginDialog?.takeIf { it.isShowing }?.findViewById<TextView>(R.id.login_message)?.text = message
    }
    private fun login() {
        val baseUrl = MeetingAppAssembly.serverUrl()
        val username = usernameView.text.toString().trim()
        val password = passwordView.text.toString()
        if (baseUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            loginMessage("请先在连接设置配置后端，并填写账号和密码"); return
        }
        setConfigurationEnabled(false)
        loginMessage("正在登录…")
        preMeetingClient?.close()
        MeetingAppAssembly.configureBackend(baseUrl)
        val client = MeetingRuntimeFactory.create(this, MeetingAppAssembly.clientConfig { main.post(it) })
        preMeetingClient = client
        client.login(username, password, object : MeetingClient.Callback<ReferenceLoginSession> {
            override fun onSuccess(loginSession: ReferenceLoginSession) {
                passwordView.setText(""); loginDialog?.dismiss()
                statusView.text = "登录成功，正在获取可用功能…"
                Log.i(TAG, "参考账号登录成功")
                refreshIdentity(true)
            }
            override fun onError(message: String) { setConfigurationEnabled(true); loginMessage(message); Log.w(TAG, "参考账号登录失败") }
        })
    }

    /** 进入页面或伙伴登录返回时读取身份，页面不直接读取 token 或 Provider 私有字段。 */
    private fun refreshIdentity(reloadCapabilities: Boolean) {
        if (MeetingAppAssembly.serverUrl().isEmpty()) return
        if (preMeetingClient == null) preMeetingClient = MeetingRuntimeFactory.create(this, MeetingAppAssembly.clientConfig { main.post(it) })
        preMeetingClient!!.refreshIdentity(object : MeetingClient.Callback<IdentityResult> {
            override fun onSuccess(identity: IdentityResult) {
                renderIdentity(identity)
                if (identity.status() == IdentityResult.Status.LOGGED_IN && reloadCapabilities) discoverCapabilities()
            }
            override fun onError(message: String) { statusView.text = message }
        })
    }
    private fun renderIdentity(identity: IdentityResult) {
        displayedIdentity = identity.session()
        val loggedIn = identity.status() == IdentityResult.Status.LOGGED_IN
        loginButton.text = if (loggedIn) "切换账号" else "账号登录"
        setConfigurationEnabled(!meetingBusy)
        if (!loggedIn) {
            deploymentCapabilities = emptySet(); refreshLanguageOptions(emptyList()); discovery = null
            if (!meetingBusy && !hasReadyResult) statusView.text = identity.message()
        }
        refreshResultAvailability(); refreshModeAvailability()
        startButton.isEnabled = loggedIn && !meetingBusy && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT) && deploymentLanguages.isNotEmpty()
        Log.d(TAG, "页面身份刷新 state=${identity.status()}")
    }
    private fun discoverCapabilities() {
        preMeetingClient!!.discover(object : MeetingClient.Callback<MeetingDiscovery> {
            override fun onSuccess(discovery: MeetingDiscovery) {
                deploymentCapabilities = discovery.availableCapabilities()
                refreshLanguageOptions(discovery.deployment().languages())
                this@MainActivity.discovery = discovery
                refreshResultAvailability(); refreshModeAvailability(); setConfigurationEnabled(!meetingBusy)
                startButton.isEnabled = displayedIdentity != null && !meetingBusy && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT) && deploymentLanguages.isNotEmpty()
                if (!meetingBusy && !hasReadyResult) statusView.text = if (deploymentLanguages.isEmpty()) "当前部署尚未声明会议语言" else "登录成功，可开始会议"
            }
            override fun onError(message: String) {
                setConfigurationEnabled(!meetingBusy); startButton.isEnabled = false
                if (!meetingBusy && !hasReadyResult) statusView.text = "登录成功；$message"
            }
        })
    }
    private fun logout() {
        preMeetingClient?.close(); preMeetingClient = null
        MeetingAppAssembly.clearReferenceSession(); displayedIdentity = null
        deploymentCapabilities = emptySet(); refreshLanguageOptions(emptyList()); discovery = null
        refreshResultAvailability(); refreshModeAvailability(); loginButton.text = "账号登录"
        startButton.isEnabled = false; statusView.text = "已退出，请重新登录"
        Log.i(TAG, "参考账号已退出")
    }
    private fun refreshModeAvailability() {
        val summary = summaryCapabilityAvailable()
        val translation = displayedIdentity != null && deploymentCapabilities.contains(MeetingCapabilityState.TRANSLATION) && deploymentLanguages.size >= 2
        summaryEnabledView.text = "生成会议纪要"
        summaryEnabledView.contentDescription = "生成会议纪要"
        translationModeView.text = "AI双语互译"; translationModeView.alpha = 1f
        page.showCapabilityOptions(summary, translation)
        targetLanguageView.isEnabled = translation && translationModeView.isChecked
        val screenCapture = ScreenCaptureSourceFactory.isSupported()
        presentationCaptureView.text = if (screenCapture) "演示文稿" else "演示文稿（Mock不支持截图）"
        presentationCaptureView.contentDescription = if (screenCapture) "演示文稿，开始时申请系统屏幕采集授权" else "Mock构建不提供截图，请使用SDK构建"
        presentationCaptureView.isEnabled = screenCapture && !translationModeView.isChecked
        if (!screenCapture) presentationCaptureView.isChecked = false
        page.showSetupMode(translationModeView.isChecked, summary)
    }
    private fun summaryCapabilityAvailable(): Boolean =
        displayedIdentity != null && deploymentCapabilities.contains(MeetingCapabilityState.SUMMARY)
    /** 保留仍可用的选择，初次发现取后端前两项，不硬编码模型语言。 */
    private fun refreshLanguageOptions(languages: List<String>) {
        val source = deploymentLanguages.getOrNull(sourceLanguageView.selectedItemPosition)
        val target = deploymentLanguages.getOrNull(targetLanguageView.selectedItemPosition)
        deploymentLanguages = languages.toList()
        val labels = deploymentLanguages.map { code ->
            val label = if (code.startsWith("en")) "English" else Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
            label.ifEmpty { code }
        }
        sourceLanguageView.adapter = languageAdapter(labels, sourceLanguageView)
        targetLanguageView.adapter = languageAdapter(labels, targetLanguageView)
        if (deploymentLanguages.isNotEmpty()) {
            sourceLanguageView.setSelection(maxOf(0, deploymentLanguages.indexOf(source)))
            val targetIndex = deploymentLanguages.indexOf(target)
            targetLanguageView.setSelection(if (targetIndex >= 0) targetIndex else minOf(1, deploymentLanguages.size - 1))
        }
        sourceLanguageView.isEnabled = deploymentLanguages.isNotEmpty()
        Log.d(TAG, "会议语言选项更新 count=${deploymentLanguages.size}")
    }
    private fun languageAdapter(labels: List<String>, owner: Spinner): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, R.layout.reference_language_selected, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val text = super.getView(position, convertView, parent) as TextView
                page.styleLanguageItem(text, false, true)
                return text
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val text = super.getDropDownView(position, convertView, parent) as TextView
                page.styleLanguageItem(text, true, position == owner.selectedItemPosition)
                return text
            }
        }.apply { setDropDownViewResource(R.layout.reference_language_choice) }
    private fun refreshResultAvailability() {
        val current = discovery
        val available = if (current == null) emptySet() else MeetingAppAssembly.resultRegistry().available(current).map { it.id() }.toSet()
        var first: Int? = null
        for ((id, action) in resultActionIds) {
            val visible = available.contains(action)
            resultGroup.findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
            if (visible && first == null) first = id
        }
        val selected = resultActionIds[resultGroup.checkedRadioButtonId]
        if (selected == null || !available.contains(selected)) {
            if (first == null) { resultGroup.clearCheck(); resultSettings.values.forEach { it.view()?.visibility = View.GONE } }
            else resultGroup.check(first)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) createAndStartMeeting()
        else statusView.text = "需要麦克风权限才能进行真实转写"
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONNECTION_SETTINGS_REQUEST) {
            if (resultCode == RESULT_OK) {
                preMeetingClient?.close(); preMeetingClient = null; displayedIdentity = null
                deploymentCapabilities = emptySet(); refreshLanguageOptions(emptyList()); discovery = null
                refreshResultAvailability(); refreshModeAvailability(); startButton.isEnabled = false
            }
            return
        }
        if (requestCode != PRESENTATION_PERMISSION_REQUEST) return
        val approved = pendingPresentationMeeting
        pendingPresentationMeeting = null
        if (approved == null) return
        if (resultCode != RESULT_OK || data == null) {
            MeetingAppAssembly.clearPreparedMeeting(); statusView.text = "未授予系统屏幕采集权限，会议未开始"
            startButton.isEnabled = true; setConfigurationEnabled(true); return
        }
        startMeeting(approved, resultCode, data)
    }
    private fun createAndStartMeeting() {
        val client = preMeetingClient
        if (client == null) { statusView.text = "请先登录"; return }
        startButton.isEnabled = false; setConfigurationEnabled(false)
        client.refreshIdentity(object : MeetingClient.Callback<IdentityResult> {
            override fun onSuccess(identity: IdentityResult) {
                renderIdentity(identity)
                if (identity.status() == IdentityResult.Status.LOGGED_IN) continueStartMeeting()
            }
            override fun onError(message: String) { statusView.text = message }
        })
    }
    /** 页面检查后 SDK 在真实建会前仍重新读取身份，避免沿用会前过时凭据。 */
    private fun continueStartMeeting() {
        if (deploymentLanguages.isEmpty()) { statusView.text = "当前部署尚未声明会议语言"; return }
        val currentDiscovery = discovery
        if (resultGroup.checkedRadioButtonId == -1 || currentDiscovery == null) {
            statusView.text = "当前部署没有可用的已装配结果动作"; startButton.isEnabled = true; setConfigurationEnabled(true); return
        }
        val setup = selectedSetup()
        if (!deploymentCapabilities.containsAll(setup.toOptions().requiredCapabilities())) {
            statusView.text = "本场选择的字幕／互译或纪要功能当前不可用"; return
        }
        val settings = resultSettings[setup.resultOptions().actionId()]
        val preparation = MeetingStartConditions.prepare(startCondition, setup, currentDiscovery, settings?.freeze()?.get())
        if (!preparation.allowed()) {
            Log.i(TAG, "会前结果动作准备未满足，会议未启动")
            statusView.text = preparation.message(); startButton.isEnabled = true; setConfigurationEnabled(true); return
        }
        val prepared = preparation.meeting()!!
        if (setup.presentationCapture()) {
            pendingPresentationMeeting = prepared; startButton.isEnabled = false; setConfigurationEnabled(false)
            statusView.text = "请在系统弹窗中授权共享整个屏幕"
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                startActivityForResult(permission, PRESENTATION_PERMISSION_REQUEST)
            } catch (failure: RuntimeException) {
                pendingPresentationMeeting = null; MeetingAppAssembly.clearPreparedMeeting()
                startButton.isEnabled = true; setConfigurationEnabled(true); statusView.text = "无法打开系统屏幕采集授权弹窗"
                Log.e(TAG, "请求屏幕采集授权失败", failure)
            }
            return
        }
        startMeeting(prepared, 0, null)
    }
    private fun startMeeting(prepared: MeetingAppAssembly.PreparedMeeting, projectionResultCode: Int, projectionData: Intent?) {
        startButton.isEnabled = false; setConfigurationEnabled(false); statusView.text = "正在创建会议…"
        MeetingAppAssembly.handoff(prepared)
        val begin = Intent(this, MeetingService::class.java).setAction(MeetingService.ACTION_BEGIN)
        if (projectionData != null) {
            begin.putExtra(MeetingService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
            begin.putExtra(MeetingService.EXTRA_PROJECTION_DATA, projectionData)
        }
        stopButton.contentDescription = "停止会议"
        try { startForegroundService(begin) }
        catch (failure: RuntimeException) {
            MeetingAppAssembly.clearPreparedMeeting(); Log.e(TAG, "启动会议服务失败 type=${failure.javaClass.simpleName}")
            statusView.text = "无法启动会议服务，请检查麦克风权限"; startButton.isEnabled = true; setConfigurationEnabled(true)
        }
    }
    private fun selectedSetup(): MeetingSetup {
        val mode = if (translationModeView.isChecked) MeetingSetup.Mode.LIVE_TRANSLATION else MeetingSetup.Mode.LIVE_TRANSCRIPT
        return MeetingSetup(mode, deploymentLanguages[sourceLanguageView.selectedItemPosition], deploymentLanguages[targetLanguageView.selectedItemPosition],
            mode == MeetingSetup.Mode.LIVE_TRANSCRIPT && ScreenCaptureSourceFactory.isSupported() && presentationCaptureView.isChecked,
            ResultOptions.action(resultActionIds[resultGroup.checkedRadioButtonId]!!), "", emptyList(), summaryEnabledView.isChecked)
    }

    /** Service 推送不可变状态，Activity 重建直接重绘整场字幕，不重订模型事件。 */
    private fun renderMeetingState(state: MeetingService.State) {
        meetingBusy = state.busy; hasReadyResult = state.readyResult != null
        val failedAction = state.actionOutcome?.status() == ResultActionOutcome.Status.FAILED
        retryActionButton.visibility = if (failedAction) View.VISIBLE else View.GONE
        endResultButton.visibility = if (failedAction) View.VISIBLE else View.GONE
        statusView.text = state.status; capabilityView.text = state.capabilityText
        page.showMeeting(state.active); page.showPresentationNotice(state.active, state.presentationNotice)
        startButton.isEnabled = displayedIdentity != null && !state.busy && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT) && deploymentLanguages.isNotEmpty()
        setConfigurationEnabled(!state.busy); stopButton.isEnabled = state.canStop
        overlayButton.contentDescription = if (state.overlayShowing) "隐藏悬浮字幕" else "显示悬浮字幕"
        elapsedView.text = state.timeText.split(" · ")[0].replace("已进行 ", "")
        val audioLevel = state.audioLevel?.level() ?: 0
        audioLevelBar.progress = audioLevel; page.voiceLevel.setLevel(audioLevel)
        audioLevelView.text = if (MeetingRuntimeFactory.requiresMicrophone()) "输入电平：$audioLevel/100" else "输入电平：Mock 未实采"
        reminderPopup.render(state.reminder)
        if (renderedTranscriptVersion != state.transcriptVersion) {
            val followSingle = page.followsLatest(R.id.transcript_scroll)
            val followLeft = page.followsLatest(R.id.left_scroll); val followRight = page.followsLatest(R.id.right_scroll)
            if (state.display.rows().isEmpty()) { transcriptRows.clear(); transcriptList.removeAllViews(); transcriptList.addView(transcriptView) }
            for (source in state.display.rows()) {
                val segment = DisplaySnapshotPresentation.subtitle(source) ?: continue
                val row = transcriptRows.getOrPut(segment.segmentId()) { page.textRow(28f, false).also { transcriptList.addView(it) } }
                SubtitleViewFactory.render(row, segment.segmentId(), segment.text(), segment.alpha())
            }
            if (state.display.bilingual()) {
                page.leftList.removeAllViews(); page.rightList.removeAllViews()
                for (source in state.display.rows()) {
                    val segment = DisplaySnapshotPresentation.speakerColumn(source, state.display.swapped())
                    if (segment.side() == MeetingDisplaySnapshot.Side.UNRESOLVED) continue
                    val column = if (segment.side() == MeetingDisplaySnapshot.Side.A) page.leftList else page.rightList
                    val left = page.textRow(26f, false)
                    SubtitleViewFactory.render(left, segment.segmentId(), segment.source(), segment.sourceAlpha()); column.addView(left)
                    val right = page.textRow(26f, true)
                    SubtitleViewFactory.render(right, segment.segmentId(), segment.translated(), segment.translatedAlpha()); column.addView(right)
                }
            }
            renderedTranscriptVersion = state.transcriptVersion
            page.scrollLatest(R.id.transcript_scroll, followSingle); page.scrollLatest(R.id.left_scroll, followLeft); page.scrollLatest(R.id.right_scroll, followRight)
        }
        val bilingual = state.display.bilingual()
        val pendingSide = bilingual && state.display.rows().isNotEmpty() && state.display.rows().last().sourceSide() == MeetingDisplaySnapshot.Side.UNRESOLVED
        meetingTitle.text = if (bilingual) {
            if (pendingSide) "AI双语互译 · 语言识别中…" else "AI双语互译"
        } else if (state.summaryEnabled) "AI会议纪要" else "AI字幕"
        leftLanguageTitle.text = "左侧发言"; rightLanguageTitle.text = "右侧发言"; page.showBilingual(bilingual)
        transcriptView.visibility = if (!bilingual && transcriptRows.isEmpty()) View.VISIBLE else View.GONE
        // 插件提供的已准备 UI 操作只在可见页面消费，不根据动作类型分支。
        if (resumed) while (true) { val operation = meetingService?.takeUiOperation() ?: break; operation.run() }
    }
    private fun toggleOverlay() {
        val service = meetingService
        if (service == null) { statusView.text = "会议服务正在连接，请稍后重试"; return }
        if (!Settings.canDrawOverlays(this)) {
            statusView.text = "请授予悬浮窗权限，返回会议页后再次点击显示"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        service.toggleOverlay()
    }
    private fun setConfigurationEnabled(enabled: Boolean) {
        val available = enabled && !meetingBusy && pendingPresentationMeeting == null
        loginButton.isEnabled = available; settingsButton.isEnabled = available
        loginDialog?.takeIf { it.isShowing }?.findViewById<View>(R.id.login_submit)?.isEnabled = available
    }
    override fun onStart() { super.onStart(); serviceBound = bindService(Intent(this, MeetingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE) }
    override fun onResume() { super.onResume(); resumed = true; meetingService?.observe(observer); refreshIdentity(deploymentCapabilities.isEmpty()) }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        if (serviceBound) { meetingService?.unobserve(observer); unbindService(serviceConnection); serviceBound = false; meetingService = null }
        super.onStop()
    }
    override fun onDestroy() { preMeetingClient?.close(); super.onDestroy() }
    companion object {
        private const val TAG = "AiMeetingReference"
        private const val MIC_PERMISSION_REQUEST = 1001
        private const val PRESENTATION_PERMISSION_REQUEST = 1002
        private const val CONNECTION_SETTINGS_REQUEST = 1003
    }
}
