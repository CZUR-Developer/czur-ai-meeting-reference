package com.czur.aimeeting.reference

import android.content.Context
import android.content.Intent
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.result.ResultActionPlugin.Preparation
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.result.ResultActionRegistry
import com.czur.aimeeting.reference.AppResultActionPlugin.Settings
import com.czur.aimeeting.reference.AppResultActionPlugin.UiOperation
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

/** 默认 Web 动作：会前冻结地址，READY 后只生成同场链接，不读取正文或发送邮件。 */
class WebResultActionPlugin @JvmOverloads constructor(
    private val configuredUrl: String? = null, private val openPage: Consumer<String>? = null
) : AppResultActionPlugin {
    override fun id() = "web"
    override fun displayName() = "网页查看"
    override fun requiredServiceCapabilities() = setOf(ResultActionRegistry.RESULT_LINK)
    override fun createSettings(pageContext: Context): Settings {
        val app = pageContext.applicationContext
        return Settings(null) {
            WebResultActionPlugin(MeetingAppAssembly.resultWebUrl()) { url ->
                app.startActivity(Intent(app, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_RESULT_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    override fun prepare(options: MeetingOptions): Preparation {
        val url = configuredUrl ?: options.resultWebUrl()
        if (!validUrl(url)) return Preparation.block("请填写有效的网页结果地址")
        return Preparation.allow { context, callback ->
            val link = resultUrl(url, context.result().meetingId(), context.result().genTime())
            context.uiExecutor().execute(UiOperation {
                try {
                    openPage!!.accept(link)
                    callback.onSuccess(ResultActionOutcome.success(id(), "已打开本场网页结果"))
                } catch (failure: RuntimeException) { callback.onError("无法打开网页结果，请再次执行") }
            })
        }
    }
    companion object {
        @JvmStatic fun validUrl(url: String?): Boolean = try {
            val uri = URI.create(url)
            ("https".equals(uri.scheme, true) || "http".equals(uri.scheme, true)) && uri.host != null
        } catch (invalid: IllegalArgumentException) { false } catch (invalid: NullPointerException) { false }

        /** 保留已有参数和 fragment，替换同名会议参数，避免链接指向旧场次。 */
        @JvmStatic fun resultUrl(base: String, meetingId: String, genTime: Long): String {
            val uri = URI.create(base)
            val query = mutableListOf<String>()
            if (!uri.rawQuery.isNullOrEmpty()) for (part in uri.rawQuery.split("&")) {
                val key = part.split("=", limit = 2)[0]
                if (key != "meetingId" && key != "genTime") query += part
            }
            query += "meetingId=" + URLEncoder.encode(meetingId, StandardCharsets.UTF_8)
            query += "genTime=$genTime"
            val path = base.split(Regex("[?#]"), limit = 2)[0]
            return path + "?" + query.joinToString("&") + (uri.rawFragment?.let { "#$it" } ?: "")
        }
    }
}
