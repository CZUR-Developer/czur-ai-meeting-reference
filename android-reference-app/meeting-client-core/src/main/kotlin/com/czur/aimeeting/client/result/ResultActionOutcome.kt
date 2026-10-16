package com.czur.aimeeting.client.result

/** 单次查看／通知动作结果，独立于会议 READY，失败不重新触发模型／Stop／总结或计费。 */
data class ResultActionOutcome(private val actionId: String, private val status: Status, private val message: String) {
    enum class Status { SUCCEEDED, FAILED }
    fun actionId(): String = actionId
    fun status(): Status = status
    fun message(): String = message
    companion object {
        @JvmStatic fun success(id: String, message: String): ResultActionOutcome = ResultActionOutcome(id, Status.SUCCEEDED, message)
        @JvmStatic fun failure(id: String, message: String): ResultActionOutcome = ResultActionOutcome(id, Status.FAILED, message)
    }
}
