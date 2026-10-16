package com.czur.aimeeting.model

import java.io.IOException

/**
 * 已创建会议的模型续接端口，组合层捕获本场身份／Server／meetingId。
 * 只获取新材料和新 Adapter，不重复 Create／StartClient 或重读账号。调用可阻塞 HTTP，
 * 必须在续接工作线程，不可在主线程、采音回调、状态锁或音源控制队列执行。
 * Stop／close 取消待执行重试并丢弃迟到结果，不能等待此方法才释放音源。
 */
fun interface ModelSessionReconnector {
    /** 成功结果属于新连接代次；失败抛 IOException，后续重试由仍运行的 Runtime 决定。 */
    @Throws(IOException::class)
    fun request(): ModelSessionConnection
}
