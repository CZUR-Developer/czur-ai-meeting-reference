package com.czur.aimeeting.client.result

import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingResultContent

/**
 * 本场真实 READY 结果的窄访问，SDK 冻结身份和 genTime，不暴露底层 client。
 * 异步使用 IO／串行通知队列，关闭后不再派发待执行通知，不重开音源／Stop 或自动重试。
 */
interface ResultActionAccess {
    /** 仅有需要的动作按需读取正文，Web／纯提示不应调用；保留部署端口的空正文返回。 */
    fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>)
    /** 本场有效链接邮件；失败保持 READY，手动重发的重复语义归部署服务。 */
    fun sendEmail(recipients: List<String>, resultUrl: String, callback: MeetingClient.Callback<Void?>)
}
