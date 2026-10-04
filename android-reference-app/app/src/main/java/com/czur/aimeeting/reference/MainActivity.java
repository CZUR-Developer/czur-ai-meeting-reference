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
import android.os.IBinder;
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
import com.czur.aimeeting.reference.meeting.HttpReferenceSessionClient;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeBridge;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingCapabilityState;
import com.czur.aimeeting.reference.meeting.MeetingDisplayState;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.meeting.ReferenceLoginSession;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 参考会议入口：会前设置与会中字幕共用一场模型会话，标准事件按序提交后端。 */
public final class MainActivity extends Activity {
    private static final String TAG = "AiMeetingReference";
    private static final int MIC_PERMISSION_REQUEST = 1001;
    private static final int PRESENTATION_PERMISSION_REQUEST = 1002;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
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
    private MeetingRuntimeBridge runtime;
    private MeetingService meetingService;
    private boolean serviceBound;
    private ScrollView setupScreen;
    private ScrollView meetingScreen;
    private int renderedTranscriptVersion = -1;
    private EditText backendUrlView;
    private EditText usernameView;
    private EditText passwordView;
    private EditText resultWebUrlView;
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
    private Button startButton;
    private Button stopButton;
    private Button overlayButton;
    private Button swapColumnsButton;
    private Button loginButton;
    private String sessionToken;
    private String sessionBackendUrl;
    private Set<String> deploymentCapabilities = Set.of();
    private MeetingSetup pendingPresentationSetup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = MeetingRuntimeFactory.create(this);

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

        backendUrlView = new EditText(this);
        backendUrlView.setSingleLine(true);
        backendUrlView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        backendUrlView.setHint("后端地址，例如 https://meeting.example.com");
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
            if (sessionToken == null) login();
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
            targetLanguageView.setEnabled(checkedId == translationModeView.getId());
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
        for (String unavailable : new String[] {"本地保存", "二维码", "邮箱", "无痕"}) {
            RadioButton option = new RadioButton(this);
            option.setText(unavailable + "（交付流程待接入，暂不可用）");
            option.setEnabled(false);
            resultGroup.addView(option);
        }
        resultGroup.check(webResultView.getId());
        layout.addView(resultGroup);

        resultWebUrlView = new EditText(this);
        resultWebUrlView.setSingleLine(true);
        resultWebUrlView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        resultWebUrlView.setHint("网页结果地址，例如 http://192.168.1.10:8081/index.html");
        layout.addView(resultWebUrlView);

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
        meetingLayout.addView(swapColumnsButton);

