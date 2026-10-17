package com.czur.aimeeting.model

import java.io.IOException

/**
 * 可选独立文本翻译协议。由伙伴注册工厂，每场创建实例；SDK 在工作线程按顺序调用，
 * Adapter 仅生成请求／解析响应，不自行联网、启停采音或改变会议状态。默认听悟一体译文
 * 不经过此接口。使用 input.result(...) 可保留原文绑定、稳定译文 ID 和修订。
 */
interface TranslationAdapter {
    fun id(): String
    fun request(grant: TranslationSessionGrant, input: TranslationInput): ModelHttpRequest
    @Throws(IOException::class)
    fun parseResponse(response: String, input: TranslationInput): TranslationEvent
}
