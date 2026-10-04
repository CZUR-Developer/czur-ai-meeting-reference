package com.czur.aimeeting.reference;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.reference.meeting.HttpReferenceMeetingClient;
import com.czur.aimeeting.reference.meeting.HttpReferenceSessionClient;
import com.czur.aimeeting.reference.meeting.CreatedMeeting;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeBridge;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingCapabilityState;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.meeting.ReferenceMeetingClient;
import com.czur.aimeeting.reference.meeting.ReferenceLoginSession;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** 参考版最小会议画面：模型事件先显示，再按顺序提交后端标准事件。 */
public final class MainActivity extends Activity {
    private static final String TAG = "AiMeetingReference";
    private static final int MIC_PERMISSION_REQUEST = 1001;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private MeetingRuntimeBridge runtime;
    private ModelSession session;
    private volatile boolean stopRequested;
    private volatile boolean modelFinished;
    private volatile String activeResultWebUrl;
    private MeetingSetup activeSetup;
    private EditText backendUrlView;
    private EditText usernameView;
    private EditText passwordView;
    private EditText resultWebUrlView;
    private TextView transcriptView;
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
    private Button loginButton;
    private String sessionToken;
    private String sessionBackendUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = MeetingRuntimeFactory.create(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 40, 40, 40);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(layout);

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

        transcriptView = new TextView(this);
        transcriptView.setTextSize(28);
        transcriptView.setText("请先登录，再开始" + runtime.displayName() + "会议");
        layout.addView(transcriptView);

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
        layout.addView(stopButton);

        statusView = new TextView(this);
        statusView.setText("填写 HTTPS 后端地址和部署者预置的账号");
        layout.addView(statusView);

        capabilityView = new TextView(this);
        capabilityView.setText("本场功能将在创建会议后按后端能力显示");
        layout.addView(capabilityView);
        setContentView(scrollView);
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
        ReferenceMeetingClient client = new HttpReferenceMeetingClient(sessionBackendUrl,
                sessionToken);
        startButton.setEnabled(false);
        loginButton.setEnabled(false);
        statusView.setText("正在创建会议…");
        network.execute(() -> {
            try {
                CreatedMeeting created = client.createMeeting(setup);
                String meetingId = created.meetingId();
                Log.i(TAG, "会议已创建 meetingId=" + meetingId + " mode=" + setup.mode()
                        + " sourceLanguage=" + setup.sourceLanguage());
                // Create 只表达请求，必须先核对后端冻结的本场能力，再打开模型/启动计费。
                if (!created.capabilities().canUse(setup.mode().requiredCapability(),
                        runtime.setupCapabilities())) {
                    throw new IOException("本场后端未开放" + setup.mode().displayName());
                }
                ModelSessionGrant grant = runtime.waitsForModelFinish()
                        ? client.requestModelSession(meetingId) : null;
                if (grant != null) Log.i(TAG, "模型会话材料已领取 meetingId=" + meetingId
                        + " adapterId=" + grant.adapterId());
                CreatedMeeting ready = created.withModelSession(grant);
                if (!ready.capabilities().canUse(setup.mode().requiredCapability(),
                        runtime.realtimeCapabilities(ready))) {
                    throw new IOException("本场模型 Adapter 未开放" + setup.mode().displayName());
                }
                client.startClient(meetingId);
                Log.i(TAG, "客户端会议已启动 meetingId=" + meetingId);
                activeSetup = setup;
                runOnUiThread(() -> startModelMeeting(client, ready));
            } catch (IOException failure) {
                Log.e(TAG, "创建或启动会议失败", failure);
                runOnUiThread(() -> {
                    statusView.setText(failure.getMessage());
                    startButton.setEnabled(true);
                    loginButton.setEnabled(true);
                });
            }
        });
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

