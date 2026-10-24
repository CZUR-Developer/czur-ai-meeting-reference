package com.czur.aimeeting.reference

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
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

/**
 * 默认 Web 动作：会前冻结地址，READY 后记住并打开同场链接，不读取正文或发送邮件。
 * 最近链接属于此插件的普通查看偏好，不恢复会议／Session，也不复制任何结果状态。
 */
class WebResultActionPlugin private constructor(
    private val configuredUrl: String?, private val openPage: Consumer<String>?,
    private val recentResults: SharedPreferences?
) : AppResultActionPlugin {
    /** 无 Android 存储的构建期装配仍可使用原构造入口，由宿主提供打开动作。 */
    @JvmOverloads constructor(configuredUrl: String? = null, openPage: Consumer<String>? = null) :
        this(configuredUrl, openPage, null)

    override fun id() = "web"
    override fun displayName() = "网页查看"
    override fun requiresResultWebUrl() = true
    override fun requiredServiceCapabilities() = setOf(ResultActionRegistry.RESULT_LINK)
    override fun createSettings(pageContext: Context): Settings {
        val app = pageContext.applicationContext
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val opener = Consumer<String> { url ->
            app.startActivity(Intent(app, ResultActivity::class.java)
                .putExtra(ResultActivity.EXTRA_RESULT_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // 历史结果统一走会议记录列表，会前不再单独放“查看上场结果”按钮。
        // freeze 只引用应用 Context 对应的 opener、普通偏好和地址，不引用按钮／Activity。
        return Settings(null) {
            WebResultActionPlugin(MeetingAppAssembly.resultWebUrl(), opener, preferences)
        }
    }
    override fun prepare(options: MeetingOptions): Preparation {
        val url = configuredUrl ?: options.resultWebUrl()
        if (!validUrl(url)) return Preparation.block("网页结果尚未配置，请联系应用提供方")
        return Preparation.allow { context, callback ->
            val link = resultUrl(url, context.result().meetingId(), context.result().genTime())
            // 此执行入口只由原 READY 结果动作调用。先记住原链接，再等待可见页面；
            // 即使后台队列尚未消费、Service 正常退出或页面重建，也能手动查看本场。
            // 仅写一条链接，不保存正文、不改 genTime、不声称 UI 动作已经完成。
            recentResults?.let { preferences ->
                preferences.edit().putString(KEY_LAST_READY_LINK, link).apply()
                Log.d(TAG, "已记住本场网页结果 meetingId=${context.result().meetingId()}")
            }
            context.uiExecutor().execute(UiOperation {
                try {
                    openPage!!.accept(link)
                    callback.onSuccess(ResultActionOutcome.success(id(), "已打开本场网页结果"))
                } catch (failure: RuntimeException) { callback.onError("无法打开网页结果，请再次执行") }
            })
        }
    }

    companion object {
        private const val TAG = "AiMeetingReference"
        private const val PREFERENCES = "czur_reference_web_results"
        private const val KEY_LAST_READY_LINK = "last_ready_link"
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
