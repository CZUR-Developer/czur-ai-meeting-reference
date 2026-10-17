package com.czur.aimeeting.reference.identity

import android.app.Activity

/**
 * 参考 App 的登录交互入口，与 SDK 的身份数据 [com.czur.aimeeting.client.identity.IdentityProvider]
 * 分开替换。账号按钮在主线程调用本接口，默认显示参考账号密码弹窗。
 *
 * 合作伙伴可以打开 SSO 页面、系统个人中心，或使用自己的 ContentProvider 登录流程；
 * 更新装配的身份 Provider 后调用 [onIdentityChanged]，页面会重新读取身份与部署能力。
 * 登录状态保存、外部账号切换和退出由该插件与配套 Provider 负责，默认退出逻辑不会提前执行。
 * 如需沿用参考表单，直接调用 [defaultEntry]。取消登录无需回调，也不影响当前账号。
 *
 * [activity] 只供本次展示，插件不应长期持有它；异步流程结束可从任意线程调用
 * [onIdentityChanged]，调用方会切回主线程。会议音频、模型和鉴权流程不在此接口内。
 */
fun interface AppLoginEntry {
    fun open(activity: Activity, defaultEntry: () -> Unit, onIdentityChanged: () -> Unit)
}

/** 默认入口只委托原表单；它不自行保存密码或干预伙伴的身份生命周期。 */
object DefaultAppLoginEntry : AppLoginEntry {
    override fun open(activity: Activity, defaultEntry: () -> Unit, onIdentityChanged: () -> Unit) = defaultEntry()
}
