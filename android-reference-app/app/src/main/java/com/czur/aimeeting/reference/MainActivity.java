package com.czur.aimeeting.reference;

import android.app.Activity;
import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 参考会议入口：会前设置与会中字幕共用一场模型会话，标准事件按序提交后端。 */
public final class MainActivity extends Activity {
    private static final String TAG = "AiMeetingReference";
    private static final int MIC_PERMISSION_REQUEST = 1001;
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
    private LinearLayout transcriptList;
    private final Map<String, TextView> transcriptRows = new LinkedHashMap<>();
    private TextView elapsedView;
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
    private Button loginButton;
    private String sessionToken;
    private String sessionBackendUrl;

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
        boolean minutesAvailable = runtime.setupCapabilities().contains(
                MeetingCapabilityState.SUMMARY);
        minutesModeView.setText(minutesAvailable ? "AI 会议纪要"
                : "AI 会议纪要（当前模型/应用未接入，暂不可用）");
        minutesModeView.setEnabled(minutesAvailable);
        modeGroup.addView(minutesModeView);
        translationModeView = new RadioButton(this);
        translationModeView.setId(View.generateViewId());
        boolean translationAvailable = runtime.setupCapabilities().contains(
                MeetingCapabilityState.TRANSLATION);
        translationModeView.setText(translationAvailable ? "现场互译"
                : "现场互译（当前模型/应用未接入，暂不可用）");
        translationModeView.setEnabled(translationAvailable);
        modeGroup.addView(translationModeView);
        modeGroup.check(transcriptModeView.getId());
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
        targetTitle.setText("互译目标语言（现场互译可用后选择）");
        layout.addView(targetTitle);
        targetLanguageView = new Spinner(this);
        targetLanguageView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, languages));
        targetLanguageView.setSelection(1);
        targetLanguageView.setEnabled(false);
        layout.addView(targetLanguageView);
        modeGroup.setOnCheckedChangeListener((group, checkedId) ->
                targetLanguageView.setEnabled(checkedId == translationModeView.getId()));

        presentationCaptureView = new CheckBox(this);
        presentationCaptureView.setText("演示文稿（会中采集待接入，暂不可用）");
        presentationCaptureView.setEnabled(false);
        layout.addView(presentationCaptureView);

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

        TextView meetingTitle = new TextView(this);
        meetingTitle.setText("实时字幕 · 会议中");
        meetingTitle.setTextSize(26);
        meetingLayout.addView(meetingTitle);

        elapsedView = new TextView(this);
        elapsedView.setText("已进行 00:00:00");
        meetingLayout.addView(elapsedView);

        transcriptView = new TextView(this);
        transcriptView.setTextSize(28);
        transcriptView.setText("等待字幕…");
        transcriptView.setPadding(0, 36, 0, 36);
        meetingLayout.addView(transcriptView);
        transcriptList = new LinearLayout(this);
        transcriptList.setOrientation(LinearLayout.VERTICAL);
        meetingLayout.addView(transcriptList);

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
        setContentView(root);
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
                ReferenceLoginSession loginSession = new HttpReferenceSessionClient(baseUrl)
                        .login(username, password);
                Log.i(TAG, "参考账号登录成功");
                runOnUiThread(() -> {
                    sessionToken = loginSession.accessToken();
                    sessionBackendUrl = baseUrl;
                    passwordView.setText("");
                    backendUrlView.setEnabled(false);
                    usernameView.setEnabled(false);
                    passwordView.setEnabled(false);
                    loginButton.setText("切换账号");
                    loginButton.setEnabled(true);
                    startButton.setEnabled(true);
                    statusView.setText("登录成功，可开始会议");
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
        backendUrlView.setEnabled(true);
        usernameView.setEnabled(true);
        passwordView.setEnabled(true);
        loginButton.setText("登录");
        startButton.setEnabled(false);
        statusView.setText("已退出，请重新登录");
        Log.i(TAG, "参考账号已退出");
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

    private void createAndStartMeeting() {
        if (sessionToken == null) {
            statusView.setText("请先登录");
            return;
        }
        // 点击开始时读取一次会前设置。会议进行中即使输入框变化，也使用这份快照。
        MeetingSetup setup = selectedSetup();
        if (!runtime.setupCapabilities().contains(setup.mode().requiredCapability())) {
            statusView.setText(setup.mode().displayName() + "当前不可用");
            return;
        }
        if (setup.resultDelivery() != MeetingSetup.ResultDelivery.WEB
                || !webResultView.isChecked() || setup.resultWebUrl().isEmpty()) {
            statusView.setText("当前仅支持网页查看，请填写网页结果地址");
            return;
        }
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
                presentationCaptureView.isChecked(), MeetingSetup.ResultDelivery.WEB,
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
        elapsedView.setText("已进行 " + state.display.elapsed(SystemClock.elapsedRealtime()));
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
            renderedTranscriptVersion = state.transcriptVersion;
        }
        transcriptView.setVisibility(transcriptRows.isEmpty() ? View.VISIBLE : View.GONE);
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
