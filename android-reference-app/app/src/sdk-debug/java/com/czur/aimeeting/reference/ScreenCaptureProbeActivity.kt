package com.czur.aimeeting.reference

import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.czur.aimeeting.sdk.ScreenCaptureClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Random

/**
 * SDK Debug 专用的截图 API 冒烟页，不登录、不开会、不配置模型，不进入 Release。
 *
 * 通过一次正常系统授权与投影前台 Service，在固定测试画面上调用伙伴同样的黑盒接口。
 * 仅验证返回图片、空结果、接受确认与资源归属，不读取 SDK 内部算法或判断依据。
 * 结果写入本 App 的 files/screen-api-probe/result.json，供开发机脚本读回。
 */
class ScreenCaptureProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var pattern: PatternView
    private var foreground: ScreenCaptureProbeService? = null
    private var capture: ScreenCaptureClient? = null
    private var overlay: Dialog? = null
    private var bound = false
    private val checks = JSONObject()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            foreground = (binder as ScreenCaptureProbeService.LocalBinder).service
            val manager = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else manager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(request, 1)
        }
        override fun onServiceDisconnected(name: ComponentName) { foreground = null; scope.cancel() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pattern = PatternView(this)
        setContentView(pattern)
        File(filesDir, "screen-api-probe/result.json").delete()
        bound = bindService(Intent(this, ScreenCaptureProbeService::class.java), connection, BIND_AUTO_CREATE)
    }

    @Deprecated("调试页沿用 Activity 的标准投影授权回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        scope.launch {
            try {
                check(resultCode == RESULT_OK && data != null) { "未取得屏幕授权" }
                requireNotNull(foreground).holdProjection()
                val main = Handler(Looper.getMainLooper())
                val client = ScreenCaptureClient(this@ScreenCaptureProbeActivity, resultCode, data,
                    { main.post(it) }, Runnable { scope.cancel("屏幕授权已结束") })
                capture = client
                runChecks(client)
                writeResult("PASS", null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // 不把底层异常或图片内容当作公共诊断信息，只给出本探针的固定断言名称。
                writeResult("FAIL", failure.message ?: failure.javaClass.simpleName)
            } finally {
                overlay?.dismiss(); overlay = null
                capture?.close(); capture = null
                foreground?.stopHoldingProjection()
                // 根 Activity 按返回可能只退后台；验完即解绑，不能等 onDestroy 才结束探针服务。
                if (bound) { unbindService(connection); bound = false }
                foreground = null
            }
        }
    }

    /** 固定图形承载不同画面；会话/确认只通过公开 API 操作。 */
    private suspend fun runChecks(client: ScreenCaptureClient) {
        val directory = File(filesDir, "screen-api-probe").apply { mkdirs() }
        val dialog = Dialog(this).apply {
            setContentView(View(this@ScreenCaptureProbeActivity).apply { setBackgroundColor(Color.MAGENTA) })
            setCancelable(false)
            show()
            window!!.setLayout(240, 160)
        }
        overlay = dialog
        delay(400) // 等待真实窗口首帧提交；不参与产品截图时序。
        val token = requireNotNull(dialog.window!!.decorView.windowToken)
        val exclusions = if (client.supportsWindowExclusion()) listOf(token) else emptyList()
        checks.put("mode", client.mode().name)
        val raw = client.createSession()
        val processing = client.createSession(enableProcessing = true)
        try {
            val shown = requireNotNull(raw.captureBitmap()).value
            val hidden = requireNotNull(raw.captureBitmap(exclusions)).value
            try {
                checks.put("bitmapDelivered", !shown.isRecycled && shown.width > 0)
                if (exclusions.isNotEmpty()) {
                    val position = IntArray(2)
                    dialog.window!!.decorView.getLocationOnScreen(position)
                    val x = position[0] + dialog.window!!.decorView.width / 2
                    val y = position[1] + dialog.window!!.decorView.height / 2
                    check(shown.getPixel(x, y) == Color.MAGENTA) { "排窗对照图缺少测试窗口" }
                    check(hidden.getPixel(x, y) != Color.MAGENTA) { "排窗图片仍包含测试窗口" }
                    checks.put("windowExcluded", true)
                }
                withContext(Dispatchers.IO) {
                    File(directory, "raw-excluded.png").outputStream().use {
                        check(hidden.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            } finally { shown.recycle(); hidden.recycle() }

            val first = requireNotNull(processing.captureFile(directory, exclusions)) { "首张测试图没有交付" }
            val retry = requireNotNull(processing.captureFile(directory, exclusions)) { "未接受的图片未再次交付" }
            check(first.value.isFile && retry.value.isFile) { "File 输出未落到目录" }
            retry.value.delete()
            first.confirmAccepted(); first.confirmAccepted()
            check(first.value.isFile) { "确认不应删除已交付文件" }
            check(processing.captureBitmap(exclusions) == null) { "同画面确认后仍重复交付" }
            checks.put("fileDelivered", true).put("unacceptedCanRetry", true).put("acceptedNoNewImage", true)
            checks.put("processedFile", first.value.name)

            pattern.frame = 1
            pattern.invalidate()
            delay(400)
            val changed = requireNotNull(processing.captureBitmap(exclusions)) { "变化画面未交付" }
            changed.confirmAccepted()
            check(!changed.value.isRecycled) { "确认不应回收已交付 Bitmap" }
            changed.value.recycle()
            checks.put("changedBitmapDelivered", true)
            processing.close()
            val stillRaw = requireNotNull(raw.captureFile(directory, exclusions))
            check(stillRaw.value.extension == "png") { "关闭处理应保持 PNG 输出" }
            checks.put("rawFile", stillRaw.value.name).put("rawSessionSurvivesOtherClose", true)
            client.createSession(enableProcessing = true).use { independent ->
                val fresh = requireNotNull(independent.captureBitmap(exclusions)) { "新会话不应继承上一场状态" }
                fresh.value.recycle()
            }
            checks.put("sessionIndependent", true)
        } finally { processing.close(); raw.close() }
    }

    private suspend fun writeResult(status: String, message: String?) {
        val result = JSONObject().put("status", status).put("checks", checks)
        message?.let { result.put("message", it) }
        withContext(Dispatchers.IO) {
            val directory = File(filesDir, "screen-api-probe").apply { mkdirs() }
            File(directory, "result.json").writeText(result.toString(2))
        }
        Log.i("ScreenCaptureApiProbe", "截图API验证 status=$status")
        setContentView(TextView(this).apply { textSize = 24f; text = "截图 API：$status\n${result.toString(2)}" })
    }

    override fun onDestroy() {
        scope.cancel()
        overlay?.dismiss()
        capture?.close()
        foreground?.stopHoldingProjection()
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    /** 测试画面没有系统页面或会议信息，使用固定种子，便于重复验证。 */
    private class PatternView(context: Context) : View(context) {
        var frame = 0
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            val random = Random(19L + frame * 997L)
            for (y in 0 until 12) for (x in 0 until 16) {
                paint.color = Color.rgb(random.nextInt(200), random.nextInt(200), random.nextInt(200))
                canvas.drawRect(x * width / 16f, y * height / 12f,
                    (x + 1) * width / 16f, (y + 1) * height / 12f, paint)
            }
        }
    }
}

/** 只维持探针这一份标准投影前台身份；不启动 MIC、会议或任何网络请求。 */
class ScreenCaptureProbeService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@ScreenCaptureProbeService }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    fun holdProjection() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture-api-probe", "截图 API 调试", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "capture-api-probe")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("截图 API 调试进行中").build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(41, notification)
    }
    fun stopHoldingProjection() { stopForeground(STOP_FOREGROUND_REMOVE) }
    override fun onDestroy() { stopHoldingProjection(); super.onDestroy() }
}
