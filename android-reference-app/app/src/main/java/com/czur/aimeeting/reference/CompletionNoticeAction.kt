package com.czur.aimeeting.reference

import android.content.Context
import android.widget.Toast
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.result.ResultActionPlugin.Preparation
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.reference.AppResultActionPlugin.Settings
import com.czur.aimeeting.reference.AppResultActionPlugin.UiOperation
import java.util.function.Consumer

/** 最小替换示例，默认不注册；闭包只捕获应用 Context 与冻结参数。 */
class CompletionNoticeAction @JvmOverloads constructor(private val showNotice: Consumer<String>? = null) : AppResultActionPlugin {
    override fun id() = "example.notice"
    override fun displayName() = "完成提示（替换示例）"
    override fun createSettings(pageContext: Context): Settings {
        val app = pageContext.applicationContext
        return Settings(null) { CompletionNoticeAction { Toast.makeText(app, it, Toast.LENGTH_LONG).show() } }
    }
    override fun prepare(options: MeetingOptions) = Preparation.allow { context, callback ->
        context.uiExecutor().execute(UiOperation {
            try {
                showNotice!!.accept("本场结果已就绪：${context.result().meetingId()}")
                callback.onSuccess(ResultActionOutcome.success(id(), "本场结果已就绪，完成提示已显示"))
            } catch (failure: RuntimeException) { callback.onError("完成提示失败，请再次执行") }
        })
    }
}
