package com.czur.aimeeting.client.server

/** Stop 仅表达输入接管，accepted 为 true 仍须读取实际 Manifest 判断 READY。 */
data class StopMeetingResponse(private val accepted: Boolean, private val inputState: String?,
    private val finalizationReceipt: String?, private val manifestState: String?) {
    fun accepted(): Boolean = accepted
    fun inputState(): String? = inputState
    fun finalizationReceipt(): String? = finalizationReceipt
    fun manifestState(): String? = manifestState
}
