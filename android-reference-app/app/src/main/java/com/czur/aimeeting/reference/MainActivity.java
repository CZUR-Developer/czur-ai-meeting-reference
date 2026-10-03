package com.czur.aimeeting.reference;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.reference.mock.MockModelAdapter;

/** 参考版最小会议画面：本地即时显示标准字幕，后续 P4-2 再接后端。 */
public final class MainActivity extends Activity {
    private static final String TAG = "AiMeetingReference";
    private final MockModelAdapter adapter = new MockModelAdapter();
    private ModelSession session;
    private TextView transcriptView;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 40, 40, 40);

        transcriptView = new TextView(this);
        transcriptView.setTextSize(28);
        transcriptView.setText("点击开始，查看模拟字幕");
        layout.addView(transcriptView);

        startButton = new Button(this);
        startButton.setText("开始会议");
        startButton.setOnClickListener(view -> startMockMeeting());
        layout.addView(startButton);
        setContentView(layout);
    }

    private void startMockMeeting() {
        if (session != null) {
            session.close();
        }
        transcriptView.setText("等待字幕…");
        transcriptView.setAlpha(0.8f);
        startButton.setEnabled(false);
        Log.i(TAG, "开始模拟会议，adapter=" + adapter.id());
        session = adapter.open(event -> runOnUiThread(() -> showTranscript(event)));
        session.start();
    }

    private void showTranscript(TranscriptEvent event) {
        transcriptView.setText(event.text());
        transcriptView.setAlpha(event.isFinal() ? 1.0f : 0.8f);
        Log.d(TAG, "字幕片段=" + event.segmentId() + " 修订=" + event.revision()
                + " 最终=" + event.isFinal() + " 文字=" + event.text());
        if (event.isFinal()) {
            Log.i(TAG, "模拟会议字幕已完成");
            startButton.setEnabled(true);
            startButton.setText("重新开始");
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
        }
        super.onDestroy();
    }
}
