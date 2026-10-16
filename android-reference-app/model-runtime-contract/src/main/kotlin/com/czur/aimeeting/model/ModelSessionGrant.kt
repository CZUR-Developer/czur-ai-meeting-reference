package com.czur.aimeeting.model

import java.util.Collections
import java.util.LinkedHashMap

/** 后端本场短期连接材料。endpoint 可含签名，initParams 属于供应商，均不得进入标准产物或日志。 */
class ModelSessionGrant(
    private val meetingId: String,
    private val adapterId: String,
    private val endpoint: String,
    private val expiresAt: String,
    private val token: String?,
    initParams: Map<String, String>,
) {
    private val initParams = Collections.unmodifiableMap(LinkedHashMap(initParams))
    fun meetingId(): String = meetingId
    fun adapterId(): String = adapterId
    fun endpoint(): String = endpoint
    fun expiresAt(): String = expiresAt
    /** 签名 URL 已足够时无需独立 token。 */
    fun token(): String? = token
    fun initParams(): Map<String, String> = initParams
    override fun toString(): String = "ModelSessionGrant[meetingId=$meetingId, adapterId=$adapterId, " +
        "endpoint=redacted, expiresAt=$expiresAt, token=redacted]"
}
