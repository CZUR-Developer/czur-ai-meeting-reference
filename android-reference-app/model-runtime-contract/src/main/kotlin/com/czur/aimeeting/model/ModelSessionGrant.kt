package com.czur.aimeeting.model

import java.util.Collections
import java.util.LinkedHashMap

/** 后端本场短期连接材料。endpoint 可含签名，initParams 属于供应商，均不得进入标准产物或日志。 */
class ModelSessionGrant @JvmOverloads constructor(
    private val meetingId: String,
    private val adapterId: String,
    private val endpoint: String,
    private val expiresAt: String,
    private val token: String?,
    initParams: Map<String, String>,
    private val translationSession: TranslationSessionGrant? = null,
) {
    private val initParams = Collections.unmodifiableMap(LinkedHashMap(initParams))
    fun meetingId(): String = meetingId
    fun adapterId(): String = adapterId
    fun endpoint(): String = endpoint
    fun expiresAt(): String = expiresAt
    /** 签名 URL 已足够时无需独立 token。 */
    fun token(): String? = token
    fun initParams(): Map<String, String> = initParams
    /** 仅 ASR＋独立文本翻译时存在；null 保持模型原生译文路径，原六参构造保持可用。 */
    fun translationSession(): TranslationSessionGrant? = translationSession
    override fun toString(): String = "ModelSessionGrant[meetingId=$meetingId, adapterId=$adapterId, " +
        "endpoint=redacted, expiresAt=$expiresAt, token=redacted]"
}
