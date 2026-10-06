package com.czur.aimeeting.reference;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;

/** P6 设备验收页：独立读取基础音频，展示实际来源、音频帧数和 PCM 峰值。 */
public final class AudioDiagnosticActivity extends Activity {
    private static final String TAG = "AiMeetingAudioDiagnostic";
    private static final int RECORD_AUDIO_REQUEST = 1;
    private TextView status;
    private AudioDiagnosticProbe probe;
    private String inputMode = "等待 SDK 选择";
    private String direction = "等待方向样本";
    private long frames;
    private int peak;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);
        status = new TextView(this);
        status.setTextSize(24);
        status.setText("等待开始读取 MIC");
        layout.addView(status);
        Button start = new Button(this);
        start.setText("开始诊断");
        start.setOnClickListener(view -> requestOrStart());
        layout.addView(start);
        Button permissionCheck = new Button(this);
        permissionCheck.setText("SDK 权限自检（不申请）");
        // 仅 Debug 验收入口：绕过页面权限预检，验证 SDK 无权限时返回错误而不崩溃。
        // 正常开始仍走 requestOrStart，由应用申请权限；Release 没有这个页面。
        permissionCheck.setOnClickListener(view -> startProbe());
        layout.addView(permissionCheck);
        Button stop = new Button(this);
        stop.setText("停止诊断");
        stop.setOnClickListener(view -> stopProbe());
        layout.addView(stop);
        setContentView(layout);
    }

    private void requestOrStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO},
                    RECORD_AUDIO_REQUEST);
            return;
        }
        startProbe();
    }

    private void startProbe() {
        stopProbe();
        inputMode = "等待 SDK 选择";
        direction = "等待方向样本";
        frames = 0;
        peak = 0;
        status.setText("已启动，等待音频帧");
        probe = AudioDiagnosticProbeFactory.create(this);
        try {
            probe.start(new AudioDiagnosticProbe.Listener() {
                @Override public void onLevel(String source, long frames, int peak) {
                    Log.i(TAG, "source=" + source + " frames=" + frames + " peak=" + peak);
                    runOnUiThread(() -> {
                        AudioDiagnosticActivity.this.frames = frames;
                        AudioDiagnosticActivity.this.peak = peak;
                        renderAudioState();
                    });
                }

                @Override public void onAudioInputMode(AudioInputMode mode) {
                    Log.i(TAG, "音频模式 mode=" + mode);
                    runOnUiThread(() -> {
                        inputMode = mode.name();
                        renderAudioState();
                    });
                }

                @Override public void onAudioDirection(AudioDirectionSample sample) {
                    runOnUiThread(() -> {
                        direction = sample.status().name()
                                + (sample.status() == AudioDirectionSample.Status.AVAILABLE
                                        ? " raw=" + sample.angleDegrees()
                                                + " stable=" + sample.stableAngleDegrees() : "")
                                + " t=" + sample.sampledAtElapsedRealtimeMs();
                        renderAudioState();
                    });
                }

                @Override public void onAudioError(AudioInputErrorCode code, String message) {
                    Log.w(TAG, "音频错误 code=" + code + " message=" + message);
                    runOnUiThread(() -> status.setText("音频错误：" + code + "\n" + message));
                }

                @Override public void onError(String message) {
                    Log.w(TAG, message);
                    runOnUiThread(() -> status.setText(message));
                }
            });
        } catch (RuntimeException failure) {
            status.setText("启动失败：" + failure.getMessage());
            Log.w(TAG, "诊断启动失败", failure);
        }
    }

    /** 同一快照同时展示模式、方向和有效音频；无效角度不会显示为方向验收成功。 */
    private void renderAudioState() {
        status.setText("来源：" + inputMode + "\n方向：" + direction
                + "\n音频帧：" + frames + "\n峰值：" + peak);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestOrStart();
        } else if (requestCode == RECORD_AUDIO_REQUEST) {
            status.setText("需要麦克风权限");
        }
    }

    private void stopProbe() {
        if (probe != null) {
            probe.close();
            probe = null;
        }
    }

    @Override protected void onDestroy() {
        stopProbe();
        super.onDestroy();
    }
}
