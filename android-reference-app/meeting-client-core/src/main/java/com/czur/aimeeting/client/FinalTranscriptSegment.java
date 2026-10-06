package com.czur.aimeeting.client;

/** 最终产物中的字幕段；产物没有实时 revision/final，不伪造这些字段。 */
public record FinalTranscriptSegment(String segmentId, long startMs, long endMs,
        String language, String text, String sessionSpeakerId) { }
