package com.czur.aimeeting.model;

/** 一条标准字幕片段的某次修订，供 App 即时显示，并在 P4-2 提交后端。 */
public final class TranscriptEvent {
    private final String segmentId;
    private final int revision;
    private final long startMs;
    private final long endMs;
    private final String language;
    private final String text;
    private final boolean isFinal;
    private final String sessionSpeakerId;

    public TranscriptEvent(String segmentId, int revision, long startMs, long endMs,
            String language, String text, boolean isFinal) {
        this(segmentId, revision, startMs, endMs, language, text, isFinal, null);
    }

    public TranscriptEvent(String segmentId, int revision, long startMs, long endMs,
            String language, String text, boolean isFinal, String sessionSpeakerId) {
        this.segmentId = segmentId;
        this.revision = revision;
        this.startMs = startMs;
        this.endMs = endMs;
        this.language = language;
        this.text = text;
        this.isFinal = isFinal;
        this.sessionSpeakerId = sessionSpeakerId;
    }

    /** 同一段文字的稳定标识，修订期间不改变。 */
    public String segmentId() { return segmentId; }

    /** 从 1 开始递增的片段修订号。 */
    public int revision() { return revision; }

    /** 相对会议开始时间的片段起点，单位毫秒。 */
    public long startMs() { return startMs; }

    /** 相对会议开始时间的片段终点，单位毫秒。 */
    public long endMs() { return endMs; }

    /** 片段使用的语言代码。 */
    public String language() { return language; }

    /** 当前修订的完整文字。 */
    public String text() { return text; }

    /** 模型已确认该片段不会再修订。 */
    public boolean isFinal() { return isFinal; }

    /** 模型提供的本场临时发言人；缺失时为 null，不代表跨会议身份。 */
    public String sessionSpeakerId() { return sessionSpeakerId; }

    /** 本场未授权发言人能力时，只提交普通字幕。 */
    public TranscriptEvent withoutSpeaker() {
        return sessionSpeakerId == null ? this : new TranscriptEvent(segmentId, revision,
                startMs, endMs, language, text, isFinal, null);
    }
}
