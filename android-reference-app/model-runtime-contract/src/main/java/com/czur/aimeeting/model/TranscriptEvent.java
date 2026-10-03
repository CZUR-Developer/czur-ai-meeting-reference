package com.czur.aimeeting.model;

/** 一条标准字幕片段的某次修订，供 App 即时显示，并在 P4-2 提交后端。 */
public final class TranscriptEvent {
    private final String segmentId;
    private final int revision;
    private final String text;
    private final boolean isFinal;

    public TranscriptEvent(String segmentId, int revision, String text, boolean isFinal) {
        this.segmentId = segmentId;
        this.revision = revision;
        this.text = text;
        this.isFinal = isFinal;
    }

    /** 同一段文字的稳定标识，修订期间不改变。 */
    public String segmentId() { return segmentId; }

    /** 从 1 开始递增的片段修订号。 */
    public int revision() { return revision; }

    /** 当前修订的完整文字。 */
    public String text() { return text; }

    /** 模型已确认该片段不会再修订。 */
    public boolean isFinal() { return isFinal; }
}