    private void startModelMeeting(ReferenceMeetingClient client, CreatedMeeting created) {
        if (session != null) {
            session.close();
        }
        String meetingId = created.meetingId();
        stopRequested = false;
        modelFinished = false;
        transcriptView.setText("等待字幕…");
        transcriptView.setAlpha(0.8f);
        statusView.setText("会议 " + meetingId + " 已开始，正在接收" + runtime.displayName() + "字幕");
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger saved = new AtomicInteger();
        AtomicBoolean appendFailed = new AtomicBoolean();
        AtomicBoolean completionQueued = new AtomicBoolean();
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(view -> requestStop(client, meetingId,
                appendFailed, completionQueued));
        try {
            Set<String> localCapabilities = runtime.realtimeCapabilities(created);
            MeetingCapabilityState capabilities = created.capabilities();
            capabilityView.setText("本场功能：实时字幕"
                    + (capabilities.canUse(MeetingCapabilityState.TRANSLATION,
                            localCapabilities) ? "、实时翻译" : "；实时翻译已关闭")
                    + (capabilities.canUse(MeetingCapabilityState.SUMMARY,
                            localCapabilities) ? "、会议纪要" : "；会议纪要已关闭"));
            session = runtime.open(created, event -> {
                runOnUiThread(() -> showTranscript(event));
                int clientSequence = sequence.incrementAndGet();
                network.execute(() -> appendTranscript(client, meetingId,
                        clientSequence, event, saved, appendFailed));
            }, new ModelSessionListener() {
                @Override
                public void onFinished() {
                    modelFinished = true;
                    Log.i(TAG, "模型最终输出已接收 meetingId=" + meetingId);
                    if (stopRequested) scheduleComplete(client, meetingId,
                            appendFailed, completionQueued);
                    else runOnUiThread(() -> statusView.setText("模型已完成，可结束会议"));
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "模型会话失败 meetingId=" + meetingId);
                    runOnUiThread(() -> {
                        statusView.setText("模型会话失败，请检查连接与模型配置");
                        loginButton.setEnabled(true);
                    });
                }
            });
            Log.i(TAG, "开始模型会话 runtime=" + runtime.displayName()
                    + " meetingId=" + meetingId);
            session.start();
            if (runtime.waitsForModelFinish()) stopButton.setEnabled(true);
        } catch (RuntimeException failure) {
            Log.e(TAG, "打开模型会话失败 meetingId=" + meetingId
                    + " errorType=" + failure.getClass().getSimpleName());
            statusView.setText("打开模型会话失败，请检查模型接入配置");
            startButton.setEnabled(true);
            loginButton.setEnabled(true);
        }
    }

    private void appendTranscript(ReferenceMeetingClient client, String meetingId,
            int clientSequence, TranscriptEvent event, AtomicInteger saved,
            AtomicBoolean appendFailed) {
        try {
            client.appendTranscript(meetingId, clientSequence, event);
            int savedCount = saved.incrementAndGet();
            Log.i(TAG, "标准字幕事件已保存 meetingId=" + meetingId + " clientSequence="
                    + clientSequence + " segmentId=" + event.segmentId()
                    + " revision=" + event.revision());
            runOnUiThread(() -> {
                statusView.setText("字幕已送达后端：" + savedCount);
                if (!runtime.waitsForModelFinish() && event.isFinal() && savedCount == 3) {
                    stopButton.setEnabled(true);
                }
            });
        } catch (IOException failure) {
            appendFailed.set(true);
            Log.e(TAG, "字幕事件提交失败 meetingId=" + meetingId
                    + " clientSequence=" + clientSequence, failure);
            runOnUiThread(() -> statusView.setText("字幕提交失败：" + failure.getMessage()));
        }
    }

    private void requestStop(ReferenceMeetingClient client, String meetingId,
            AtomicBoolean appendFailed, AtomicBoolean completionQueued) {
        activeResultWebUrl = activeSetup.resultWebUrl();
        stopRequested = true;
        stopButton.setEnabled(false);
        statusView.setText("正在等待模型最终字幕…");
        network.execute(() -> {
            try {
                session.stopInput();
                if (!runtime.waitsForModelFinish() || modelFinished) {
                    scheduleComplete(client, meetingId, appendFailed, completionQueued);
                }
            } catch (RuntimeException failure) {
                Log.e(TAG, "停止模型输入失败 meetingId=" + meetingId
                        + " errorType=" + failure.getClass().getSimpleName());
                runOnUiThread(() -> statusView.setText("停止模型输入失败"));
            }
        });
    }

    private void scheduleComplete(ReferenceMeetingClient client, String meetingId,
            AtomicBoolean appendFailed, AtomicBoolean completionQueued) {
        if (completionQueued.compareAndSet(false, true)) {
            // 单线程队列保证所有先收到的字幕先送达后端，再请求会议 Stop。
            network.execute(() -> completeMeeting(client, meetingId,
                    appendFailed, completionQueued));
        }
    }

    private void completeMeeting(ReferenceMeetingClient client, String meetingId,
            AtomicBoolean appendFailed, AtomicBoolean completionQueued) {
        if (appendFailed.get()) {
            runOnUiThread(() -> statusView.setText("字幕提交失败，无法完成会议"));
            return;
        }
        try {
            boolean accepted = client.stopMeeting(meetingId);
            if (!accepted) {
                completionQueued.set(false);
                runOnUiThread(() -> {
                    statusView.setText("后端仍在收口，请稍后再点停止");
                    stopButton.setEnabled(true);
                });
                return;
            }
            session.close();
            long genTime = System.currentTimeMillis();
            Uri resultUri = Uri.parse(activeResultWebUrl).buildUpon()
                    .appendQueryParameter("meetingId", meetingId)
                    .appendQueryParameter("genTime", Long.toString(genTime))
                    .build();
            Log.i(TAG, "会议结束，打开会后结果 meetingId=" + meetingId);
            runOnUiThread(() -> {
                statusView.setText("会议已结束，正在打开结果页");
                startButton.setEnabled(true);
                loginButton.setEnabled(true);
                Intent intent = new Intent(this, ResultActivity.class);
                intent.putExtra(ResultActivity.EXTRA_RESULT_URL, resultUri.toString());
                startActivity(intent);
            });
        } catch (IOException failure) {
            completionQueued.set(false);
            Log.e(TAG, "结束会议失败 meetingId=" + meetingId, failure);
            runOnUiThread(() -> {
                statusView.setText("结束会议失败：" + failure.getMessage());
                stopButton.setEnabled(true);
            });
        }
    }

    private void showTranscript(TranscriptEvent event) {
        transcriptView.setText(event.text());
        transcriptView.setAlpha(event.isFinal() ? 1.0f : 0.8f);
        Log.d(TAG, "字幕片段=" + event.segmentId() + " 修订=" + event.revision()
                + " 最终=" + event.isFinal());
        if (event.isFinal()) {
            Log.i(TAG, "最终字幕已显示 segmentId=" + event.segmentId());
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
        }
        network.shutdownNow();
        super.onDestroy();
    }
}
