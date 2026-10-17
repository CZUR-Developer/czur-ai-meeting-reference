package com.czur.aimeeting.model

import java.io.IOException

/** SDK 的一次阻塞文本翻译调用；Coordinator 仅在 IO 队列使用，响应和连接由实现关闭。 */
fun interface TranslationRuntime {
    @Throws(IOException::class)
    fun translate(grant: TranslationSessionGrant, adapter: TranslationAdapter, input: TranslationInput): TranslationEvent
}
