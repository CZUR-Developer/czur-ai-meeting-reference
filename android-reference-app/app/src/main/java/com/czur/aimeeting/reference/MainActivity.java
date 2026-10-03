package com.czur.aimeeting.reference;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.reference.meeting.HttpReferenceMeetingClient;
import com.czur.aimeeting.reference.meeting.ReferenceMeetingClient;
import com.czur.aimeeting.reference.mock.MockModelAdapter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 参考版最小会议画面：创建会议后即时显示 Mock 字幕，并异步提交标准事件。 */
public final class MainActivity extends Activity {
    private static final String TAG = "AiMeetingReference";
    private final MockModelAdapter adapter = new MockModelAdapter();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private ModelSession session;
    private EditText backendUrlView;
    private EditText tokenView;
    private TextView transcriptView;
    private TextView statusView;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        transcriptView = new TextView(this);
        transcriptView.setTextSize(28);
        transcriptView.setText("填写后端地址和演示 Token，再开始会议");
        layout.addView(transcriptView);

        startButton = new Button(this);
        startButton.setText("开始会议");
        startButton.setOnClickListener(view -> createAndStartMeeting());
        layout.addView(startButton);

        statusView = new TextView(this);
        statusView.setText("地址须由设备访问，设备上的 localhost 不是开发机");
        layout.addView(statusView);
        setContentView(layout);
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
                String meetingId = client.createMeeting();
                Log.i(TAG, "会议已创建 meetingId=" + meetingId);
                client.startClient(meetingId);
                Log.i(TAG, "客户端会议已启动 meetingId=" + meetingId);
                runOnUiThread(() -> startMockMeeting(client, meetingId));
            } catch (IOException failure) {
                Log.e(TAG, "创建或启动会议失败", failure);
                runOnUiThread(() -> {
                    statusView.setText(failure.getMessage());
                    startButton.setEnabled(true);
                });
            }
        });
    }

    private void startMockMeeting(ReferenceMeetingClient client, String meetingId) {
        if (session != null) {
            session.close();
        }
        transcriptView.setText("等待字幕…");
        transcriptView.setAlpha(0.8f);
        statusView.setText("会议 " + meetingId + " 已开始，正在接收模拟字幕");
        AtomicInteger sequence = new AtomicInteger();
        Log.i(TAG, "开始模拟字幕 adapter=" + adapter.id() + " meetingId=" + meetingId);
        session = adapter.open(event -> {
            runOnUiThread(() -> showTranscript(event));
            int clientSequence = sequence.incrementAndGet();
            network.execute(() -> appendTranscript(client, meetingId, clientSequence, event));
        });
        session.start();
    }

    private void appendTranscript(ReferenceMeetingClient client, String meetingId,
            int clientSequence, TranscriptEvent event) {
        try {
            client.appendTranscript(meetingId, clientSequence, event);
            Log.i(TAG, "标准字幕事件已保存 meetingId=" + meetingId + " clientSequence="
                    + clientSequence + " segmentId=" + event.segmentId()
                    + " revision=" + event.revision());
            runOnUiThread(() -> statusView.setText("字幕已送达后端：" + clientSequence + "/3"));
        } catch (IOException failure) {
            Log.e(TAG, "字幕事件提交失败 meetingId=" + meetingId
                    + " clientSequence=" + clientSequence, failure);
            runOnUiThread(() -> statusView.setText("字幕提交失败：" + failure.getMessage()));
        }
    }

    private void showTranscript(TranscriptEvent event) {
        transcriptView.setText(event.text());
        transcriptView.setAlpha(event.isFinal() ? 1.0f : 0.8f);
        Log.d(TAG, "字幕片段=" + event.segmentId() + " 修订=" + event.revision()
                + " 最终=" + event.isFinal());
        if (event.isFinal()) {
            Log.i(TAG, "模拟字幕已完成");
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
