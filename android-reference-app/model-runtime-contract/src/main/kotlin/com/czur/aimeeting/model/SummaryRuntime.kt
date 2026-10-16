package com.czur.aimeeting.model

import java.io.IOException

/** SDK 执行单次短期授权的文本总结，原始响应仅交 Adapter。 */
fun interface SummaryRuntime {
    @Throws(IOException::class)
    fun summarize(grant: SummarySessionGrant, adapter: SummaryAdapter, input: SummaryInput): SummaryResult
}
