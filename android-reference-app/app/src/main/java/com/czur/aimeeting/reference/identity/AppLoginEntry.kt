package com.czur.aimeeting.reference.identity

import android.app.Activity

/**
 * 参考 App 的登录交互入口，与 SDK 的身份数据 [com.czur.aimeeting.client.identity.IdentityProvider]
 * 分开替换。账号按钮在主线程调用本接口：未登录时走 [open]，已登录时走 [signOut]。
 *
 * 合作伙伴可以打开 SSO 页面、系统个人中心，或使用自己的 ContentProvider 登录流程；
 * 更新装配的身份 Provider 后调用 [onIdentityChanged]，页面会重新读取身份与部署能力。
 * 登录状态保存、外部账号切换和退出由该插件与配套 Provider 负责，页面不会先执行默认退出。
 * 如需沿用参考账号入口，[open] 直接调用 [defaultEntry]；[signOut] 直接调用
 * [defaultSignOut]。打开表单、取消或登录失败不会清除当前会话；仅新登录成功替换会话，
 * 或插件明确选择退出时清理。自定义入口取消登录无需回调，也不应先清除原有效身份。
 *
 * [activity] 只供本次展示，插件不应长期持有它；异步流程结束可从任意线程调用
 * [onIdentityChanged]，调用方会切回主线程。会议音频、模型和鉴权流程不在此接口内。
 */
fun interface AppLoginEntry {
    /** 未登录时的账号按钮出口：打开登录表单或伙伴自己的登录页面。 */
    fun open(activity: Activity, defaultEntry: () -> Unit, onIdentityChanged: () -> Unit)

    /**
     * 已登录时的账号按钮出口：伙伴接入系统账号／SSO 后在这里执行自己的退出或切换，
     * 默认实现只委托 [defaultSignOut] 清理参考会话。异步完成后调用 [onIdentityChanged]。
     */
    fun signOut(activity: Activity, defaultSignOut: () -> Unit, onIdentityChanged: () -> Unit) {
        defaultSignOut()
    }
}

/** 默认入口只委托参考实现；它不自行保存密码或干预伙伴的身份生命周期。 */
object DefaultAppLoginEntry : AppLoginEntry {
    override fun open(activity: Activity, defaultEntry: () -> Unit, onIdentityChanged: () -> Unit) = defaultEntry()
}
