package com.czur.aimeeting.model

/**
 * 原业务会议下一次连接的材料和全新解析实例。供应商序号／时间可能归零，不能复用旧实例。
 * 此类型不重开音源、录音或业务场次，连接代次及时间偏移由 Runtime 管理。
 */
data class ModelSessionConnection(private val grant: ModelSessionGrant, private val adapter: ModelAdapter) {
    /** 原 meetingId 的完整连接材料，不得记入日志。 */
    fun grant(): ModelSessionGrant = grant
    /** 注册工厂创建的新解析器，不与旧连接共享修订状态。 */
    fun adapter(): ModelAdapter = adapter
}
