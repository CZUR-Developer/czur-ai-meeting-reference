package com.czur.aimeeting.client.server

/**
 * 服务端纪要请求的受理事实。accepted 只表示语言列表已登记，正文由服务端异步生成；
 * summaryStatus 为服务端回告的生成进度，旧服务端缺字段时为 null。
 */
data class SummaryRequestAck(private val meetingId: String, private val accepted: Boolean,
    private val summaryStatus: String?) {
    fun meetingId(): String = meetingId
    fun accepted(): Boolean = accepted
    fun summaryStatus(): String? = summaryStatus
}
