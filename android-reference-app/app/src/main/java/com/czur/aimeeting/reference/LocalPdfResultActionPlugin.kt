package com.czur.aimeeting.reference

import android.content.Context
import android.content.Intent
import android.util.Log
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.MeetingResultContent
import com.czur.aimeeting.client.result.ResultActionPlugin.Preparation
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.reference.AppResultActionPlugin.Settings
import com.czur.aimeeting.reference.AppResultActionPlugin.UiOperation
import java.io.IOException

/** 可选 PDF 插件：只在 READY 后读正文，文件工作仍走原单线程平台队列。 */
class LocalPdfResultActionPlugin private constructor(private val app: Context?) : AppResultActionPlugin {
    constructor() : this(null)
    override fun id() = "local.pdf"
    override fun displayName() = "本地保存PDF"
    override fun requiredArtifactTypes() = setOf("transcript.final")
    override fun createSettings(pageContext: Context): Settings {
        val context = pageContext.applicationContext
        return Settings(null) { LocalPdfResultActionPlugin(context) }
    }
    override fun prepare(options: MeetingOptions) = Preparation.allow { context, callback ->
        context.access().readContent(object : MeetingClient.Callback<MeetingResultContent?> {
            override fun onSuccess(content: MeetingResultContent?) {
                context.workExecutor().execute {
                    try {
                        val owner = app!!
                        val ready = context.result()
                        val full = MeetingResult(ready.meetingId(), ready.title(), ready.genTime(), ready.status(), ready.artifactTypes(), content)
                        val pdf = LocalPdfWriter(owner).write(ready.meetingId(), full)
                        LocalResultActivity.remember(owner, pdf, pdf.uri() != null)
                        context.uiExecutor().execute(UiOperation {
                            try {
                                val intent = Intent(owner, LocalResultActivity::class.java)
                                pdf.uri()?.let { intent.putExtra(LocalResultActivity.EXTRA_URI, it.toString()) }
                                intent.putExtra(LocalResultActivity.EXTRA_CACHE_PATH, pdf.cachePath())
                                intent.putExtra(LocalResultActivity.EXTRA_FILE_NAME, pdf.displayName())
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                owner.startActivity(intent)
                                callback.onSuccess(ResultActionOutcome.success(id(), if (pdf.uri() == null)
                                    "PDF已生成，请选择公共下载位置" else "PDF已保存到下载/AI会议纪要"))
                            } catch (failure: RuntimeException) { callback.onError("无法打开PDF，请再次执行") }
                        })
                    } catch (failure: IOException) {
                        Log.w("AiMeetingReference", "PDF动作失败 type=${failure.javaClass.simpleName}")
                        callback.onError("本地PDF保存失败，请再次执行")
                    } catch (failure: RuntimeException) {
                        Log.w("AiMeetingReference", "PDF动作失败 type=${failure.javaClass.simpleName}")
                        callback.onError("本地PDF保存失败，请再次执行")
                    }
                }
            }
            override fun onError(message: String) { callback.onError("读取本场正文失败，请再次执行") }
        })
    }
}
