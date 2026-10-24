package com.czur.aimeeting.reference

import android.content.Context
import android.view.View
import com.czur.aimeeting.client.result.ResultActionPlugin
import java.util.function.Supplier

/**
 * App 结果动作的薄 UI 边界。设置只留在页面，会前 freeze 返回的 core 插件只持有
 * 冻结参数和 applicationContext，不得捕获 Activity/View。Service 只消费准备好的闭包。
 */
interface AppResultActionPlugin : ResultActionPlugin {
    /**
     * 该动作是否依赖部署的网页结果地址。默认不依赖；默认 Web／邮件动作返回 true。
     * 只有所有已注册动作都依赖时，缺失 resultWebUrl 才应该阻止登录和能力发现。
     */
    fun requiresResultWebUrl(): Boolean = false

    /** 主线程创建设置；无输入动作可以返回 null View。 */
    fun createSettings(pageContext: Context): Settings

    data class Settings(private val view: View?, private val freeze: Supplier<ResultActionPlugin>) {
        fun view() = view
        fun freeze() = freeze
    }

    /**
     * 已准备的平台显示操作。只捕获应用 Context、冻结参数和完成回调，不访问关闭的 Session。
     * 页面可见后消费一次，不承诺进程恢复，不从后台强拉页面。
     */
    data class UiOperation(private val command: Runnable) : Runnable {
        fun command() = command
        override fun run() = command.run()
    }
}
