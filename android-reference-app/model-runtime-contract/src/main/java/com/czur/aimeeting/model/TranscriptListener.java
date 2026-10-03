package com.czur.aimeeting.model;

/** 接收模型归一化后的实时字幕；回调可能发生在非 UI 线程。 */
public interface TranscriptListener {
    /**
     * 同一个 {@link TranscriptEvent#segmentId()} 的更高修订号替换此前文字；
     * {@link TranscriptEvent#isFinal()} 为 true 后，该片段成为最终文字。
     */
    void onTranscript(TranscriptEvent event);
}
