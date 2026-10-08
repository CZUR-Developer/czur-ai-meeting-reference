package com.czur.aimeeting.reference;

import android.app.Activity;
import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ProgressBar;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingDiscovery;
import com.czur.aimeeting.client.result.ResultOptions;
import com.czur.aimeeting.client.result.ResultActionOutcome;
import com.czur.aimeeting.client.server.ReferenceLoginSession;
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.client.MeetingCapabilityState;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;
import com.czur.aimeeting.client.identity.IdentityResult;
import com.czur.aimeeting.client.identity.IdentitySession;
import com.czur.aimeeting.reference.meeting.MeetingStartCondition;
import com.czur.aimeeting.reference.meeting.MeetingStartConditions;
import com.czur.aimeeting.reference.ui.SubtitleViewFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** 参考会议入口：App 保留登录、会前设置和 UI，会议业务经完整客户端使用。 */
public final class MainActivity extends Activity {
    private static final String TAG = "AiMeetingReference";
    private static final int MIC_PERMISSION_REQUEST = 1001;
    private static final int PRESENTATION_PERMISSION_REQUEST = 1002;
    private static final int CONNECTION_SETTINGS_REQUEST = 1003;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MeetingService.Observer observer = this::renderMeetingState;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            meetingService = ((MeetingService.LocalBinder) binder).service();
            meetingService.observe(observer);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            meetingService = null;
        }
    };
    /** 会前发现/登录专用；与 Service 的会议客户端分开，离开页面不会关闭持续会议。 */
    private MeetingClient preMeetingClient;
    private MeetingStartCondition startCondition;
    private MeetingService meetingService;
    private boolean serviceBound;
    private MeetingPageViews page;
    private android.app.AlertDialog loginDialog;
    private int renderedTranscriptVersion = -1;
    private EditText usernameView;
    private EditText passwordView;
    private RadioGroup resultGroup;
    private final Map<Integer, String> resultActionIds = new LinkedHashMap<>();
    private final Map<String, AppResultActionPlugin.Settings> resultSettings = new LinkedHashMap<>();
    private TextView transcriptView;
    private TextView meetingTitle;
    private LinearLayout transcriptList;
    private TextView leftLanguageTitle;
    private TextView rightLanguageTitle;
    private final Map<String, TextView> transcriptRows = new LinkedHashMap<>();
    private TextView elapsedView;
    private ProgressBar audioLevelBar;
    private TextView audioLevelView;
    private MeetingReminderPopup reminderPopup;
    private TextView statusView;
    private TextView capabilityView;
    private Spinner sourceLanguageView;
    private Spinner targetLanguageView;
    private CheckBox presentationCaptureView;
    private CheckBox summaryEnabledView;
    private RadioButton transcriptModeView;
    private RadioButton translationModeView;

    private Button startButton;
    private Button retryActionButton;
    private Button endResultButton;
    private boolean resumed;
    private Button stopButton;
    private Button overlayButton;
    private Button swapColumnsButton;
    private Button loginButton;
    private Button settingsButton;
    /** 只用于页面展示／入口开关；Service开始时仍通过Provider重新获取真实凭据。 */
    private IdentitySession displayedIdentity;
    private boolean meetingBusy;
    private Set<String> deploymentCapabilities = Set.of();
    /** 按后端声明顺序保存code，Spinner标签仅负责呈现，不硬编码模型语言。 */
    private List<String> deploymentLanguages = List.of();
    private MeetingDiscovery discovery;
    private MeetingAppAssembly.PreparedMeeting pendingPresentationMeeting;
    private boolean hasReadyResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startCondition = MeetingStartConditions.create(getApplicationContext());

        page = MeetingAppAssembly.pageFactory().create(this);
        loginButton = page.account;
        settingsButton = page.settings;
        resultGroup = page.results;
        transcriptView = page.placeholder;
        meetingTitle = page.meetingTitle;
        transcriptList = page.transcriptList;
        leftLanguageTitle = page.leftTitle;
        rightLanguageTitle = page.rightTitle;
        elapsedView = page.elapsed;
        audioLevelBar = page.level;
        audioLevelView = page.levelText;
        statusView = page.status;
        capabilityView = page.capabilities;
        sourceLanguageView = page.sourceLanguage;
        targetLanguageView = page.targetLanguage;
        presentationCaptureView = page.presentation;
        summaryEnabledView = page.summary;
        transcriptModeView = page.transcriptMode;
        translationModeView = page.translationMode;
        startButton = page.start;
        stopButton = page.stop;
        overlayButton = page.overlay;
        swapColumnsButton = page.swapColumns;
        retryActionButton = page.retry;
        endResultButton = page.endResult;

        loginButton.setOnClickListener(view -> {
            if (displayedIdentity != null) logout();
            showLoginDialog();
        });
        settingsButton.setOnClickListener(view -> startActivityForResult(
                new Intent(this, ConnectionSettingsActivity.class), CONNECTION_SETTINGS_REQUEST));
        page.modes.setOnCheckedChangeListener((group, checkedId) -> {
            boolean translation = checkedId == translationModeView.getId();
            targetLanguageView.setEnabled(translation && deploymentLanguages.size() >= 2);
            if (translation) presentationCaptureView.setChecked(false);
            presentationCaptureView.setEnabled(!translation);
            page.showSetupMode(translation);
        });
        summaryEnabledView.setOnCheckedChangeListener((button, checked) ->
                page.showSetupMode(translationModeView.isChecked()));
        page.swapLanguages.setOnClickListener(view -> {
            int source = sourceLanguageView.getSelectedItemPosition();
            int target = targetLanguageView.getSelectedItemPosition();
            if (source >= 0 && target >= 0) {
                sourceLanguageView.setSelection(target);
                targetLanguageView.setSelection(source);
            }
        });
        refreshLanguageOptions(List.of());
        refreshModeAvailability();
        // 只呈现构建期实际注册的结果动作，伙伴自己的设置View仍有独立宿主槽。
        for (AppResultActionPlugin plugin : MeetingAppAssembly.resultActions()) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(plugin.displayName());
            resultActionIds.put(choice.getId(), plugin.id());
            resultGroup.addView(choice);
            AppResultActionPlugin.Settings settings = plugin.createSettings(this);
            resultSettings.put(plugin.id(), settings);
            if (settings.view() != null) {
                settings.view().setVisibility(View.GONE);
                page.pluginSettingsHost.addView(settings.view());
            }
        }
        resultGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = resultActionIds.get(checkedId);
            resultSettings.forEach((id, settings) -> {
                if (settings.view() != null) settings.view().setVisibility(
                        id.equals(selected) ? View.VISIBLE : View.GONE);
            });
        });
        refreshResultAvailability();
        startButton.setOnClickListener(view -> {
            if (MeetingRuntimeFactory.requiresMicrophone()
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
            } else createAndStartMeeting();
        });
        stopButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.requestStop();
        });
        swapColumnsButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.swapDisplayColumns();
        });
        overlayButton.setOnClickListener(view -> toggleOverlay());
        retryActionButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.retryResultAction();
        });
        endResultButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.endResultInteraction();
        });
        audioLevelView.setText(MeetingRuntimeFactory.requiresMicrophone()
                ? "输入电平：等待输入" : "输入电平：Mock 未实采");
        reminderPopup = new MeetingReminderPopup(this, false, () -> {
            if (meetingService != null) meetingService.confirmReminder();
        });
        page.root.addView(reminderPopup.view(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(page.root);
    }

    /** 独立参考账号入口，沿用原登录调用；凭据仅交给本进程身份Provider。 */
    private void showLoginDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding, padding, padding);
        usernameView = new EditText(this);
        usernameView.setSingleLine(true);
        usernameView.setHint("终端用户账号");
        passwordView = new EditText(this);
        passwordView.setSingleLine(true);
        passwordView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordView.setHint("密码");
        fields.addView(usernameView);
        fields.addView(passwordView);
        loginDialog = new android.app.AlertDialog.Builder(this).setTitle("参考账号登录")
                .setView(fields).setNegativeButton("返回", null).setPositiveButton("登录", null).create();
        loginDialog.setOnShowListener(dialog -> loginDialog.getButton(
                android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> login()));
        loginDialog.show();
    }

    /**
     * 默认参考账号密码页面；SDK登录成功后更新已装配的内存Provider。
     * 伙伴使用其他身份来源时替换此页面动作，返回后调用refreshIdentity即可；
     * CZUR系统账号、设备登录与参考默认会话互不依赖。
     */
    private void login() {
        String baseUrl = MeetingAppAssembly.serverUrl();
        String username = usernameView.getText().toString().trim();
        String password = passwordView.getText().toString();
        if (baseUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            statusView.setText("请先在连接设置配置后端，并填写账号和密码");
            return;
        }
        setConfigurationEnabled(false);
        statusView.setText("正在登录…");
        if (preMeetingClient != null) preMeetingClient.close();
        MeetingAppAssembly.configureBackend(baseUrl);
        preMeetingClient = MeetingRuntimeFactory.create(this,
                MeetingAppAssembly.clientConfig(command -> main.post(command)));
        preMeetingClient.login(username, password, new MeetingClient.Callback<ReferenceLoginSession>() {
            @Override public void onSuccess(ReferenceLoginSession loginSession) {
                passwordView.setText("");
                if (loginDialog != null) loginDialog.dismiss();
                statusView.setText("登录成功，正在获取可用功能…");
                Log.i(TAG, "参考账号登录成功");
                refreshIdentity(true);
            }
            @Override public void onError(String message) {
                setConfigurationEnabled(true);
                statusView.setText(message);
                Log.w(TAG, "参考账号登录失败");
            }
        });
    }

    /** 进入页面／从伙伴登录入口返回时读取当前来源，页面不直接读token或Provider私有字段。 */
    private void refreshIdentity(boolean reloadCapabilities) {
        if (MeetingAppAssembly.serverUrl().isEmpty()) return;
        if (preMeetingClient == null) preMeetingClient = MeetingRuntimeFactory.create(this,
                MeetingAppAssembly.clientConfig(command -> main.post(command)));
        preMeetingClient.refreshIdentity(new MeetingClient.Callback<IdentityResult>() {
            @Override public void onSuccess(IdentityResult identity) {
                renderIdentity(identity);
                if (identity.status() == IdentityResult.Status.LOGGED_IN && reloadCapabilities)
                    discoverCapabilities();
            }
            @Override public void onError(String message) { statusView.setText(message); }
        });
    }

    /** 只更新会前身份入口，进行中的会议仍由Service使用开始时的凭据快照。 */
    private void renderIdentity(IdentityResult identity) {
        displayedIdentity = identity.session();
        boolean loggedIn = identity.status() == IdentityResult.Status.LOGGED_IN;
        loginButton.setText(loggedIn ? "切换账号" : "账号登录");
        setConfigurationEnabled(!meetingBusy);
        if (!loggedIn) {
            deploymentCapabilities = Set.of();
            refreshLanguageOptions(List.of());
            discovery = null;
            if (!meetingBusy && !hasReadyResult) statusView.setText(identity.message());
        }
        refreshResultAvailability();
        refreshModeAvailability();
        startButton.setEnabled(loggedIn && !meetingBusy
                && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT)
                && !deploymentLanguages.isEmpty());
        Log.d(TAG, "页面身份刷新 state=" + identity.status());
    }

    private void discoverCapabilities() {
        preMeetingClient.discover(new MeetingClient.Callback<MeetingDiscovery>() {
            @Override public void onSuccess(MeetingDiscovery discovery) {
                // SDK已完成部署／本地能力求交；UI直接使用类型化结果，不创建Adapter。
                deploymentCapabilities = discovery.availableCapabilities();
                refreshLanguageOptions(discovery.deployment().languages());
                MainActivity.this.discovery = discovery;
                refreshResultAvailability();
                refreshModeAvailability();
                setConfigurationEnabled(!meetingBusy);
                startButton.setEnabled(displayedIdentity != null && !meetingBusy
                        && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT)
                        && !deploymentLanguages.isEmpty());
                if (!meetingBusy && !hasReadyResult) statusView.setText(deploymentLanguages.isEmpty()
                        ? "当前部署尚未声明会议语言" : "登录成功，可开始会议");
            }
            @Override public void onError(String message) {
                setConfigurationEnabled(!meetingBusy);
                startButton.setEnabled(false);
                if (!meetingBusy && !hasReadyResult) statusView.setText("登录成功；" + message);
            }
        });
    }

    /** 退出当前参考账号并允许重新配置后端；正在进行的会议不提供账号切换。 */
    private void logout() {
        if (preMeetingClient != null) { preMeetingClient.close(); preMeetingClient = null; }
        MeetingAppAssembly.clearReferenceSession();
        displayedIdentity = null;
        deploymentCapabilities = Set.of();
        refreshLanguageOptions(List.of());
        discovery = null;
        refreshResultAvailability();
        refreshModeAvailability();
        loginButton.setText("账号登录");
        startButton.setEnabled(false);
        statusView.setText("已退出，请重新登录");
        Log.i(TAG, "参考账号已退出");
    }

    /** 会前入口直接消费组合客户端已经求交的可用能力。 */
    private void refreshModeAvailability() {
        boolean summary = displayedIdentity != null
                && deploymentCapabilities.contains(MeetingCapabilityState.SUMMARY);
        boolean translation = displayedIdentity != null
                && deploymentCapabilities.contains(MeetingCapabilityState.TRANSLATION)
                && deploymentLanguages.size() >= 2;
        summaryEnabledView.setText(summary ? "会后生成会议纪要" : "会后生成会议纪要（当前部署/应用不可用）");
        summaryEnabledView.setEnabled(summary);
        if (!summary) summaryEnabledView.setChecked(false);
        translationModeView.setText(translation ? "现场互译" : "现场互译（当前部署/应用不可用）");
        translationModeView.setEnabled(translation);
        if (!translation && translationModeView.isChecked()) {
            transcriptModeView.setChecked(true);
        }
        targetLanguageView.setEnabled(translation && translationModeView.isChecked());
        page.showSetupMode(translationModeView.isChecked());
    }

    /** 保留仍可用的已选语言；初次发现使用后端前两项作为默认原文／目标语言。 */
    private void refreshLanguageOptions(List<String> languages) {
        int oldSource = sourceLanguageView.getSelectedItemPosition();
        int oldTarget = targetLanguageView.getSelectedItemPosition();
        String source = oldSource >= 0 && oldSource < deploymentLanguages.size()
                ? deploymentLanguages.get(oldSource) : null;
        String target = oldTarget >= 0 && oldTarget < deploymentLanguages.size()
                ? deploymentLanguages.get(oldTarget) : null;
        deploymentLanguages = List.copyOf(languages);
        List<String> labels = new ArrayList<>();
        for (String code : deploymentLanguages) {
            String label = Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
            labels.add(label.isEmpty() ? code : label + "（" + code + "）");
        }
        sourceLanguageView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        targetLanguageView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (!deploymentLanguages.isEmpty()) {
            sourceLanguageView.setSelection(Math.max(0, deploymentLanguages.indexOf(source)));
            int targetIndex = deploymentLanguages.indexOf(target);
            targetLanguageView.setSelection(targetIndex >= 0 ? targetIndex
                    : Math.min(1, deploymentLanguages.size() - 1));
        }
        sourceLanguageView.setEnabled(!deploymentLanguages.isEmpty());
        Log.d(TAG, "会议语言选项更新 count=" + deploymentLanguages.size());
    }

    private void refreshResultAvailability() {
        Set<String> available = discovery == null ? Set.of()
                : MeetingAppAssembly.resultRegistry().available(discovery).stream()
                        .map(plugin -> plugin.id()).collect(java.util.stream.Collectors.toSet());
        Integer first = null;
        for (var entry : resultActionIds.entrySet()) {
            View choice = resultGroup.findViewById(entry.getKey());
            boolean visible = available.contains(entry.getValue());
            choice.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible && first == null) first = entry.getKey();
        }
        String selected = resultActionIds.get(resultGroup.getCheckedRadioButtonId());
        if (selected == null || !available.contains(selected)) {
            if (first == null) {
                resultGroup.clearCheck();
                resultSettings.values().forEach(settings -> {
                    if (settings.view() != null) settings.view().setVisibility(View.GONE);
                });
            } else resultGroup.check(first);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MIC_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            createAndStartMeeting();
        } else {
            statusView.setText("需要麦克风权限才能进行真实转写");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CONNECTION_SETTINGS_REQUEST) {
            if (resultCode == RESULT_OK) {
                // onResume用新装配刷新；旧客户端不能继续访问原部署或保留原发现能力。
                if (preMeetingClient != null) preMeetingClient.close();
                preMeetingClient = null;
                displayedIdentity = null;
                deploymentCapabilities = Set.of();
                refreshLanguageOptions(List.of());
                discovery = null;
                refreshResultAvailability();
                refreshModeAvailability();
                startButton.setEnabled(false);
            }
            return;
        }
        if (requestCode != PRESENTATION_PERMISSION_REQUEST) return;
        MeetingAppAssembly.PreparedMeeting approved = pendingPresentationMeeting;
        pendingPresentationMeeting = null;
        if (approved == null) return;
        if (resultCode != RESULT_OK || data == null) {
            MeetingAppAssembly.clearPreparedMeeting();
            statusView.setText("未授予系统屏幕采集权限，会议未开始");
            startButton.setEnabled(true);
            setConfigurationEnabled(true);
            return;
        }
        startMeeting(approved, resultCode, data);
    }

    private void createAndStartMeeting() {
        if (preMeetingClient == null) {
            statusView.setText("请先登录");
            return;
        }
        startButton.setEnabled(false);
        setConfigurationEnabled(false);
        preMeetingClient.refreshIdentity(new MeetingClient.Callback<IdentityResult>() {
            @Override public void onSuccess(IdentityResult identity) {
                renderIdentity(identity);
                if (identity.status() == IdentityResult.Status.LOGGED_IN) continueStartMeeting();
            }
            @Override public void onError(String message) { statusView.setText(message); }
        });
    }

    /** 页面条件检查之后，SDK仍会在真正建会前重新读取Provider，避免使用过时身份。 */
    private void continueStartMeeting() {
        if (deploymentLanguages.isEmpty()) {
            statusView.setText("当前部署尚未声明会议语言");
            return;
        }
        if (resultGroup.getCheckedRadioButtonId() == -1 || discovery == null) {
            statusView.setText("当前部署没有可用的已装配结果动作");
            startButton.setEnabled(true);
            setConfigurationEnabled(true);
            return;
        }
        // 点击开始时读取一次会前设置。会议进行中即使输入框变化，也使用这份快照。
        MeetingSetup setup = selectedSetup();
        if (!deploymentCapabilities.containsAll(setup.toOptions().requiredCapabilities())) {
            statusView.setText("本场选择的字幕／互译或纪要功能当前不可用");
            return;
        }
        String actionId = setup.resultOptions().actionId();
        AppResultActionPlugin.Settings settings = resultSettings.get(actionId);
        // 页面输入只在本次Hook读取一次；Service与屏幕授权返回不重新prepare。
        var preparation = MeetingStartConditions.prepare(startCondition, setup, discovery,
                settings == null ? null : settings.freeze().get());
        if (!preparation.allowed()) {
            Log.i(TAG, "会前结果动作准备未满足，会议未启动");
            statusView.setText(preparation.message());
            startButton.setEnabled(true);
            setConfigurationEnabled(true);
            return;
        }
        MeetingAppAssembly.PreparedMeeting prepared = preparation.meeting();
        if (setup.presentationCapture()) {
            pendingPresentationMeeting = prepared;
            startButton.setEnabled(false);
            setConfigurationEnabled(false);
            statusView.setText("请在系统弹窗中授权共享整个屏幕");
            try {
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                Intent permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? manager.createScreenCaptureIntent(
                                MediaProjectionConfig.createConfigForDefaultDisplay())
                        : manager.createScreenCaptureIntent();
                startActivityForResult(permission, PRESENTATION_PERMISSION_REQUEST);
            } catch (RuntimeException failure) {
                pendingPresentationMeeting = null;
                MeetingAppAssembly.clearPreparedMeeting();
                startButton.setEnabled(true);
                setConfigurationEnabled(true);
                statusView.setText("无法打开系统屏幕采集授权弹窗");
                Log.e(TAG, "请求屏幕采集授权失败", failure);
            }
            return;
        }
        startMeeting(prepared, 0, null);
    }

    private void startMeeting(MeetingAppAssembly.PreparedMeeting prepared, int projectionResultCode,
            Intent projectionData) {
        startButton.setEnabled(false);
        setConfigurationEnabled(false);
        statusView.setText("正在创建会议…");
        MeetingAppAssembly.handoff(prepared);
        Intent begin = new Intent(this, MeetingService.class);
        begin.setAction(MeetingService.ACTION_BEGIN);
        if (projectionData != null) {
            begin.putExtra(MeetingService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode);
            begin.putExtra(MeetingService.EXTRA_PROJECTION_DATA, projectionData);
        }
        stopButton.setText("停止会议");
        try {
            // 用户仍在前台开始；Service只消费这一份会前设置与准备闭包，不传Activity或凭据。
            startForegroundService(begin);
        } catch (RuntimeException failure) {
            MeetingAppAssembly.clearPreparedMeeting();
            Log.e(TAG, "启动会议服务失败 type=" + failure.getClass().getSimpleName());
            statusView.setText("无法启动会议服务，请检查麦克风权限");
            startButton.setEnabled(true);
            setConfigurationEnabled(true);
        }
    }

    private MeetingSetup selectedSetup() {
        MeetingSetup.Mode mode = translationModeView.isChecked() ? MeetingSetup.Mode.LIVE_TRANSLATION
                : MeetingSetup.Mode.LIVE_TRANSCRIPT;
        String actionId = resultActionIds.get(resultGroup.getCheckedRadioButtonId());
        return new MeetingSetup(mode, deploymentLanguages.get(sourceLanguageView.getSelectedItemPosition()),
                deploymentLanguages.get(targetLanguageView.getSelectedItemPosition()),
                mode == MeetingSetup.Mode.LIVE_TRANSCRIPT && presentationCaptureView.isChecked(),
                ResultOptions.action(actionId), "", List.of(),
                summaryEnabledView.isChecked());
    }


    /** 只切换页面；模型会话与悬浮窗均由前台 Service 持有。 */
    private void showMeetingScreen(boolean visible) {
        page.showMeeting(visible);
    }

    /** Service 每秒或状态变化时推送；Activity 重建后可直接重绘整场字幕。 */
    private void renderMeetingState(MeetingService.State state) {
        meetingBusy = state.busy;
        hasReadyResult = state.readyResult != null;
        boolean failedAction = state.actionOutcome != null
                && state.actionOutcome.status() == ResultActionOutcome.Status.FAILED;
        retryActionButton.setVisibility(failedAction ? View.VISIBLE : View.GONE);
        endResultButton.setVisibility(failedAction ? View.VISIBLE : View.GONE);
        statusView.setText(state.status);
        capabilityView.setText(state.capabilityText);
        showMeetingScreen(state.active);
        startButton.setEnabled(displayedIdentity != null && !state.busy
                && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT)
                && !deploymentLanguages.isEmpty());
        setConfigurationEnabled(!state.busy);
        stopButton.setEnabled(state.canStop);
        overlayButton.setText(state.overlayShowing
                ? "关闭跨应用悬浮字幕" : "显示跨应用悬浮字幕");
        elapsedView.setText(state.timeText);
        int audioLevel = state.audioLevel == null ? 0 : state.audioLevel.level();
        audioLevelBar.setProgress(audioLevel);
        audioLevelView.setText(MeetingRuntimeFactory.requiresMicrophone()
                ? "输入电平：" + audioLevel + "/100" : "输入电平：Mock 未实采");
        reminderPopup.render(state.reminder);
        if (renderedTranscriptVersion != state.transcriptVersion) {
            if (state.display.rows().isEmpty()) {
                transcriptRows.clear();
                transcriptList.removeAllViews();
                transcriptList.addView(transcriptView);
            }
            for (MeetingDisplaySnapshot.Row source : state.display.rows()) {
                DisplaySnapshotPresentation.SubtitleRow segment = DisplaySnapshotPresentation.subtitle(source);
                if (segment == null) continue;
                TextView row = transcriptRows.get(segment.segmentId());
                if (row == null) {
                    row = page.textRow(28, false);
                    transcriptRows.put(segment.segmentId(), row);
                    transcriptList.addView(row);
                }
                SubtitleViewFactory.render(row, segment.segmentId(), segment.text(), segment.alpha());
            }
            if (state.display.bilingual()) {
                page.leftList.removeAllViews();
                page.rightList.removeAllViews();
                for (MeetingDisplaySnapshot.Row source : state.display.rows()) {
                    var segment = DisplaySnapshotPresentation.bilingual(source, state.display.swapped());
                    TextView left = page.textRow(26, false);
                    SubtitleViewFactory.render(left, segment.segmentId(), segment.left(), segment.leftAlpha());
                    page.leftList.addView(left);
                    TextView right = page.textRow(26, true);
                    SubtitleViewFactory.render(right, segment.segmentId(), segment.right(), segment.rightAlpha());
                    page.rightList.addView(right);
                }
            }
            renderedTranscriptVersion = state.transcriptVersion;
        }
        boolean bilingual = state.display.bilingual();
        var latest = !bilingual || state.display.rows().isEmpty() ? null
                : DisplaySnapshotPresentation.bilingual(
                        state.display.rows().get(state.display.rows().size() - 1), state.display.swapped());
        meetingTitle.setText(bilingual ? "双语互译" + (state.summaryEnabled ? "＋纪要" : "") + " · 会议中"
                + (latest != null && latest.languageFallback() ? "（语种分栏降级）" : "")
                : state.summaryEnabled
                        ? "AI 会议纪要 · 会议中" : "实时字幕 · 会议中");
        leftLanguageTitle.setText(DisplaySnapshotPresentation.leftLabel(state.display));
        rightLanguageTitle.setText(DisplaySnapshotPresentation.rightLabel(state.display));
        page.showBilingual(bilingual);
        transcriptView.setVisibility(!bilingual && transcriptRows.isEmpty()
                ? View.VISIBLE : View.GONE);
        // 通用UI操作由插件提供；页面不识别网页／PDF／邮件，重绑定消费同一待显示操作。
        if (resumed && meetingService != null) {
            Runnable operation;
            while ((operation = meetingService.takeUiOperation()) != null) operation.run();
        }
    }

    private void toggleOverlay() {
        if (meetingService == null) {
            statusView.setText("会议服务正在连接，请稍后重试");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            statusView.setText("请授予悬浮窗权限，返回会议页后再次点击显示");
            Intent request = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(request);
            return;
        }
        meetingService.toggleOverlay();
    }

    /** 会中及屏采授权等待期间不开放部署／账号修改，已准备的本场配置保持原快照。 */
    private void setConfigurationEnabled(boolean enabled) {
        boolean available = enabled && !meetingBusy && pendingPresentationMeeting == null;
        loginButton.setEnabled(available);
        settingsButton.setEnabled(available);
        if (loginDialog != null && loginDialog.isShowing())
            loginDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(available);
    }

    @Override protected void onStart() {
        super.onStart();
        serviceBound = bindService(new Intent(this, MeetingService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (meetingService != null) meetingService.observe(observer);
        refreshIdentity(deploymentCapabilities.isEmpty());
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onStop() {
        if (serviceBound) {
            if (meetingService != null) meetingService.unobserve(observer);
            unbindService(serviceConnection);
            serviceBound = false;
            meetingService = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (preMeetingClient != null) preMeetingClient.close();
        super.onDestroy();
    }
}
