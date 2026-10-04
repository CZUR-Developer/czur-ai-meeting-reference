package com.czur.aimeeting.model;

/** 接收 Adapter 归一化后的译文修订；回调可能发生在非 UI 线程。 */
@FunctionalInterface
public interface TranslationListener {
    void onTranslation(TranslationEvent event);
}
