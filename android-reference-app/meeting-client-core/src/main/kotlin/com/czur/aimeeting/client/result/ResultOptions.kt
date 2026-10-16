package com.czur.aimeeting.client.result

import com.czur.aimeeting.client.MeetingOptions

/** 稳定动作 ID 与主动交付策略独立；参数由插件 prepare 冻结，不交付不表示删除或改变计费。 */
data class ResultOptions(private val policy: Policy, private val actionId: String) {
    enum class Policy { READY, NO_ACTIVE_DELIVERY }
    init { require(policy != Policy.READY || actionId.isNotBlank()) { "正常结果需指定动作ID" } }
    fun policy(): Policy = policy
    fun actionId(): String = actionId
    /** 旧枚举仅用于兼容占位，新动作按真实 ID 注册／执行。 */
    fun legacyDelivery(): MeetingOptions.ResultDelivery = when {
        policy == Policy.NO_ACTIVE_DELIVERY -> MeetingOptions.ResultDelivery.TRACELESS
        actionId == "local.pdf" -> MeetingOptions.ResultDelivery.LOCAL
        actionId == "email" -> MeetingOptions.ResultDelivery.EMAIL
        else -> MeetingOptions.ResultDelivery.WEB
    }
    companion object {
        @JvmStatic fun action(actionId: String): ResultOptions = ResultOptions(Policy.READY, actionId)
        @JvmStatic fun noActiveDelivery(): ResultOptions = ResultOptions(Policy.NO_ACTIVE_DELIVERY, "")
        @JvmStatic fun fromLegacy(delivery: MeetingOptions.ResultDelivery): ResultOptions = when (delivery) {
            MeetingOptions.ResultDelivery.WEB -> action("web")
            MeetingOptions.ResultDelivery.LOCAL -> action("local.pdf")
            MeetingOptions.ResultDelivery.EMAIL -> action("email")
            MeetingOptions.ResultDelivery.TRACELESS -> noActiveDelivery()
        }
    }
}
