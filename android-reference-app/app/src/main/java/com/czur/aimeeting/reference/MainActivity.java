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
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
    private ScrollView setupScreen;
    private ScrollView meetingScreen;
    private LinearLayout meetingControls;
    private int renderedTranscriptVersion = -1;
    private EditText backendUrlView;
    private EditText usernameView;
    private EditText passwordView;
    private RadioGroup resultGroup;
    private final Map<Integer, String> resultActionIds = new LinkedHashMap<>();
    private final Map<String, AppResultActionPlugin.Settings> resultSettings = new LinkedHashMap<>();
    private TextView transcriptView;
    private TextView meetingTitle;
    private LinearLayout transcriptList;
    private LinearLayout bilingualHeader;
    private LinearLayout bilingualList;
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 40, 40, 40);
        setupScreen = new ScrollView(this);
        setupScreen.setFillViewport(true);
        setupScreen.addView(layout);
        root.addView(setupScreen, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout meetingLayout = new LinearLayout(this);
        meetingLayout.setOrientation(LinearLayout.VERTICAL);
        meetingLayout.setPadding(40, 36, 40, 36);
        meetingScreen = new ScrollView(this);
        meetingScreen.addView(meetingLayout);
        meetingScreen.setVisibility(View.GONE);
        root.addView(meetingScreen, new LinearLayout.LayoutParams(-1, 0, 1));

        // 会中操作留在滚动区之外，持续新增字幕时仍可直接停止。
        meetingControls = new LinearLayout(this);
        meetingControls.setOrientation(LinearLayout.VERTICAL);
        meetingControls.setPadding(40, 0, 40, 12);
        meetingControls.setVisibility(View.GONE);
        root.addView(meetingControls, new LinearLayout.LayoutParams(-1, -2));

        backendUrlView = new EditText(this);
        backendUrlView.setSingleLine(true);
        backendUrlView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        backendUrlView.setHint("后端地址，例如 https://meeting.example.com");
        backendUrlView.setText(MeetingAppAssembly.serverUrl());
        layout.addView(backendUrlView);

        usernameView = new EditText(this);
        usernameView.setSingleLine(true);
        usernameView.setHint("终端用户账号");
        layout.addView(usernameView);

        passwordView = new EditText(this);
        passwordView.setSingleLine(true);
        passwordView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordView.setHint("密码");
        layout.addView(passwordView);

        loginButton = new Button(this);
        loginButton.setText("登录");
        loginButton.setOnClickListener(view -> {
            if (displayedIdentity == null) login();
            else logout();
        });
        layout.addView(loginButton);

        TextView modeTitle = new TextView(this);
        modeTitle.setText("会中显示");
        layout.addView(modeTitle);
        RadioGroup modeGroup = new RadioGroup(this);
        transcriptModeView = new RadioButton(this);
        transcriptModeView.setId(View.generateViewId());
        transcriptModeView.setText("实时字幕");
        transcriptModeView.setChecked(true);
        modeGroup.addView(transcriptModeView);
        translationModeView = new RadioButton(this);
        translationModeView.setId(View.generateViewId());
        modeGroup.addView(translationModeView);
        modeGroup.check(transcriptModeView.getId());
        layout.addView(modeGroup);

        summaryEnabledView = new CheckBox(this);
        summaryEnabledView.setText("会后生成会议纪要");
        layout.addView(summaryEnabledView);

        TextView sourceTitle = new TextView(this);
        sourceTitle.setText("源语言");
        layout.addView(sourceTitle);
        sourceLanguageView = new Spinner(this);
        layout.addView(sourceLanguageView);

        TextView targetTitle = new TextView(this);
        targetTitle.setText("互译目标语言");
        layout.addView(targetTitle);
        targetLanguageView = new Spinner(this);
        targetLanguageView.setEnabled(false);
        layout.addView(targetLanguageView);
        presentationCaptureView = new CheckBox(this);
        presentationCaptureView.setText("演示文稿（每10秒采集屏幕，开始时需系统授权）");
        layout.addView(presentationCaptureView);
        refreshLanguageOptions(List.of());
        refreshModeAvailability();
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean translation = checkedId == translationModeView.getId();
            targetLanguageView.setEnabled(translation);
            boolean transcript = checkedId == transcriptModeView.getId();
            if (!transcript) presentationCaptureView.setChecked(false);
            presentationCaptureView.setEnabled(transcript);
        });

        TextView resultTitle = new TextView(this);
        resultTitle.setText("结果动作");
        layout.addView(resultTitle);
        resultGroup = new RadioGroup(this);
        layout.addView(resultGroup);
        // 页面从构建清单呈现；服务声明不会生成未装配的邮件／PDF／无痕入口。
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
                layout.addView(settings.view());
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

        meetingTitle = new TextView(this);
        meetingTitle.setText("实时字幕 · 会议中");
        meetingTitle.setTextSize(26);
        meetingLayout.addView(meetingTitle);

        elapsedView = new TextView(this);
        elapsedView.setText("已进行 00:00:00 · 剩余 00:00:00");
        meetingLayout.addView(elapsedView);

        audioLevelView = new TextView(this);
        audioLevelView.setText(MeetingRuntimeFactory.requiresMicrophone()
                ? "输入电平：等待输入" : "输入电平：Mock 未实采");
        meetingLayout.addView(audioLevelView);
        audioLevelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        audioLevelBar.setMax(100);
        meetingLayout.addView(audioLevelBar, new LinearLayout.LayoutParams(-1, -2));

        transcriptView = SubtitleViewFactory.create(this, 28, 3, false);
        SubtitleViewFactory.render(transcriptView, "", "等待字幕…", 0.8f);
        transcriptView.setPadding(0, 36, 0, 36);
        meetingLayout.addView(transcriptView);
        transcriptList = new LinearLayout(this);
        transcriptList.setOrientation(LinearLayout.VERTICAL);
        meetingLayout.addView(transcriptList);

        bilingualHeader = new LinearLayout(this);
        bilingualHeader.setOrientation(LinearLayout.HORIZONTAL);
        leftLanguageTitle = new TextView(this);
        bilingualHeader.addView(leftLanguageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        rightLanguageTitle = new TextView(this);
        bilingualHeader.addView(rightLanguageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        bilingualHeader.setVisibility(View.GONE);
        meetingLayout.addView(bilingualHeader);
        bilingualList = new LinearLayout(this);
        bilingualList.setOrientation(LinearLayout.VERTICAL);
        bilingualList.setVisibility(View.GONE);
        meetingLayout.addView(bilingualList);

        swapColumnsButton = new Button(this);
        swapColumnsButton.setText("交换左右栏");
        swapColumnsButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.swapDisplayColumns();
        });
        swapColumnsButton.setVisibility(View.GONE);
        meetingControls.addView(swapColumnsButton);

        startButton = new Button(this);
        startButton.setText("开始会议");
        startButton.setEnabled(false);
        startButton.setOnClickListener(view -> {
            if (MeetingRuntimeFactory.requiresMicrophone()
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO},
                        MIC_PERMISSION_REQUEST);
            } else {
                createAndStartMeeting();
            }
        });
        layout.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("停止会议");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.requestStop();
        });
        meetingControls.addView(stopButton);

        overlayButton = new Button(this);
        overlayButton.setText("显示跨应用悬浮字幕");
        overlayButton.setOnClickListener(view -> toggleOverlay());
        meetingControls.addView(overlayButton);

        capabilityView = new TextView(this);
        capabilityView.setText("本场功能将在创建会议后按后端能力显示");
        meetingLayout.addView(capabilityView);

        statusView = new TextView(this);
        statusView.setText("填写 HTTPS 后端地址和部署者预置的账号");
        statusView.setPadding(40, 12, 40, 24);
        root.addView(statusView);
        retryActionButton = new Button(this);
        retryActionButton.setText("再次执行结果动作");
        retryActionButton.setVisibility(View.GONE);
        retryActionButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.retryResultAction();
        });
        root.addView(retryActionButton);
        endResultButton = new Button(this);
        endResultButton.setText("结束本场结果交互");
        endResultButton.setVisibility(View.GONE);
        endResultButton.setOnClickListener(view -> {
            if (meetingService != null) meetingService.endResultInteraction();
        });
        root.addView(endResultButton);
        FrameLayout screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        reminderPopup = new MeetingReminderPopup(this, false, () -> {
            if (meetingService != null) meetingService.confirmReminder();
        });
        screen.addView(reminderPopup.view(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
    }

    /**
     * 默认参考账号密码页面；SDK登录成功后更新已装配的内存Provider。
     * 伙伴使用其他身份来源时替换此页面动作，返回后调用refreshIdentity即可；
     * CZUR系统账号、设备登录与参考默认会话互不依赖。
     */
    private void login() {
        String baseUrl = backendUrlView.getText().toString().trim();
        String username = usernameView.getText().toString().trim();
        String password = passwordView.getText().toString();
        if (baseUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            statusView.setText("请填写后端地址、账号和密码");
            return;
        }
        loginButton.setEnabled(false);
        statusView.setText("正在登录…");
        if (preMeetingClient != null) preMeetingClient.close();
        MeetingAppAssembly.configureBackend(baseUrl);
        preMeetingClient = MeetingRuntimeFactory.create(this,
                MeetingAppAssembly.clientConfig(command -> main.post(command)));
        preMeetingClient.login(username, password, new MeetingClient.Callback<ReferenceLoginSession>() {
            @Override public void onSuccess(ReferenceLoginSession loginSession) {
                passwordView.setText("");
                statusView.setText("登录成功，正在获取可用功能…");
                Log.i(TAG, "参考账号登录成功");
                refreshIdentity(true);
            }
            @Override public void onError(String message) {
                loginButton.setEnabled(true);
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
        backendUrlView.setEnabled(!loggedIn);
        usernameView.setEnabled(!loggedIn);
        passwordView.setEnabled(!loggedIn);
        loginButton.setText(loggedIn ? "切换账号" : "登录");
        loginButton.setEnabled(!meetingBusy);
        if (loggedIn && !displayedIdentity.displayName().isEmpty())
            usernameView.setText(displayedIdentity.displayName());
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
                loginButton.setEnabled(!meetingBusy);
                startButton.setEnabled(displayedIdentity != null && !meetingBusy
                        && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT)
                        && !deploymentLanguages.isEmpty());
                if (!meetingBusy && !hasReadyResult) statusView.setText(deploymentLanguages.isEmpty()
                        ? "当前部署尚未声明会议语言" : "登录成功，可开始会议");
            }
            @Override public void onError(String message) {
                loginButton.setEnabled(!meetingBusy);
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
        backendUrlView.setEnabled(true);
        usernameView.setEnabled(true);
        passwordView.setEnabled(true);
        loginButton.setText("登录");
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
        if (requestCode != PRESENTATION_PERMISSION_REQUEST) return;
        MeetingAppAssembly.PreparedMeeting approved = pendingPresentationMeeting;
        pendingPresentationMeeting = null;
        if (approved == null) return;
        if (resultCode != RESULT_OK || data == null) {
            MeetingAppAssembly.clearPreparedMeeting();
            statusView.setText("未授予系统屏幕采集权限，会议未开始");
            startButton.setEnabled(true);
            loginButton.setEnabled(true);
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
        loginButton.setEnabled(false);
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
            loginButton.setEnabled(true);
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
            loginButton.setEnabled(true);
            return;
        }
        MeetingAppAssembly.PreparedMeeting prepared = preparation.meeting();
        if (setup.presentationCapture()) {
            pendingPresentationMeeting = prepared;
            startButton.setEnabled(false);
            loginButton.setEnabled(false);
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
                loginButton.setEnabled(true);
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
        loginButton.setEnabled(false);
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
            loginButton.setEnabled(true);
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
        setupScreen.setVisibility(visible ? View.GONE : View.VISIBLE);
        meetingScreen.setVisibility(visible ? View.VISIBLE : View.GONE);
        meetingControls.setVisibility(visible ? View.VISIBLE : View.GONE);
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
        loginButton.setEnabled(!state.busy);
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
            }
            for (MeetingDisplaySnapshot.Row source : state.display.rows()) {
                DisplaySnapshotPresentation.SubtitleRow segment = DisplaySnapshotPresentation.subtitle(source);
                if (segment == null) continue;
                TextView row = transcriptRows.get(segment.segmentId());
                if (row == null) {
                    row = SubtitleViewFactory.create(this, 28, Integer.MAX_VALUE, false);
                    row.setPadding(0, 8, 0, 8);
                    transcriptRows.put(segment.segmentId(), row);
                    transcriptList.addView(row);
                }
                SubtitleViewFactory.render(row, segment.segmentId(), segment.text(), segment.alpha());
            }
            if (state.display.bilingual()) {
                bilingualList.removeAllViews();
                for (MeetingDisplaySnapshot.Row source : state.display.rows()) {
                    var segment = DisplaySnapshotPresentation.bilingual(source, state.display.swapped());
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    TextView left = SubtitleViewFactory.create(this, 26, Integer.MAX_VALUE, false);
                    SubtitleViewFactory.render(left, segment.segmentId(), segment.left(), segment.leftAlpha());
                    left.setPadding(0, 8, 12, 8);
                    row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView right = SubtitleViewFactory.create(this, 26, Integer.MAX_VALUE, false);
                    SubtitleViewFactory.render(right, segment.segmentId(), segment.right(), segment.rightAlpha());
                    right.setPadding(12, 8, 0, 8);
                    row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
                    bilingualList.addView(row);
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
        bilingualHeader.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        bilingualList.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        swapColumnsButton.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        transcriptList.setVisibility(bilingual ? View.GONE : View.VISIBLE);
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
