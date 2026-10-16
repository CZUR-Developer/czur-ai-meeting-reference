package com.czur.aimeeting.client.result

import com.czur.aimeeting.client.MeetingResult
import java.util.concurrent.Executor

/** 不可变动作上下文，READY 和 access 均来自原场，工作/UI 队列由宿主提供；不重读当前身份。 */
data class ResultActionContext(
    private val result: MeetingResult,
    private val access: ResultActionAccess,
    private var serviceCapabilities: Set<String>,
    private val workExecutor: Executor,
    private val uiExecutor: Executor,
) {
    init { serviceCapabilities = java.util.Set.copyOf(serviceCapabilities) }
    fun result(): MeetingResult = result
    fun access(): ResultActionAccess = access
    fun serviceCapabilities(): Set<String> = serviceCapabilities
    fun workExecutor(): Executor = workExecutor
    fun uiExecutor(): Executor = uiExecutor
}
