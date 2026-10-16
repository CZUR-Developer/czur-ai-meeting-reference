package com.czur.aimeeting.client.identity

/** 参考 App 进程内身份，页面／Service 共用；不持久化、不实现用户库，伙伴可整体替换。 */
class ReferenceIdentityProvider @JvmOverloads constructor(accessToken: String = "") : IdentityProvider {
    @Volatile private var session: IdentitySession? =
        if (accessToken.isBlank()) null else IdentitySession(accessToken, "")
    /** 登录成功更新同一实例，不保存账号密码或打印会话。 */
    fun updateSession(session: IdentitySession?) { this.session = session }
    /** 只清除参考会话，不影响系统账号。 */
    fun clearSession() { session = null }
    override fun currentIdentity(): IdentityResult {
        val current = session
        return if (current == null) IdentityResult.loginRequired("请先登录") else IdentityResult.loggedIn(current)
    }
}