        startButton = new Button(this);
        startButton.setText("开始会议");
        startButton.setEnabled(false);
        startButton.setOnClickListener(view -> {
            if (runtime.waitsForModelFinish()
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
        meetingLayout.addView(stopButton);

        overlayButton = new Button(this);
        overlayButton.setText("显示跨应用悬浮字幕");
        overlayButton.setOnClickListener(view -> toggleOverlay());
        meetingLayout.addView(overlayButton);

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
     * 登录仅用于参考会议后端。凭据直接从输入框取出发送，后端令牌留在内存中；
     * CZUR 系统账号、设备登录与这个会话互不依赖。
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
        network.execute(() -> {
            try {
                HttpReferenceSessionClient sessionClient = new HttpReferenceSessionClient(baseUrl);
                ReferenceLoginSession loginSession = sessionClient.login(username, password);
                Set<String> discovered;
                boolean discoveryUnavailable = false;
                try {
                    discovered = sessionClient.capabilities();
                } catch (IOException failure) {
                    // 旧后端仍可做字幕；增值模式必须有部署能力的明确声明。
                    discovered = Set.of(MeetingCapabilityState.TRANSCRIPT);
                    discoveryUnavailable = true;
                }
                Set<String> available = discovered;
                boolean limited = discoveryUnavailable;
                Log.i(TAG, "参考账号登录成功");
                runOnUiThread(() -> {
                    sessionToken = loginSession.accessToken();
                    sessionBackendUrl = baseUrl;
                    deploymentCapabilities = available;
                    refreshModeAvailability();
                    passwordView.setText("");
                    backendUrlView.setEnabled(false);
                    usernameView.setEnabled(false);
                    passwordView.setEnabled(false);
                    loginButton.setText("切换账号");
                    loginButton.setEnabled(true);
                    startButton.setEnabled(true);
                    statusView.setText(limited ? "登录成功；能力发现不可用，仅开放实时字幕"
                            : "登录成功，可开始会议");
                });
            } catch (IOException failure) {
                Log.w(TAG, "参考账号登录失败：" + failure.getClass().getSimpleName());
                runOnUiThread(() -> {
                    loginButton.setEnabled(true);
                    statusView.setText(failure.getMessage());
                });
            }
        });
    }

    /** 退出当前参考账号并允许重新配置后端；正在进行的会议不提供账号切换。 */
    private void logout() {
        sessionToken = null;
        sessionBackendUrl = null;
        deploymentCapabilities = Set.of();
        refreshModeAvailability();
        backendUrlView.setEnabled(true);
        usernameView.setEnabled(true);
        passwordView.setEnabled(true);
        loginButton.setText("登录");
        startButton.setEnabled(false);
        statusView.setText("已退出，请重新登录");
        Log.i(TAG, "参考账号已退出");
    }

    /** 会前入口只展示本地 Adapter 与当前后端部署共同支持的模式。 */
    private void refreshModeAvailability() {
        Set<String> local = runtime.setupCapabilities();
        boolean minutes = sessionToken != null
                && local.contains(MeetingCapabilityState.SUMMARY)
                && deploymentCapabilities.contains(MeetingCapabilityState.SUMMARY);
        boolean translation = sessionToken != null
                && local.contains(MeetingCapabilityState.TRANSLATION)
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
        if (sessionToken == null) {
            statusView.setText("请先登录");
            return;
        }
        // 点击开始时读取一次会前设置。会议进行中即使输入框变化，也使用这份快照。
        MeetingSetup setup = selectedSetup();
        if (!runtime.setupCapabilities().contains(setup.mode().requiredCapability())
                || !deploymentCapabilities.contains(setup.mode().requiredCapability())) {
            statusView.setText(setup.mode().displayName() + "当前不可用");
            return;
        }
        if (setup.resultDelivery() != MeetingSetup.ResultDelivery.WEB
                || !webResultView.isChecked() || setup.resultWebUrl().isEmpty()) {
            statusView.setText("当前仅支持网页查看，请填写网页结果地址");
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
        begin.putExtra(MeetingService.EXTRA_BACKEND, sessionBackendUrl);
        begin.putExtra(MeetingService.EXTRA_TOKEN, sessionToken);
        begin.putExtra(MeetingService.EXTRA_MODE, setup.mode().name());
        begin.putExtra(MeetingService.EXTRA_SOURCE, setup.sourceLanguage());
        begin.putExtra(MeetingService.EXTRA_TARGET, setup.targetLanguage());
        begin.putExtra(MeetingService.EXTRA_PRESENTATION, setup.presentationCapture());
        if (projectionData != null) {
            begin.putExtra(MeetingService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode);
            begin.putExtra(MeetingService.EXTRA_PROJECTION_DATA, projectionData);
        }
        begin.putExtra(MeetingService.EXTRA_RESULT_WEB, setup.resultWebUrl());
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
        return new MeetingSetup(mode, codes[sourceLanguageView.getSelectedItemPosition()],
                codes[targetLanguageView.getSelectedItemPosition()],
                mode == MeetingSetup.Mode.LIVE_TRANSCRIPT
                        && presentationCaptureView.isChecked(), MeetingSetup.ResultDelivery.WEB,
                resultWebUrlView.getText().toString().trim());
    }


    /** 只切换页面；模型会话与悬浮窗均由前台 Service 持有。 */
    private void showMeetingScreen(boolean visible) {
        setupScreen.setVisibility(visible ? View.GONE : View.VISIBLE);
        meetingScreen.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Service 每秒或状态变化时推送；Activity 重建后可直接重绘整场字幕。 */
    private void renderMeetingState(MeetingService.State state) {
        statusView.setText(state.status);
        capabilityView.setText(state.capabilityText);
        showMeetingScreen(state.active);
        startButton.setEnabled(sessionToken != null && !state.busy);
        loginButton.setEnabled(!state.busy);
        stopButton.setEnabled(state.canStop);
        overlayButton.setText(state.overlayShowing
                ? "关闭跨应用悬浮字幕" : "显示跨应用悬浮字幕");
        elapsedView.setText(state.timeText);
        reminderPopup.render(state.reminder);
        if (renderedTranscriptVersion != state.transcriptVersion) {
            if (state.display.segments().isEmpty()) {
                transcriptRows.clear();
                transcriptList.removeAllViews();
            }
            for (MeetingDisplayState.Segment segment : state.display.segments()) {
                TextView row = transcriptRows.get(segment.id());
                if (row == null) {
                    row = new TextView(this);
                    row.setTextSize(28);
                    row.setPadding(0, 8, 0, 8);
                    transcriptRows.put(segment.id(), row);
                    transcriptList.addView(row);
                }
                row.setText(segment.text());
                row.setAlpha(segment.alpha());
            }
            if (state.display.isBilingual()) {
                bilingualList.removeAllViews();
                for (MeetingDisplayState.BilingualRow segment : state.display.bilingualRows()) {
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
        boolean bilingual = state.display.isBilingual();
        MeetingDisplayState.BilingualRow latest = bilingual
                ? state.display.latestBilingualRow() : null;
        meetingTitle.setText(bilingual ? "双语互译 · 会议中"
                + (latest != null && latest.languageFallback() ? "（语种分栏降级）" : "")
                : "实时字幕 · 会议中");
        leftLanguageTitle.setText(state.display.leftLabel());
        rightLanguageTitle.setText(state.display.rightLabel());
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
        network.shutdownNow();
        super.onDestroy();
    }
}
