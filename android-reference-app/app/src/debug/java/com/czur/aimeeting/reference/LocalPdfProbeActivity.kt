package com.czur.aimeeting.reference

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.MeetingResultContent
import com.czur.aimeeting.client.result.ResultActionAccess
import com.czur.aimeeting.client.result.ResultActionContext
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.result.ResultActionRegistry
import com.czur.aimeeting.client.server.HttpMeetingServerClient
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingSetup
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug 专用结果验收入口：读取默认 Web 插件已保存的最近 READY 场次，调用原 PDF 插件。
 * 不新建会议、不注入身份、不更改默认动作注册表；所有网络／文件处理在工作队列执行。
 * 回执仅包含场次、数量和图片摘要，不输出正文、账号、Token、genTime 或完整结果链接。
 * 此入口不进入 Release；伙伴正式接入仍在 MeetingAppAssembly 注册自己的结果插件。
 */
class LocalPdfProbeActivity : Activity() {
    private val work = Executors.newSingleThreadExecutor()
    /** 离开验收页后不再派发迟到的 UI／插件续步，避免向已关闭队列提交工作。 */
    private val ui = Executor { command -> mainExecutor.execute { if (!isDestroyed) command.run() } }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 24f; text = "正在核对最近会议并生成本地 PDF…" }
        setContentView(status)
        MeetingAppAssembly.initialize(this)
        // 与 Web 插件共享最近场次指针；不从 ADB 接收结果链接或凭据。
        val link = getSharedPreferences("czur_reference_web_results", MODE_PRIVATE)
            .getString("last_ready_link", null)
        val uri = link?.let(Uri::parse)
        val meetingId = uri?.getQueryParameter("meetingId")
        val genTime = uri?.getQueryParameter("genTime")?.toLongOrNull()
        if (meetingId.isNullOrBlank() || genTime == null) { failed("尚无已保存的 Web 结果"); return }
        val expected = intent.getStringExtra("meetingId")
        if (expected != null && expected != meetingId) { failed("最近结果与指定场次不一致"); return }
        val plugin = LocalPdfResultActionPlugin().createSettings(this).freeze().get()
        val prepared = plugin.prepare(MeetingSetup.transcript("zh-CN", "").toOptions())
        val action = ResultActionRegistry.PreparedAction(plugin.id(), plugin.requiredArtifactTypes(),
            plugin.requiredServiceCapabilities(), requireNotNull(prepared.execution()))
        val config = MeetingAppAssembly.clientConfig(ui)
        work.execute {
            try {
                val identity = config.identityProvider().currentIdentity().session()
                    ?: throw IllegalStateException("需要先登录参考 App")
                val server = HttpMeetingServerClient(config.forSession(identity))
                val ready = server.readResult(meetingId, genTime, false)
                val receipt = JSONObject().put("meetingId", meetingId).put("status", "READING")
                val access = object : ResultActionAccess {
                    override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) {
                        work.execute {
                            try {
                                val content = requireNotNull(server.readResult(meetingId, genTime, true).content())
                                val images = JSONArray()
                                for (frame in content.presentationFrames()) images.put(JSONObject()
                                    .put("offsetMs", frame.offsetMs())
                                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(frame.jpeg())
                                        .joinToString("") { "%02x".format(it) }))
                                receipt.put("transcriptSegments", content.transcriptSegments().size)
                                    .put("summary", content.summary() != null).put("images", images)
                                ui.execute { callback.onSuccess(content) }
                            } catch (failure: Exception) {
                                ui.execute { callback.onError("读取本场正文失败") }
                            }
                        }
                    }
                    override fun sendEmail(recipients: List<String>, resultUrl: String, callback: MeetingClient.Callback<Void?>) {
                        callback.onError("PDF 验收不发送邮件")
                    }
                }
                val context = ResultActionContext(ready, access, emptySet(), work, ui)
                ResultActionRegistry(listOf(plugin)).execute(action, context, object : MeetingClient.Callback<ResultActionOutcome> {
                    override fun onSuccess(result: ResultActionOutcome) {
                        val ok = result.status() == ResultActionOutcome.Status.SUCCEEDED
                        work.execute {
                            receipt.put("status", if (ok) "PASS" else "FAIL")
                            val file = File(getExternalFilesDir(null), "local-pdf-probe.json")
                            file.writeText(receipt.toString(2))
                            ui.execute {
                                Log.i(TAG, "status=${if (ok) "PASS" else "FAIL"} meetingId=$meetingId")
                                if (ok) finish() else failed("本地 PDF 插件执行失败")
                            }
                        }
                    }
                    override fun onError(error: MeetingError) = failed("本地 PDF 插件执行失败 code=${error.code}")
                })
            } catch (failure: Exception) { ui.execute { failed("结果读取失败 type=${failure.javaClass.simpleName}") } }
        }
    }

    private fun failed(message: String) { status.text = message; Log.w(TAG, "status=FAIL $message") }
    override fun onDestroy() { work.shutdown(); super.onDestroy() }
    companion object { private const val TAG = "ReferencePdfProbe" }
}
