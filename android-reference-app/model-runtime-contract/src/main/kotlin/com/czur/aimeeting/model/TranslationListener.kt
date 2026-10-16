package com.czur.aimeeting.model

/** 接收 Adapter 归一化后的译文修订，可能在非 UI 线程调用。 */
fun interface TranslationListener {
    fun onTranslation(event: TranslationEvent)
}
