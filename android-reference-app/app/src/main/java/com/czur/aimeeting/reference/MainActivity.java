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
import android.util.Patterns;
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
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingDiscovery;
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
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
    private EditText resultWebUrlView;
    private EditText resultEmailsView;
    private TextView transcriptView;
    private TextView meetingTitle;
    private LinearLayout transcriptList;
    private LinearLayout bilingualHeader;
    private LinearLayout bilingualList;
    private TextView leftLanguageTitle;
    private TextView rightLanguageTitle;
    private final Map<String, TextView> transcriptRows = new LinkedHashMap<>();
    private TextView elapsedView;
    private MeetingReminderPopup reminderPopup;
    private TextView statusView;
    private TextView capabilityView;
    private Spinner sourceLanguageView;
    private Spinner targetLanguageView;
    private CheckBox presentationCaptureView;
    private RadioButton transcriptModeView;
    private RadioButton minutesModeView;
    private RadioButton translationModeView;
    private RadioButton webResultView;
    private RadioButton localResultView;
    private RadioButton emailResultView;
    private RadioButton tracelessResultView;
    private Button startButton;
    private Button recentPdfButton;
    private Button stopButton;
    private Button overlayButton;
    private Button swapColumnsButton;
    private Button loginButton;
    /** 只用于页面展示／入口开关；Service开始时仍通过Provider重新获取真实凭据。 */
    private IdentitySession displayedIdentity;
    private boolean meetingBusy;
    private Set<String> deploymentCapabilities = Set.of();
    private long resultLinkTtlMs;
    private boolean resultEmailEnabled;
    private MeetingSetup pendingPresentationSetup;

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
        modeTitle.setText("会议模式");
        layout.addView(modeTitle);
        RadioGroup modeGroup = new RadioGroup(this);
        transcriptModeView = new RadioButton(this);
        transcriptModeView.setId(View.generateViewId());
        transcriptModeView.setText("实时字幕");
        transcriptModeView.setChecked(true);
        modeGroup.addView(transcriptModeView);
        minutesModeView = new RadioButton(this);
        minutesModeView.setId(View.generateViewId());
        modeGroup.addView(minutesModeView);
        translationModeView = new RadioButton(this);
        translationModeView.setId(View.generateViewId());
        modeGroup.addView(translationModeView);
        modeGroup.check(transcriptModeView.getId());
        refreshModeAvailability();
        layout.addView(modeGroup);

        TextView sourceTitle = new TextView(this);
        sourceTitle.setText("源语言");
        layout.addView(sourceTitle);
        String[] languages = {"中文（zh-CN）", "英语（en-US）"};
        sourceLanguageView = new Spinner(this);
        sourceLanguageView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, languages));
        layout.addView(sourceLanguageView);

        TextView targetTitle = new TextView(this);
        targetTitle.setText("互译目标语言");
        layout.addView(targetTitle);
        targetLanguageView = new Spinner(this);
        targetLanguageView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, languages));
        targetLanguageView.setSelection(1);
        targetLanguageView.setEnabled(false);
        layout.addView(targetLanguageView);
        presentationCaptureView = new CheckBox(this);
        presentationCaptureView.setText("演示文稿（每10秒采集屏幕，开始时需系统授权）");
        layout.addView(presentationCaptureView);
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean translation = checkedId == translationModeView.getId();
            targetLanguageView.setEnabled(translation);
            if (tracelessResultView != null) {
                tracelessResultView.setEnabled(translation);
                if (!translation && tracelessResultView.isChecked()) {
                    webResultView.setChecked(true);
                }
            }
            boolean transcript = checkedId == transcriptModeView.getId();
            if (!transcript) presentationCaptureView.setChecked(false);
            presentationCaptureView.setEnabled(transcript);
        });

        TextView resultTitle = new TextView(this);
        resultTitle.setText("结果获取方式");
        layout.addView(resultTitle);
        RadioGroup resultGroup = new RadioGroup(this);
        webResultView = new RadioButton(this);
        webResultView.setId(View.generateViewId());
        webResultView.setText("网页查看");
        webResultView.setChecked(true);
        resultGroup.addView(webResultView);
        localResultView = new RadioButton(this);
        localResultView.setId(View.generateViewId());
        localResultView.setText("本地保存 PDF（下载/AI会议纪要）");
        resultGroup.addView(localResultView);
        emailResultView = new RadioButton(this);
        emailResultView.setId(View.generateViewId());
        emailResultView.setText("邮箱（当前部署不可用）");
        emailResultView.setEnabled(false);
        resultGroup.addView(emailResultView);
        tracelessResultView = new RadioButton(this);
        tracelessResultView.setId(View.generateViewId());
        tracelessResultView.setText("无痕（仅现场互译）");
        tracelessResultView.setEnabled(false);
        resultGroup.addView(tracelessResultView);
        resultGroup.check(webResultView.getId());
        layout.addView(resultGroup);

        resultWebUrlView = new EditText(this);
        resultWebUrlView.setSingleLine(true);
        resultWebUrlView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        resultWebUrlView.setHint("网页结果地址，例如 http://192.168.1.10:8081/index.html");
        layout.addView(resultWebUrlView);
        resultEmailsView = new EditText(this);
        resultEmailsView.setHint("收件邮箱，多个用逗号分隔（最多 10 个）");
        resultEmailsView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        resultEmailsView.setVisibility(View.GONE);
        layout.addView(resultEmailsView);
        resultGroup.setOnCheckedChangeListener((group, checkedId) -> {
            resultWebUrlView.setEnabled(checkedId != localResultView.getId()
                    && checkedId != tracelessResultView.getId());
            resultEmailsView.setVisibility(checkedId == emailResultView.getId()
                    ? View.VISIBLE : View.GONE);
        });
        recentPdfButton = new Button(this);
        recentPdfButton.setText("打开最近本地 PDF");
        recentPdfButton.setOnClickListener(view -> {
            Intent recent = LocalResultActivity.recentIntent(this);
            if (recent != null) startActivity(recent);
        });
        layout.addView(recentPdfButton);

        meetingTitle = new TextView(this);
        meetingTitle.setText("实时字幕 · 会议中");
        meetingTitle.setTextSize(26);
        meetingLayout.addView(meetingTitle);

        elapsedView = new TextView(this);
        elapsedView.setText("已进行 00:00:00 · 剩余 00:00:00");
        meetingLayout.addView(elapsedView);

        transcriptView = new TextView(this);
        transcriptView.setTextSize(28);
        transcriptView.setText("等待字幕…");
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
        stopButton.setText("停止会议并查看网页结果");
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
            resultLinkTtlMs = 0;
            resultEmailEnabled = false;
            if (!meetingBusy) statusView.setText(identity.message());
        }
        refreshResultAvailability();
        refreshModeAvailability();
        startButton.setEnabled(loggedIn && !meetingBusy
                && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT));
        Log.d(TAG, "页面身份刷新 state=" + identity.status());
    }

    private void discoverCapabilities() {
        preMeetingClient.discover(new MeetingClient.Callback<MeetingDiscovery>() {
            @Override public void onSuccess(MeetingDiscovery discovery) {
                // SDK已完成部署／本地能力求交；UI直接使用类型化结果，不创建Adapter。
                deploymentCapabilities = discovery.availableCapabilities();
                resultLinkTtlMs = discovery.deployment().resultLinkTtlMs();
                resultEmailEnabled = discovery.deployment().resultEmailEnabled();
                refreshResultAvailability();
                refreshModeAvailability();
                loginButton.setEnabled(!meetingBusy);
                startButton.setEnabled(displayedIdentity != null && !meetingBusy
                        && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT));
                if (!meetingBusy) statusView.setText("登录成功，可开始会议");
            }
            @Override public void onError(String message) {
                loginButton.setEnabled(!meetingBusy);
                startButton.setEnabled(false);
                if (!meetingBusy) statusView.setText("登录成功；" + message);
            }
        });
    }

    /** 退出当前参考账号并允许重新配置后端；正在进行的会议不提供账号切换。 */
    private void logout() {
        if (preMeetingClient != null) { preMeetingClient.close(); preMeetingClient = null; }
        MeetingAppAssembly.clearReferenceSession();
        displayedIdentity = null;
        deploymentCapabilities = Set.of();
        resultLinkTtlMs = 0;
        resultEmailEnabled = false;
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
        boolean minutes = displayedIdentity != null
                && deploymentCapabilities.contains(MeetingCapabilityState.SUMMARY);
        boolean translation = displayedIdentity != null
                && deploymentCapabilities.contains(MeetingCapabilityState.TRANSLATION);
        minutesModeView.setText(minutes ? "AI 会议纪要" : "AI 会议纪要（当前部署/应用不可用）");
        minutesModeView.setEnabled(minutes);
        translationModeView.setText(translation ? "现场互译" : "现场互译（当前部署/应用不可用）");
        translationModeView.setEnabled(translation);
        if ((!minutes && minutesModeView.isChecked())
                || (!translation && translationModeView.isChecked())) {
            transcriptModeView.setChecked(true);
        }
    }

    private void refreshResultAvailability() {
        boolean available = displayedIdentity != null && resultLinkTtlMs > 0
                && resultEmailEnabled;
        emailResultView.setEnabled(available);
        emailResultView.setText(available ? "邮箱" : "邮箱（当前部署不可用）");
        if (!available && emailResultView.isChecked()) webResultView.setChecked(true);
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
        MeetingSetup approved = pendingPresentationSetup;
        pendingPresentationSetup = null;
        if (approved == null) return;
        if (resultCode != RESULT_OK || data == null) {
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
        // 点击开始时读取一次会前设置。会议进行中即使输入框变化，也使用这份快照。
        MeetingSetup setup = selectedSetup();
        if (!deploymentCapabilities.contains(setup.mode().requiredCapability())) {
            statusView.setText(setup.mode().displayName() + "当前不可用");
            return;
        }
        if (setup.resultDelivery() != MeetingSetup.ResultDelivery.LOCAL
                && setup.resultDelivery() != MeetingSetup.ResultDelivery.TRACELESS
                && setup.resultWebUrl().isEmpty()) {
            statusView.setText("请填写网页结果地址");
            return;
        }
        if (setup.resultDelivery() == MeetingSetup.ResultDelivery.EMAIL
                && (setup.resultEmails().isEmpty() || setup.resultEmails().size() > 10
                || setup.resultEmails().stream().distinct().count()
                        != setup.resultEmails().size()
                || setup.resultEmails().stream().anyMatch(email ->
                        !Patterns.EMAIL_ADDRESS.matcher(email).matches()))) {
            statusView.setText("请填写 1～10 个不重复的有效收件邮箱后开始会议");
            return;
        }
        // 合作伙伴的附加开会条件只在此处接入。默认实现直接放行；二次开发可检查
        // 已缓存的接收者人数或其他外部状态，而无需改动后面的会议创建/模型流程。
        MeetingStartCondition.Decision decision = startCondition.check(setup);
        if (!decision.allowed()) {
            Log.i(TAG, "合作伙伴开会条件未满足，会议未启动 mode=" + setup.mode());
            statusView.setText(decision.message());
            return;
        }
        if (setup.presentationCapture()) {
            pendingPresentationSetup = setup;
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
                pendingPresentationSetup = null;
                startButton.setEnabled(true);
                loginButton.setEnabled(true);
                statusView.setText("无法打开系统屏幕采集授权弹窗");
                Log.e(TAG, "请求屏幕采集授权失败", failure);
            }
            return;
        }
        startMeeting(setup, 0, null);
    }

    private void startMeeting(MeetingSetup setup, int projectionResultCode,
            Intent projectionData) {
        startButton.setEnabled(false);
        loginButton.setEnabled(false);
        statusView.setText("正在创建会议…");
        Intent begin = new Intent(this, MeetingService.class);
        begin.setAction(MeetingService.ACTION_BEGIN);
        begin.putExtra(MeetingService.EXTRA_MODE, setup.mode().name());
        begin.putExtra(MeetingService.EXTRA_SOURCE, setup.sourceLanguage());
        begin.putExtra(MeetingService.EXTRA_TARGET, setup.targetLanguage());
        begin.putExtra(MeetingService.EXTRA_PRESENTATION, setup.presentationCapture());
        if (projectionData != null) {
            begin.putExtra(MeetingService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode);
            begin.putExtra(MeetingService.EXTRA_PROJECTION_DATA, projectionData);
        }
        begin.putExtra(MeetingService.EXTRA_RESULT_WEB, setup.resultWebUrl());
        begin.putExtra(MeetingService.EXTRA_RESULT_DELIVERY, setup.resultDelivery().name());
        begin.putExtra(MeetingService.EXTRA_RESULT_EMAILS,
                setup.resultEmails().toArray(new String[0]));
        stopButton.setText(switch (setup.resultDelivery()) {
            case LOCAL -> "停止会议并保存本地 PDF";
            case EMAIL -> "停止会议并发送邮箱结果";
            case TRACELESS -> "结束无痕互译";
            default -> "停止会议并查看网页结果";
        });
        try {
            // 用户仍在前台点击开始时启动 microphone 前台 Service，再由 Service 建会/开 MIC。
            startForegroundService(begin);
        } catch (RuntimeException failure) {
            Log.e(TAG, "启动会议前台服务失败", failure);
            statusView.setText("无法启动会议服务，请检查麦克风权限");
            startButton.setEnabled(true);
            loginButton.setEnabled(true);
        }
    }

    private MeetingSetup selectedSetup() {
        MeetingSetup.Mode mode = minutesModeView.isChecked()
                ? MeetingSetup.Mode.MEETING_MINUTES
                : translationModeView.isChecked() ? MeetingSetup.Mode.LIVE_TRANSLATION
                : MeetingSetup.Mode.LIVE_TRANSCRIPT;
        String[] codes = {"zh-CN", "en-US"};
        List<String> emails = new ArrayList<>();
        for (String entry : resultEmailsView.getText().toString().split("[,，;；\\s]+")) {
            if (!entry.isBlank()) emails.add(entry.trim());
        }
        return new MeetingSetup(mode, codes[sourceLanguageView.getSelectedItemPosition()],
                codes[targetLanguageView.getSelectedItemPosition()],
                mode == MeetingSetup.Mode.LIVE_TRANSCRIPT
                        && presentationCaptureView.isChecked(), localResultView.isChecked()
                                ? MeetingSetup.ResultDelivery.LOCAL
                                : emailResultView.isChecked() ? MeetingSetup.ResultDelivery.EMAIL
                                : tracelessResultView.isChecked()
                                        ? MeetingSetup.ResultDelivery.TRACELESS
                                : MeetingSetup.ResultDelivery.WEB,
                resultWebUrlView.getText().toString().trim(), emails);
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
        statusView.setText(state.status);
        capabilityView.setText(state.capabilityText);
        showMeetingScreen(state.active);
        startButton.setEnabled(displayedIdentity != null && !state.busy
                && deploymentCapabilities.contains(MeetingCapabilityState.TRANSCRIPT));
        loginButton.setEnabled(!state.busy);
        stopButton.setEnabled(state.canStop);
        overlayButton.setText(state.overlayShowing
                ? "关闭跨应用悬浮字幕" : "显示跨应用悬浮字幕");
        elapsedView.setText(state.timeText);
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
                    row = new TextView(this);
                    row.setTextSize(28);
                    row.setPadding(0, 8, 0, 8);
                    transcriptRows.put(segment.segmentId(), row);
                    transcriptList.addView(row);
                }
                row.setText(segment.text());
                row.setAlpha(segment.alpha());
            }
            if (state.display.bilingual()) {
                bilingualList.removeAllViews();
                for (MeetingDisplaySnapshot.Row source : state.display.rows()) {
                    var segment = DisplaySnapshotPresentation.bilingual(source, state.display.swapped());
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    TextView left = new TextView(this);
                    left.setTextSize(26);
                    left.setText(segment.left());
                    left.setAlpha(segment.leftAlpha());
                    left.setPadding(0, 8, 12, 8);
                    row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView right = new TextView(this);
                    right.setTextSize(26);
                    right.setText(segment.right());
                    right.setAlpha(segment.rightAlpha());
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
        meetingTitle.setText(bilingual ? "双语互译 · 会议中"
                + (latest != null && latest.languageFallback() ? "（语种分栏降级）" : "")
                : state.mode == MeetingSetup.Mode.MEETING_MINUTES
                        ? "AI 会议纪要 · 会议中" : "实时字幕 · 会议中");
        leftLanguageTitle.setText(DisplaySnapshotPresentation.leftLabel(state.display));
        rightLanguageTitle.setText(DisplaySnapshotPresentation.rightLabel(state.display));
        bilingualHeader.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        bilingualList.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        swapColumnsButton.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        transcriptList.setVisibility(bilingual ? View.GONE : View.VISIBLE);
        transcriptView.setVisibility(!bilingual && transcriptRows.isEmpty()
                ? View.VISIBLE : View.GONE);
        if (state.resultUrl != null && meetingService != null) {
            String resultUrl = meetingService.takeResultUrl();
            Intent result = new Intent(this, ResultActivity.class);
            result.putExtra(ResultActivity.EXTRA_RESULT_URL, resultUrl);
            startActivity(result);
        }
        if (state.resultPdf != null && meetingService != null) {
            LocalPdfWriter.SavedPdf pdf = meetingService.takeResultPdf();
            Intent result = new Intent(this, LocalResultActivity.class);
            if (pdf.uri() != null) result.putExtra(LocalResultActivity.EXTRA_URI,
                    pdf.uri().toString());
            result.putExtra(LocalResultActivity.EXTRA_CACHE_PATH, pdf.cachePath());
            result.putExtra(LocalResultActivity.EXTRA_FILE_NAME, pdf.displayName());
            startActivity(result);
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
        refreshIdentity(deploymentCapabilities.isEmpty());
        if (recentPdfButton != null) recentPdfButton.setVisibility(
                LocalResultActivity.recentIntent(this) == null ? View.GONE : View.VISIBLE);
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
