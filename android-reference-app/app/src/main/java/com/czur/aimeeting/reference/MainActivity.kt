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
import android.view.Gravity
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
    private val observer = object : MeetingService.Observer {
        override fun onMeetingState(state: MeetingService.State) = renderMeetingState(state)
        override fun captureWindowTokens(): Array<IBinder?> {
            // 截图时读取实际已挂载窗口，不缓存 Activity 重建前的句柄。
            val views = listOfNotNull(window.decorView, loginDialog?.takeIf { it.isShowing }?.window?.decorView)
            return views.filter { it.isAttachedToWindow }.map { it.windowToken }.toTypedArray()
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            meetingService = (binder as MeetingService.LocalBinder).service()
            observeVisibleMeeting()
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
    /** 仅缓存本页面已画出的文字，跳过未变化历史的排版；不保存或修改 SDK 事件。 */
    private val renderedSubtitleRows = linkedMapOf<String, DisplaySnapshotPresentation.SubtitleRow>()
    private class BilingualRowViews(val source: TextView, val translated: TextView,
        var rendered: DisplaySnapshotPresentation.SpeakerColumnRow? = null)
    private val bilingualRows = linkedMapOf<String, BilingualRowViews>()
    private lateinit var elapsedView: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var audioLevelView: TextView
    private lateinit var reminderPopup: MeetingReminderPopup
    private lateinit var statusView: TextView
    private var renderedServiceStatus = ""
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
    /** 只用于页面入口，开始会议仍重新读取 Provider 的真实凭据。 */
    private var displayedIdentity: IdentitySession? = null
    private var meetingBusy = false
    private var deploymentCapabilities: Set<String> = emptySet()
    private var deploymentLanguages: List<String> = emptyList()
    private var discovery: MeetingDiscovery? = null
    private var pendingPresentationMeeting: MeetingAppAssembly.PreparedMeeting? = null
    private var hasReadyResult = false
    private lateinit var setupPreferences: MeetingSetupPreferenceStore
    /** 页面与开始入口消费同一份选择，程序化重绘不作为用户操作写入偏好。 */
    private var setupSelection = MeetingSetupSelection()
    private var bindingSetupViews = false
    private var availableResultActions: Set<String> = emptySet()
    private var defaultDimAmount = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        defaultDimAmount = window.attributes.dimAmount
        // 地址由 APK 编译配置装配；默认登录插件按同后端身份配对恢复会话。
        MeetingAppAssembly.initialize(this)
        setupPreferences = MeetingSetupPreferenceStore(this)
        startCondition = MeetingStartConditions.create(applicationContext)
        page = MeetingAppAssembly.pageFactory().create(this)
        // 关闭入口只离开设置页，会议的生命周期仍归原 Service 所有。
        page.close.setOnClickListener { finish() }
        loginButton = page.account; resultGroup = page.results
        transcriptView = page.placeholder; meetingTitle = page.meetingTitle; transcriptList = page.transcriptList
        leftLanguageTitle = page.leftTitle; rightLanguageTitle = page.rightTitle; elapsedView = page.elapsed
        audioLevelBar = page.level; audioLevelView = page.levelText; statusView = page.status
        capabilityView = page.capabilities; sourceLanguageView = page.sourceLanguage; targetLanguageView = page.targetLanguage
        presentationCaptureView = page.presentation; summaryEnabledView = page.summary
        transcriptModeView = page.transcriptMode; translationModeView = page.translationMode
        startButton = page.start; stopButton = page.stop; overlayButton = page.overlay
        swapColumnsButton = page.swapColumns; retryActionButton = page.retry; endResultButton = page.endResult
        loginButton.setOnClickListener {
            MeetingAppAssembly.loginEntry().open(this,
                defaultEntry = { showDefaultAccountEntry() },
                onIdentityChanged = {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            // 伙伴可连同入口替换 Provider，重建会前客户端以读取新的装配。
                            preMeetingClient?.close(); preMeetingClient = null
                            refreshIdentity(true)
                        }
                    }
                })
        }
        page.modes.setOnCheckedChangeListener { _, checkedId ->
            if (bindingSetupViews) return@setOnCheckedChangeListener
            val mode = if (checkedId == translationModeView.id) MeetingSetup.Mode.LIVE_TRANSLATION else MeetingSetup.Mode.LIVE_TRANSCRIPT
            setupSelection = setupPreferences.read(mode).availableIn(deploymentLanguages, summaryCapabilityAvailable())
            saveAndRenderSetupSelection()
        }
        summaryEnabledView.setOnCheckedChangeListener { _, checked ->
            if (!bindingSetupViews) {
                setupSelection = setupSelection.copy(summaryEnabled = checked)
                saveAndRenderSetupSelection()
            }
        }
        sourceLanguageView.onItemSelectedListener = languageSelectionListener(true)
        targetLanguageView.onItemSelectedListener = languageSelectionListener(false)
        page.swapLanguages.setOnClickListener {
            if (setupSelection.mode == MeetingSetup.Mode.LIVE_TRANSLATION && deploymentLanguages.size >= 2) {
                setupSelection = setupSelection.swapLanguages()
                saveAndRenderSetupSelection()
            }
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
            updateStartAvailability()
        }
        refreshResultAvailability()
        startButton.setOnClickListener { requestMeetingPermissions() }
        stopButton.setOnClickListener { meetingService?.requestStop() }
        page.stopConfirmation.setOnClickListener { meetingService?.requestStop() }
        swapColumnsButton.setOnClickListener { meetingService?.swapDisplayColumns() }
        overlayButton.setOnClickListener { toggleOverlay() }
        retryActionButton.setOnClickListener { meetingService?.retryResultAction() }
        endResultButton.setOnClickListener { meetingService?.endResultInteraction() }
        audioLevelView.text = if (MeetingRuntimeFactory.requiresMicrophone()) "输入电平：等待输入" else "输入电平：Mock 未实采"
        reminderPopup = MeetingReminderPopup(this, false, Runnable { meetingService?.confirmReminder() })
        page.root.addView(reminderPopup.view(), FrameLayout.LayoutParams(-1, -1))
        setContentView(page.root)
        window.decorView.setPadding(0, 0, 0, 0)
        window.setGravity(Gravity.CENTER)
        renderWindowSize(false)
        setFinishOnTouchOutside(true)
        if (MeetingAppAssembly.serverUrl().isEmpty()) statusView.setText(R.string.reference_deployment_not_configured)
    }

    /**
     * 默认账号入口：首次登录直接打开表单，已有身份时明确区分切换与退出。
     *
     * 打开菜单或表单不改变 Provider；只有用户明确退出才清理本地会话。新账号登录
     * 成功后，既有 MeetingClient.login 会更新同一个 Provider，无需在 App 备份令牌
     * 或增加第二份账号状态。伙伴自定义 AppLoginEntry 不会经过本默认菜单。
     */
    private fun showDefaultAccountEntry() {
        if (MeetingAppAssembly.serverUrl().isEmpty()) {
            statusView.setText(R.string.reference_deployment_not_configured)
            return
        }
        if (displayedIdentity == null) {
            showLoginDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("账号操作")
            .setItems(arrayOf("切换账号", "退出登录")) { _, which ->
                if (which == 0) showLoginDialog() else logout()
            }
            .show()
    }

    /**
     * 独立参考账号表单，不依赖系统账号。取消和登录失败保留原有效身份及能力，
     * 成功后由客户端替换会话并重新发现部署能力；密码不保存。
     */
    private fun showLoginDialog() {
        val fields = LayoutInflater.from(this).inflate(R.layout.reference_login_dialog, null)
        ReferenceUiScale.bindDialog(fields)
        usernameView = fields.findViewById(R.id.login_username)
        passwordView = fields.findViewById(R.id.login_password)
        fields.findViewById<TextView>(R.id.login_title).text = if (displayedIdentity == null) "账号登录" else "切换账号"
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
        if (baseUrl.isEmpty()) { loginMessage(getString(R.string.reference_deployment_not_configured)); return }
        if (username.isEmpty() || password.isEmpty()) { loginMessage("请填写账号和密码"); return }
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
        if (MeetingAppAssembly.serverUrl().isEmpty()) {
            statusView.setText(R.string.reference_deployment_not_configured)
            startButton.isEnabled = false
            return
        }
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
            deploymentCapabilities = emptySet(); discovery = null; refreshLanguageOptions(emptyList())
            if (!meetingBusy && !hasReadyResult) statusView.text = identity.message()
        }
        refreshResultAvailability(); refreshModeAvailability()
        updateStartAvailability()
        Log.d(TAG, "页面身份刷新 state=${identity.status()}")
    }
    private fun discoverCapabilities() {
        preMeetingClient!!.discover(object : MeetingClient.Callback<MeetingDiscovery> {
            override fun onSuccess(discovery: MeetingDiscovery) {
                deploymentCapabilities = discovery.availableCapabilities()
                this@MainActivity.discovery = discovery
                refreshLanguageOptions(discovery.deployment().languages())
                refreshResultAvailability(); refreshModeAvailability(); setConfigurationEnabled(!meetingBusy)
                updateStartAvailability()
                if (!meetingBusy && !hasReadyResult) {
                    statusView.text = when {
                        !deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT) ->
                            "当前部署未提供实时字幕能力，暂时无法开始会议"
                        deploymentLanguages.isEmpty() -> "当前部署尚未声明会议语言"
                        else -> "登录成功，可开始会议"
                    }
                }
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
        deploymentCapabilities = emptySet(); discovery = null; refreshLanguageOptions(emptyList())
        refreshResultAvailability(); refreshModeAvailability(); loginButton.text = "账号登录"
        startButton.isEnabled = false; statusView.text = "已退出，请重新登录"
        Log.i(TAG, "参考账号已退出")
    }
    private fun refreshModeAvailability() {
        val transcript = transcriptCapabilityAvailable()
        val summary = transcript && summaryCapabilityAvailable()
        val translation = transcript && translationCapabilityAvailable()
        summaryEnabledView.text = "生成会议纪要"
        summaryEnabledView.contentDescription = "生成会议纪要"
        translationModeView.text = "AI双语互译"; translationModeView.alpha = 1f
        // 页面会在能力不足时取消选项；这种显示回退不能覆盖用户保存的正常选择。
        val wasBinding = bindingSetupViews
        bindingSetupViews = true
        try { page.showCapabilityOptions(summary, translation, transcript) }
        finally { bindingSetupViews = wasBinding }
        targetLanguageView.isEnabled = translation && translationModeView.isChecked
        val screenCapture = ScreenCaptureSourceFactory.isSupported()
        presentationCaptureView.text = if (screenCapture) "演示文稿" else "演示文稿（Mock不支持截图）"
        presentationCaptureView.contentDescription = if (screenCapture) "演示文稿，开始时申请系统屏幕采集授权" else "Mock构建不提供截图，请使用SDK构建"
        presentationCaptureView.isEnabled = screenCapture && !translationModeView.isChecked
        if (!screenCapture) presentationCaptureView.isChecked = false
        page.showSetupMode(translationModeView.isChecked, summary, transcript)
    }
    /** 实时字幕是所有参考会议的必选能力；未发现部署结果前保留默认会前页面。 */
    private fun transcriptCapabilityAvailable(): Boolean =
        displayedIdentity == null || discovery == null || deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT)
    private fun summaryCapabilityAvailable(): Boolean =
        displayedIdentity != null && deploymentCapabilities.contains(MeetingCapabilityState.SUMMARY)
    private fun translationCapabilityAvailable(): Boolean = displayedIdentity != null &&
        deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT) &&
        deploymentCapabilities.contains(MeetingCapabilityState.TRANSLATION) && deploymentLanguages.size >= 2

    /** 部署发现后再恢复对应模式的合法偏好；无翻译能力时只临时显示字幕模式。 */
    private fun refreshLanguageOptions(languages: List<String>) {
        deploymentLanguages = languages.toList()
        val preferredMode = setupPreferences.mode()
        val mode = if (preferredMode == MeetingSetup.Mode.LIVE_TRANSLATION && !translationCapabilityAvailable())
            MeetingSetup.Mode.LIVE_TRANSCRIPT else preferredMode
        setupSelection = setupPreferences.read(mode).availableIn(deploymentLanguages, summaryCapabilityAvailable())
        renderSetupSelection(updateLanguages = true)
        Log.d(TAG, "会议语言选项更新 count=${deploymentLanguages.size}")
    }

    /** 用户操作才持久化，字幕与互译的语言／纪要分别记忆，不保存模型或身份材料。 */
    private fun saveAndRenderSetupSelection() {
        setupPreferences.save(setupSelection)
        renderSetupSelection()
        Log.d(TAG, "会前选择已保存 mode=${setupSelection.mode} summary=${setupSelection.summaryEnabled}")
    }

    private fun renderSetupSelection(updateLanguages: Boolean = false) {
        bindingSetupViews = true
        try {
            if (updateLanguages) {
                val labels = deploymentLanguages.map { code ->
                    val label = if (code.startsWith("en")) "English" else Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
                    label.ifEmpty { code }
                }
                sourceLanguageView.adapter = languageAdapter(labels, sourceLanguageView)
                targetLanguageView.adapter = languageAdapter(labels, targetLanguageView)
            }
            val translation = setupSelection.mode == MeetingSetup.Mode.LIVE_TRANSLATION
            page.modes.check(if (translation) translationModeView.id else transcriptModeView.id)
            summaryEnabledView.isChecked = setupSelection.summaryEnabled
            if (deploymentLanguages.isNotEmpty()) {
                sourceLanguageView.setSelection(deploymentLanguages.indexOf(setupSelection.sourceLanguage))
                targetLanguageView.setSelection(maxOf(0, deploymentLanguages.indexOf(setupSelection.targetLanguage)))
            }
            sourceLanguageView.isEnabled = deploymentLanguages.isNotEmpty()
            if (translation) presentationCaptureView.isChecked = false
            refreshModeAvailability()
        } finally { bindingSetupViews = false }
        updateStartAvailability()
    }

    private fun languageSelectionListener(sourceSide: Boolean) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            // Spinner 的批量 setSelection 可能延后回调；只消费当前可见且真正改变的选择。
            if (bindingSetupViews || discovery == null || position != parent.selectedItemPosition) return
            // 字幕模式的目标控件已隐藏，单语部署的默认选中回调不能改写互译偏好。
            if (!sourceSide && setupSelection.mode != MeetingSetup.Mode.LIVE_TRANSLATION) return
            val language = deploymentLanguages.getOrNull(position) ?: return
            val previous = if (sourceSide) setupSelection.sourceLanguage else setupSelection.targetLanguage
            if (language == previous) return
            setupSelection = setupSelection.selectLanguage(sourceSide, language)
            saveAndRenderSetupSelection()
        }
        override fun onNothingSelected(parent: AdapterView<*>) = Unit
    }

    private fun canStartMeeting(): Boolean = setupSelection.canStart(displayedIdentity != null,
        meetingBusy || pendingPresentationMeeting != null, deploymentCapabilities, deploymentLanguages,
        discovery != null && resultActionIds[resultGroup.checkedRadioButtonId] in availableResultActions)

    /** 页面恢复、会前改动、Service 状态和实际开始入口共用此规则。 */
    private fun updateStartAvailability() { startButton.isEnabled = canStartMeeting() }
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
        availableResultActions = available
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
        updateStartAvailability()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestMeetingPermissions()
        else statusView.text = "需要麦克风权限才能进行真实转写"
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) createAndStartMeeting()
            else statusView.text = "需要悬浮窗权限才能在其他应用上方显示会议字幕，会议未开始"
            return
        }
        if (requestCode != PRESENTATION_PERMISSION_REQUEST) return
        val approved = pendingPresentationMeeting
        pendingPresentationMeeting = null
        if (approved == null) return
        if (resultCode != RESULT_OK || data == null) {
            MeetingAppAssembly.clearPreparedMeeting(); statusView.text = "未授予系统屏幕采集权限，会议未开始"
            updateStartAvailability(); setConfigurationEnabled(true); return
        }
        startMeeting(approved, resultCode, data)
    }
    /** 权限由 App 在建会前申请；SDK 只负责窗口，缺少权限时不创建付费会议。 */
    private fun requestMeetingPermissions() {
        if (MeetingRuntimeFactory.requiresMicrophone() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
        } else if (!Settings.canDrawOverlays(this)) {
            statusView.text = "请允许在其他应用上层显示，返回后继续开始会议"
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), OVERLAY_PERMISSION_REQUEST)
        } else createAndStartMeeting()
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
        if (!canStartMeeting()) {
            statusView.text = when {
                displayedIdentity == null -> "请先登录"
                meetingBusy || pendingPresentationMeeting != null -> "本场会议仍在处理中"
                MeetingCapabilityState.TRANSCRIPT !in deploymentCapabilities -> "当前部署未提供实时字幕能力，暂时无法开始会议"
                deploymentLanguages.isEmpty() -> "当前部署尚未声明会议语言"
                setupSelection.mode == MeetingSetup.Mode.LIVE_TRANSLATION &&
                    setupSelection.sourceLanguage == setupSelection.targetLanguage -> "请选择两种不同的会议语言"
                discovery == null || resultActionIds[resultGroup.checkedRadioButtonId] !in availableResultActions ->
                    "当前部署没有可用的已装配结果动作"
                else -> "本场选择的字幕／互译或纪要功能当前不可用"
            }
            updateStartAvailability(); setConfigurationEnabled(true); return
        }
        val currentDiscovery = discovery!!
        val setup = selectedSetup()
        val settings = resultSettings[setup.resultOptions().actionId()]
        val preparation = MeetingStartConditions.prepare(startCondition, setup, currentDiscovery, settings?.freeze()?.get())
        if (!preparation.allowed()) {
            Log.i(TAG, "会前结果动作准备未满足，会议未启动")
            statusView.text = preparation.message(); updateStartAvailability(); setConfigurationEnabled(true); return
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
                updateStartAvailability(); setConfigurationEnabled(true); statusView.text = "无法打开系统屏幕采集授权弹窗"
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
            statusView.text = "无法启动会议服务，请检查麦克风权限"; updateStartAvailability(); setConfigurationEnabled(true)
        }
    }
    private fun selectedSetup(): MeetingSetup {
        return MeetingSetup(setupSelection.mode, setupSelection.sourceLanguage, setupSelection.targetLanguage,
            setupSelection.mode == MeetingSetup.Mode.LIVE_TRANSCRIPT && ScreenCaptureSourceFactory.isSupported() && presentationCaptureView.isChecked,
            ResultOptions.action(resultActionIds[resultGroup.checkedRadioButtonId]!!), "", emptyList(), setupSelection.summaryEnabled)
    }

    /** Service 推送不可变状态，Activity 重建直接重绘整场字幕，不重订模型事件。 */
    private fun renderMeetingState(state: MeetingService.State) {
        // 默认会中入口属于 Service 的真悬浮窗。挂载成功后退出设置 Activity，释放
        // 它的整页输入区域与暗化层；只有从浮窗时钟显式打开详情时才保留历史页面。
        if (resumed && state.active && state.overlayShowing && !intent.getBooleanExtra(EXTRA_MEETING_DETAILS, false)) {
            if (!isFinishing) {
                Log.i(TAG, "会议已交给跨应用悬浮窗，会前页面退出")
                finish()
            }
            return
        }
        meetingBusy = state.busy; hasReadyResult = state.readyResult != null
        val failedAction = state.actionOutcome?.status() == ResultActionOutcome.Status.FAILED
        retryActionButton.visibility = if (failedAction) View.VISIBLE else View.GONE
        endResultButton.visibility = if (failedAction) View.VISIBLE else View.GONE
        // 新空闲 Service 不带上场消息；清掉它此前画出的收尾文案，保留页面更新的身份／部署提示。
        if (state.status.isNotBlank()) statusView.text = state.status
        else if (statusView.text.toString() == renderedServiceStatus) statusView.text = ""
        renderedServiceStatus = state.status
        capabilityView.text = state.capabilityText
        page.showMeeting(state.active); page.showPresentationNotice(state.active, state.presentationNotice)
        // Dialog 的背景暗化层独立于页面窗口，单独排窗无法移除它。演示采集期间（含
        // 停止末帧）不暗化真实内容；采集关闭后恢复原会前风格，不在截图回调中切 UI。
        val dimAmount = if (state.capturingPresentation) 0f else defaultDimAmount
        if (window.attributes.dimAmount != dimAmount) window.setDimAmount(dimAmount)
        renderWindowSize(state.active)
        page.showStopConfirmation(!state.active && state.canStop)
        updateStartAvailability()
        setConfigurationEnabled(!state.busy); stopButton.isEnabled = state.canStop
        overlayButton.contentDescription = "收起详情，返回悬浮字幕"
        elapsedView.text = state.timeText.split(" · ")[0].replace("已进行 ", "")
        val audioLevel = state.audioLevel?.level() ?: 0
        audioLevelBar.progress = audioLevel; page.voiceLevel.setLevel(audioLevel)
        audioLevelView.text = if (MeetingRuntimeFactory.requiresMicrophone()) "输入电平：$audioLevel/100" else "输入电平：Mock 未实采"
        reminderPopup.render(state.reminder)
        if (renderedTranscriptVersion != state.transcriptVersion) {
            val followSingle = page.followsLatest(R.id.transcript_scroll)
            val followLeft = page.followsLatest(R.id.left_scroll); val followRight = page.followsLatest(R.id.right_scroll)
            renderTranscriptRows(state.display)
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

    /**
     * 一次快照只更新变化的可见单元。连续临时句不会重新创建整场 TextView，也不会让
     * 未改变的历史重复 setParts／排版；纯电平和计时推送仍由原 transcriptVersion 门禁跳过。
     * 显示顺序、原译配对和实际物理侧继续直接消费 SDK 投影，不在这里重新判断 DOA／语种。
     */
    private fun renderTranscriptRows(display: MeetingDisplaySnapshot) {
        if (!display.bilingual()) {
            bilingualRows.clear()
            reconcileRows(page.leftList, emptyList()); reconcileRows(page.rightList, emptyList())
            val projected = display.rows().mapNotNull(DisplaySnapshotPresentation::subtitle)
            val retained = projected.mapTo(mutableSetOf()) { it.segmentId() }
            transcriptRows.keys.retainAll(retained); renderedSubtitleRows.keys.retainAll(retained)
            val desired = mutableListOf<View>(transcriptView)
            for (segment in projected) {
                val view = transcriptRows.getOrPut(segment.segmentId()) { page.textRow(28f, false) }
                if (renderedSubtitleRows[segment.segmentId()] != segment) {
                    SubtitleViewFactory.render(view, segment.segmentId(), segment.text(), segment.alpha())
                    renderedSubtitleRows[segment.segmentId()] = segment
                }
                desired += view
            }
            reconcileRows(transcriptList, desired)
            return
        }
        // 互译只画同侧的原译对，不同时为隐藏的单语列表构建整场历史。
        transcriptRows.clear(); renderedSubtitleRows.clear()
        reconcileRows(transcriptList, listOf(transcriptView))
        val left = mutableListOf<View>()
        val right = mutableListOf<View>()
        val retained = mutableSetOf<String>()
        for (row in display.rows()) {
            val segment = DisplaySnapshotPresentation.speakerColumn(row, display.swapped())
            if (segment.side() == MeetingDisplaySnapshot.Side.UNRESOLVED) continue
            retained += segment.segmentId()
            val views = bilingualRows.getOrPut(segment.segmentId()) {
                BilingualRowViews(page.textRow(26f, false), page.textRow(26f, true))
            }
            val previous = views.rendered
            if (previous == null || previous.source() != segment.source() || previous.sourceAlpha() != segment.sourceAlpha())
                SubtitleViewFactory.render(views.source, segment.segmentId(), segment.source(), segment.sourceAlpha())
            if (previous == null || previous.translated() != segment.translated() || previous.translatedAlpha() != segment.translatedAlpha())
                SubtitleViewFactory.render(views.translated, segment.segmentId(), segment.translated(), segment.translatedAlpha())
            views.rendered = segment
            val column = if (segment.side() == MeetingDisplaySnapshot.Side.A) left else right
            column += views.source; column += views.translated
        }
        bilingualRows.keys.retainAll(retained)
        reconcileRows(page.leftList, left); reconcileRows(page.rightList, right)
    }

    /**
     * 顺序不变时不触碰 View 树。新增段才挂载；分栏／交换时移动同一对 View；
     * 快照清空时只移除页面内容，SDK 的标准事件和最终结果完全不在此处处理。
     */
    private fun reconcileRows(column: LinearLayout, desired: List<View>) {
        for ((index, view) in desired.withIndex()) {
            if (column.getChildAt(index) === view) continue
            (view.parent as? ViewGroup)?.removeView(view)
            column.addView(view, index)
        }
        while (column.childCount > desired.size) column.removeViewAt(column.childCount - 1)
    }

    private fun toggleOverlay() {
        val service = meetingService
        if (service == null) { statusView.text = "会议服务正在连接，请稍后重试"; return }
        if (!Settings.canDrawOverlays(this)) {
            statusView.text = "请授予悬浮窗权限，返回会议页后再次点击显示"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        if (service.showOverlay()) finish()
    }
    private fun setConfigurationEnabled(enabled: Boolean) {
        val available = enabled && !meetingBusy && pendingPresentationMeeting == null
        loginButton.isEnabled = available
        page.close.isEnabled = available
        // 会前允许点击外围退出；创建、授权或收尾中保持当前操作入口。
        setFinishOnTouchOutside(available)
        loginDialog?.takeIf { it.isShowing }?.findViewById<View>(R.id.login_submit)?.isEnabled = available
    }

    /**
     * 同一宿主随既有会中状态切换窗口：会前沿用国内白卡，会中容纳原字幕大面板。
     *
     * 不以小窗口的 root 作为设计比例，也不复制会议状态；电平与计时频繁推送时，
     * 仅在目标宽高真正变化后通知 WindowManager，避免每帧重复触发窗口重排。
     */
    private fun renderWindowSize(active: Boolean) {
        val width = ReferenceUiScale.px(this, if (active) 1800 else 988)
        val height = ReferenceUiScale.px(this, if (active) 1008 else 856)
        if (window.attributes.width != width || window.attributes.height != height) window.setLayout(width, height)
    }
    override fun onStart() { super.onStart(); serviceBound = bindService(Intent(this, MeetingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE) }
    /** 单实例入口避免反复点击图标叠出旧页面；只让前台 Activity 观察同一场快照。 */
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
    private fun observeVisibleMeeting() {
        if (!resumed || isFinishing) return
        val service = meetingService ?: return
        if (!intent.getBooleanExtra(EXTRA_MEETING_DETAILS, false)) service.showOverlay()
        service.observe(observer)
    }
    override fun onResume() {
        super.onResume(); resumed = true; observeVisibleMeeting()
        if (!isFinishing) refreshIdentity(deploymentCapabilities.isEmpty())
    }
    override fun onPause() { resumed = false; meetingService?.unobserve(observer); super.onPause() }
    override fun onStop() {
        if (serviceBound) { meetingService?.unobserve(observer); unbindService(serviceConnection); serviceBound = false; meetingService = null }
        super.onStop()
    }
    override fun onDestroy() { preMeetingClient?.close(); super.onDestroy() }
    companion object {
        const val EXTRA_MEETING_DETAILS = "meetingDetails"
        private const val OVERLAY_PERMISSION_REQUEST = 1003
        private const val TAG = "AiMeetingReference"
        private const val MIC_PERMISSION_REQUEST = 1001
        private const val PRESENTATION_PERMISSION_REQUEST = 1002
    }
}
