package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

import com.czur.aimeeting.client.FinalTranscriptSegment;
import com.czur.aimeeting.client.FinalTranslation;
import com.czur.aimeeting.client.MeetingResultContent;
import com.czur.aimeeting.client.MeetingResult;
import com.czur.aimeeting.client.PresentationFrame;
import com.czur.aimeeting.model.SummaryResult;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class LocalPdfContentTest {
    private static MeetingResult result(MeetingResultContent content) {
        return new MeetingResult("mtg_pdf", "本场", 123L, MeetingResult.Status.READY,
                content.artifactTypes(), content);
    }
    @Test public void includesOnlyChaptersBackedByReadyContent() {
        MeetingResult result = result(new MeetingResultContent(
                Set.of("transcript.final"), "你好\n继续", List.of(
                        new FinalTranscriptSegment("s1", 0, 100, "zh-CN", "你好", "speaker-1"),
                        new FinalTranscriptSegment("s2", 100, 200, "zh-CN", "继续", null)),
                List.of(), null, List.of()));
        List<LocalPdfContent.Entry> entries = LocalPdfContent.from(result);
        assertEquals(List.of("字幕", "本场发言人"), entries.stream()
                .filter(item -> item.kind() == LocalPdfContent.Kind.HEADING)
                .map(LocalPdfContent.Entry::text).toList());
        assertTrue(entries.stream().anyMatch(item -> item.text().contains("speaker-1")));
        assertFalse(entries.stream().anyMatch(item -> item.text().equals("译文")));
    }

    @Test public void includesTranslationSummaryAndPresentationOnlyWhenPresent() {
        MeetingResult result = result(new MeetingResultContent(
                Set.of("translation.final", "summary.structured", "presentation.capture"), "",
                List.of(), List.of(new FinalTranslation("t1", "s1", "en-US", "zh-CN", "你好")),
                new SummaryResult("zh-CN", "纪要", "结论", List.of("要点"), List.of()),
                List.of(new PresentationFrame(10_000, new byte[]{1, 2, 3}))));
        List<LocalPdfContent.Entry> entries = LocalPdfContent.from(result);
        assertEquals(List.of("译文", "结构化纪要", "演示画面"), entries.stream()
                .filter(item -> item.kind() == LocalPdfContent.Kind.HEADING)
                .map(LocalPdfContent.Entry::text).toList());
        LocalPdfContent.Entry image = entries.stream()
                .filter(item -> item.kind() == LocalPdfContent.Kind.IMAGE).findFirst().orElseThrow();
        assertEquals("会议 00:10", image.text());
        assertArrayEquals(new byte[]{1, 2, 3}, image.jpeg());
    }

    @Test public void keepsWholeTranscriptFallbackWithoutInventingMissingSections() {
        MeetingResult result = result(new MeetingResultContent(
                Set.of("transcript.final"), "仅有全文的产物", List.of(), List.of(), null, List.of()));
        List<LocalPdfContent.Entry> entries = LocalPdfContent.from(result);
        assertEquals(List.of("字幕", "仅有全文的产物"), entries.stream()
                .map(LocalPdfContent.Entry::text).toList());
    }
}
