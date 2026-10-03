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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.reference.meeting.HttpReferenceMeetingClient;
import com.czur.aimeeting.reference.meeting.CreatedMeeting;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeBridge;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingCapabilityState;
import com.czur.aimeeting.reference.meeting.ReferenceMeetingClient;
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
    private EditText backendUrlView;
    private EditText tokenView;
    private EditText resultWebUrlView;
    private TextView transcriptView;
    private TextView statusView;
    private TextView capabilityView;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = MeetingRuntimeFactory.create(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 40, 40, 40);

        backendUrlView = new EditText(this);
        backendUrlView.setSingleLine(true);
        backendUrlView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        backendUrlView.setHint("后端地址，例如 http://192.168.1.10:8080");
        layout.addView(backendUrlView);

        tokenView = new EditText(this);
        tokenView.setSingleLine(true);
        tokenView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenView.setHint("reference-dev 演示 Token");
        layout.addView(tokenView);

        resultWebUrlView = new EditText(this);
        resultWebUrlView.setSingleLine(true);
        resultWebUrlView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        resultWebUrlView.setHint("结果页地址，例如 http://192.168.1.10:8081/index.html");
        layout.addView(resultWebUrlView);

        transcriptView = new TextView(this);
        transcriptView.setTextSize(28);
        transcriptView.setText("填写后端地址和演示 Token，再开始" + runtime.displayName() + "会议");
        layout.addView(transcriptView);

        startButton = new Button(this);
        startButton.setText("开始会议");
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
        stopButton.setText("停止会议并查看结果");
        stopButton.setEnabled(false);
        layout.addView(stopButton);

        statusView = new TextView(this);
        statusView.setText("地址须由设备访问，设备上的 localhost 不是开发机");
        layout.addView(statusView);

        capabilityView = new TextView(this);
        capabilityView.setText("本场功能将在创建会议后按后端能力显示");
        layout.addView(capabilityView);
        setContentView(layout);
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
        String baseUrl = backendUrlView.getText().toString().trim();
        String token = tokenView.getText().toString().trim();
        if (baseUrl.isEmpty() || token.isEmpty()) {
            statusView.setText("请填写后端地址和演示 Token");
            return;
        }
        ReferenceMeetingClient client = new HttpReferenceMeetingClient(baseUrl, token);
        startButton.setEnabled(false);
        statusView.setText("正在创建会议…");
        network.execute(() -> {
            try {
                CreatedMeeting created = client.createMeeting();
                String meetingId = created.meetingId();
                Log.i(TAG, "会议已创建 meetingId=" + meetingId);
                client.startClient(meetingId);
                Log.i(TAG, "客户端会议已启动 meetingId=" + meetingId);
                ModelSessionGrant grant = runtime.waitsForModelFinish()
                        ? client.requestModelSession(meetingId) : null;
                if (grant != null) Log.i(TAG, "模型会话材料已领取 meetingId=" + meetingId
                        + " adapterId=" + grant.adapterId());
                CreatedMeeting ready = created.withModelSession(grant);
                runOnUiThread(() -> startModelMeeting(client, ready));
            } catch (IOException failure) {
                Log.e(TAG, "创建或启动会议失败", failure);
                runOnUiThread(() -> {
                    statusView.setText(failure.getMessage());
                    startButton.setEnabled(true);
                });
            }
        });
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
                    runOnUiThread(() -> statusView.setText("模型会话失败，请检查连接与模型配置"));
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
        String resultWebUrl = resultWebUrlView.getText().toString().trim();
        if (resultWebUrl.isEmpty()) {
            statusView.setText("请填写结果页地址");
            return;
        }
        activeResultWebUrl = resultWebUrl;
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
