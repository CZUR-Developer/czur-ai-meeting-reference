package com.czur.aimeeting.reference

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.czur.aimeeting.model.AudioDirectionSample
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.AudioInputMode

/** P6 设备验收页：独立读取基础音频，展示实际来源、音频帧数和 PCM 峰值。仅 Debug 构建包含。 */
class AudioDiagnosticActivity : Activity() {
    private lateinit var status: TextView
    private var probe: AudioDiagnosticProbe? = null
    private var inputMode = "等待 SDK 选择"
    private var direction = "等待方向样本"
    private var frames = 0L
    private var peak = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply {
            textSize = 24f
            text = "等待开始读取 MIC"
        }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "开始诊断"
            setOnClickListener { requestOrStart() }
        })
        layout.addView(Button(this).apply {
            text = "SDK 权限自检（不申请）"
            // 仅 Debug 验收入口：绕过页面权限预检，验证 SDK 无权限时返回错误而不崩溃。
            // 正常开始仍走 requestOrStart，由应用申请权限；Release 没有这个页面。
            setOnClickListener { startProbe() }
        })
        layout.addView(Button(this).apply {
            text = "停止诊断"
            setOnClickListener { stopProbe() }
        })
        setContentView(layout)
    }

    private fun requestOrStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
            return
        }
        startProbe()
    }

    private fun startProbe() {
        stopProbe()
        inputMode = "等待 SDK 选择"
        direction = "等待方向样本"
        frames = 0L
        peak = 0
        status.text = "已启动，等待音频帧"
        val current = AudioDiagnosticProbeFactory.create(this)
        probe = current
        try {
            current.start(object : AudioDiagnosticProbe.Listener {
                override fun onLevel(source: String, frames: Long, peak: Int) {
                    Log.i(TAG, "source=$source frames=$frames peak=$peak")
                    runOnUiThread {
                        this@AudioDiagnosticActivity.frames = frames
                        this@AudioDiagnosticActivity.peak = peak
                        renderAudioState()
                    }
                }

                override fun onAudioInputMode(mode: AudioInputMode) {
                    Log.i(TAG, "音频模式 mode=$mode")
                    runOnUiThread {
                        inputMode = mode.name
                        renderAudioState()
                    }
                }

                override fun onAudioDirection(sample: AudioDirectionSample) {
                    runOnUiThread {
                        direction = sample.status().name +
                            (if (sample.status() == AudioDirectionSample.Status.AVAILABLE) {
                                " raw=${sample.angleDegrees()} stable=${sample.stableAngleDegrees()}"
                            } else "") + " t=${sample.sampledAtElapsedRealtimeMs()}"
                        renderAudioState()
                    }
                }

                override fun onAudioError(code: AudioInputErrorCode, message: String) {
                    Log.w(TAG, "音频错误 code=$code message=$message")
                    runOnUiThread { status.text = "音频错误：$code\n$message" }
                }

                override fun onError(message: String) {
                    Log.w(TAG, message)
                    runOnUiThread { status.text = message }
                }
            })
        } catch (failure: RuntimeException) {
            status.text = "启动失败：${failure.message}"
            Log.w(TAG, "诊断启动失败", failure)
        }
    }

    /** 同一快照同时展示模式、方向和有效音频；无效角度不会显示为方向验收成功。 */
    private fun renderAudioState() {
        status.text = "来源：$inputMode\n方向：$direction\n音频帧：$frames\n峰值：$peak"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestOrStart()
        } else if (requestCode == RECORD_AUDIO_REQUEST) {
            status.text = "需要麦克风权限"
        }
    }

    private fun stopProbe() {
        probe?.close()
        probe = null
    }

    override fun onDestroy() {
        stopProbe()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AiMeetingAudioDiagnostic"
        const val RECORD_AUDIO_REQUEST = 1
    }
}
