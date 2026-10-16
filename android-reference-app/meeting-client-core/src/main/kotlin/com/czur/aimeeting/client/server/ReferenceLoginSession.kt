package com.czur.aimeeting.client.server

/** 参考后端进程内会话，不自动打印／持久化令牌或密码。 */
class ReferenceLoginSession(private val accessToken: String, private val expiresAt: String) {
    /** 后续会议 Bearer 凭据，禁止日志输出。 */
    fun accessToken(): String = accessToken
    /** RFC3339 UTC 到期时刻。 */
    fun expiresAt(): String = expiresAt
}
