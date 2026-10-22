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
        @JvmStatic fun loggedIn(session: IdentitySession): IdentityResult = IdentityResult(Status.LOGGED_IN, session, "")
        /**
         * 本地提示属于调用方话术，SDK 只原样携带；App 自行选择登录 UI，不得携带凭据。
         */
        @JvmStatic fun loginRequired(message: String): IdentityResult = IdentityResult(Status.LOGIN_REQUIRED, null, message)
        /**
         * 调用方提示原样携带；SDK 自身不生成面向用户的句子。
         * 不将读取失败解释成退出设备账号。
         */
        @JvmStatic fun readFailed(message: String): IdentityResult = IdentityResult(Status.READ_FAILED, null, message)
        /** SDK 自身读取失败入口，不附带文案。 */
        @JvmStatic fun readFailed(): IdentityResult = readFailed("")
    }
}
