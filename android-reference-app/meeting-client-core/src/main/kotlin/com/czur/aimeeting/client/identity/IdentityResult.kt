package com.czur.aimeeting.client.identity

/** 最小身份结果，只有 LOGGED_IN 带会话，其他状态交 App 提示；不含原响应或密码。 */
class IdentityResult private constructor(
    private val status: Status,
    private val session: IdentitySession?,
    private val message: String,
) {
    enum class Status { LOGGED_IN, LOGIN_REQUIRED, READ_FAILED }
    fun status(): Status = status
    /** 仅登录成功非空。 */
    fun session(): IdentitySession? = session
    fun message(): String = message
    companion object {
        @JvmStatic fun loggedIn(session: IdentitySession): IdentityResult = IdentityResult(Status.LOGGED_IN, session, "已登录")
        /** 本地用户提示，App 自行选择登录 UI，不得携带凭据。 */
        @JvmStatic fun loginRequired(message: String): IdentityResult = IdentityResult(Status.LOGIN_REQUIRED, null, message)
        /** 不将读取失败解释成退出设备账号。 */
        @JvmStatic fun readFailed(message: String): IdentityResult = IdentityResult(Status.READ_FAILED, null, message)
    }
}
