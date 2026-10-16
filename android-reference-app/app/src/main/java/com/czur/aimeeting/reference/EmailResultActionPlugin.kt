package com.czur.aimeeting.reference

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.result.ResultActionPlugin.Preparation
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.result.ResultActionRegistry
import com.czur.aimeeting.reference.AppResultActionPlugin.Settings

/** 显式可选邮件动作，默认不注册；失败只影响动作，不改变 READY 或 genTime。 */
class EmailResultActionPlugin @JvmOverloads constructor(
    private val configuredUrl: String? = null, emails: List<String>? = null
) : AppResultActionPlugin {
    private val configuredEmails = emails?.toList()
    override fun id() = "email"
    override fun displayName() = "邮箱通知"
    override fun requiredServiceCapabilities() = setOf(ResultActionRegistry.RESULT_LINK, ResultActionRegistry.RESULT_EMAIL)
    override fun createSettings(pageContext: Context): Settings {
        val layout = LinearLayout(pageContext).apply { orientation = LinearLayout.VERTICAL }
        val url = EditText(pageContext).apply { hint = "网页结果地址" }
        val recipients = EditText(pageContext).apply { hint = "收件邮箱，多个用逗号分隔（最多10个）" }
        layout.addView(url)
        layout.addView(recipients)
        return Settings(layout) { EmailResultActionPlugin(url.text.toString().trim(),
            recipients.text.toString().split(Regex("[,，;；\\s]+")).filter { it.isNotBlank() }.map { it.trim() }) }
    }
    override fun prepare(options: MeetingOptions): Preparation {
        val url = configuredUrl ?: options.resultWebUrl()
        val emails = configuredEmails ?: options.resultEmails()
        if (!WebResultActionPlugin.validUrl(url)) return Preparation.block("请填写有效的网页结果地址")
        if (emails.isEmpty() || emails.size > 10 || emails.distinct().size != emails.size || emails.any { !EMAIL.matches(it) })
            return Preparation.block("请填写1～10个不重复的有效收件邮箱后开始会议")
        val frozen = emails.toList()
        return Preparation.allow { context, callback ->
            context.access().sendEmail(frozen,
                WebResultActionPlugin.resultUrl(url, context.result().meetingId(), context.result().genTime()),
                object : MeetingClient.Callback<Void?> {
                    override fun onSuccess(ignored: Void?) { callback.onSuccess(ResultActionOutcome.success(id(), "结果链接已发送到邮箱")) }
                    override fun onError(message: String) { callback.onError("邮件发送失败，请再次执行") }
                })
        }
    }
    companion object { private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") }
}
