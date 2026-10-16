package com.czur.aimeeting.client

/** 部署方配置的客户端身份，不属于系统账号或模型供应商。 */
data class ClientIdentity(
    private val appId: String,
    private val appVersion: String,
    private val contractVersion: String,
    private val deviceId: String,
) {
    fun appId(): String = appId
    fun appVersion(): String = appVersion
    fun contractVersion(): String = contractVersion
    fun deviceId(): String = deviceId
    companion object {
        /** 旧参考装配的身份默认值。 */
        @JvmStatic fun reference(): ClientIdentity = ClientIdentity("com.czur.aimeeting.reference", "0.1.0", "v1", "reference-android")
    }
}
