package com.czur.aimeeting.client.identity

/** 部署后端 Bearer 会话与 UI 名称；不保留密码、不登出或刷新，避免自动 toString 暴露凭据。 */
class IdentitySession(private val accessToken: String, private val displayName: String) {
    init { require(accessToken.isNotBlank()) { "登录会话缺少凭据" } }
    /** 仅供会议请求使用，禁止打印或加入提示。 */
    fun accessToken(): String = accessToken
    /** 只供展示，不参与权益／账号相同判定。 */
    fun displayName(): String = displayName
}
