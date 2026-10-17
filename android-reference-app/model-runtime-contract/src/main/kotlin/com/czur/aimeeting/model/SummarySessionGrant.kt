package com.czur.aimeeting.model

/** 本场短期总结材料，不得进入标准事件、结果或日志。 */
class SummarySessionGrant @JvmOverloads constructor(
    private val adapterId: String,
    private val endpoint: String,
    private val expiresAt: String,
    private val token: String?,
    initParams: Map<String, String> = emptyMap(),
) {
    private val initParams = java.util.Collections.unmodifiableMap(LinkedHashMap(initParams))
    fun adapterId(): String = adapterId
    fun endpoint(): String = endpoint
    fun expiresAt(): String = expiresAt
    /** 已签名 URL 或其他请求材料足够时可以为空，不应生成空 Bearer。 */
    fun token(): String? = token
    /** 厂商版本、请求材料等仅交 Adapter；不进入标准结果或日志。 */
    fun initParams(): Map<String, String> = initParams
    override fun toString(): String = "SummarySessionGrant[adapterId=$adapterId, endpoint=redacted, " +
        "expiresAt=$expiresAt, token=redacted]"
}
