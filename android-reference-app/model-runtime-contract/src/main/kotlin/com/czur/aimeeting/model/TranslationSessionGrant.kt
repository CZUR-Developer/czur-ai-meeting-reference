package com.czur.aimeeting.model

/**
 * 同一会议附带的可选文本翻译材料；随 modelSession 返回，不创建第二场会议或第二路音源。
 * endpoint、token 和 initParams 只用于 Adapter 请求，不进入字幕、结果或日志。材料由 Server
 * 按本场翻译授权签发；字段并不代表可自行提升能力。缺少本对象时保持原生实时译文路径。
 */
class TranslationSessionGrant @JvmOverloads constructor(
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
    /** 已签名 URL 可不带 token；鉴权头完全由 Adapter 的请求描述决定。 */
    fun token(): String? = token
    /** 本场厂商参数的只读副本，不包含标准会议状态。 */
    fun initParams(): Map<String, String> = initParams
    override fun toString(): String = "TranslationSessionGrant[adapterId=$adapterId, endpoint=redacted, " +
        "expiresAt=$expiresAt, token=redacted, initParams=redacted]"
}
