package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public final class LocalPdfContentTest {
    @Test public void includesOnlyChaptersBackedByReadyContent() throws Exception {
        LocalMeetingResult result = new LocalMeetingResult("本场", new JSONObject("{"
                + "\"text\":\"你好\",\"segments\":["
                + "{\"text\":\"你好\",\"sessionSpeakerId\":\"speaker-1\"},"
                + "{\"text\":\"继续\"}]}"), null, null, List.of());
        List<LocalPdfContent.Entry> entries = LocalPdfContent.from(result);
        assertEquals(List.of("字幕", "本场发言人"), entries.stream()
                .filter(item -> item.kind() == LocalPdfContent.Kind.HEADING)
                .map(LocalPdfContent.Entry::text).toList());
        assertTrue(entries.stream().anyMatch(item -> item.text().contains("speaker-1")));
        assertFalse(entries.stream().anyMatch(item -> item.text().equals("译文")));
    }

    @Test public void includesTranslationSummaryAndPresentationOnlyWhenPresent()
            throws Exception {
        LocalMeetingResult result = new LocalMeetingResult("本场", null,
                new JSONObject("{\"translations\":[{\"sourceLanguage\":\"en-US\","
                        + "\"targetLanguage\":\"zh-CN\",\"text\":\"你好\"}]}"),
                new JSONObject("{\"title\":\"纪要\",\"overview\":\"结论\","
                        + "\"keyPoints\":[\"要点\"],\"actionItems\":[]}"),
                List.of(new JSONObject("{\"frames\":[{\"offsetMs\":10000,"
                        + "\"jpegBase64\":\"abc\"}]}")));
        List<LocalPdfContent.Entry> entries = LocalPdfContent.from(result);
        assertEquals(List.of("译文", "结构化纪要", "演示画面"), entries.stream()
                .filter(item -> item.kind() == LocalPdfContent.Kind.HEADING)
                .map(LocalPdfContent.Entry::text).toList());
        assertEquals("会议 00:10", entries.stream()
                .filter(item -> item.kind() == LocalPdfContent.Kind.IMAGE)
                .findFirst().orElseThrow().text());
    }
}
