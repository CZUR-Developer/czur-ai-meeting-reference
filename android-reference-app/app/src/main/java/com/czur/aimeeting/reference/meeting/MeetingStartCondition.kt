package com.czur.aimeeting.reference.meeting

/**
 * 合作伙伴可扩展的会前条件。主线程只检查已准备好的本地状态；需访问服务器的条件，
 * 应先异步获取并缓存，再在这里判断。检查发生在屏采授权、启动 Service 和 Create 之前，
 * 拒绝不会启动模型或产生本场计费，也不能替代后端鉴权。
 */
fun interface MeetingStartCondition {
    /** 每次点击开始都会检查；拒绝消息直接显示在会前页面。 */
    fun check(setup: MeetingSetup): Decision

    data class Decision(private val allowed: Boolean, private val message: String) {
        fun allowed() = allowed
        fun message() = message

        companion object {
            @JvmStatic fun allow() = Decision(true, "")
            @JvmStatic fun block(message: String) = Decision(false, message)
        }
    }
}
