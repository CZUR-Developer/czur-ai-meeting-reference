package com.czur.aimeeting.model

/** 本场短期总结材料，不得进入标准事件、结果或日志。 */
class SummarySessionGrant(
    private val adapterId: String,
    private val endpoint: String,
    private val expiresAt: String,
    private val token: String,
) {
    fun adapterId(): String = adapterId
    fun endpoint(): String = endpoint
    fun expiresAt(): String = expiresAt
    fun token(): String = token
    override fun toString(): String = "SummarySessionGrant[adapterId=$adapterId, endpoint=redacted, " +
        "expiresAt=$expiresAt, token=redacted]"
}
