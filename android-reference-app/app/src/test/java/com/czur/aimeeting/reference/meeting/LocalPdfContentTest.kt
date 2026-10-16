package com.czur.aimeeting.reference.meeting

import org.junit.Assert.*
import org.junit.Test
import com.czur.aimeeting.client.*
import com.czur.aimeeting.model.SummaryResult

class LocalPdfContentTest {
    private fun result(content: MeetingResultContent) = MeetingResult("mtg_pdf", "本场", 123L, MeetingResult.Status.READY, content.artifactTypes(), content)
    @Test fun includesOnlyChaptersBackedByReadyContent() {
        val result = result(MeetingResultContent(setOf("transcript.final"), "你好\n继续",
            listOf(FinalTranscriptSegment("s1", 0, 100, "zh-CN", "你好", "speaker-1"),
                FinalTranscriptSegment("s2", 100, 200, "zh-CN", "继续", null)), emptyList(), null, emptyList()))
        val entries = LocalPdfContent.from(result)
        assertEquals(listOf("字幕", "本场发言人"), entries.filter { it.kind() == LocalPdfContent.Kind.HEADING }.map { it.text() })
        assertTrue(entries.any { it.text().contains("speaker-1") }); assertFalse(entries.any { it.text() == "译文" })
    }
    @Test fun includesTranslationSummaryAndPresentationOnlyWhenPresent() {
        val result = result(MeetingResultContent(setOf("translation.final", "summary.structured", "presentation.capture"), "", emptyList(),
            listOf(FinalTranslation("t1", "s1", "en-US", "zh-CN", "你好")),
            SummaryResult("zh-CN", "纪要", "结论", listOf("要点"), emptyList()), listOf(PresentationFrame(10_000, byteArrayOf(1, 2, 3)))))
        val entries = LocalPdfContent.from(result)
        assertEquals(listOf("译文", "结构化纪要", "演示画面"), entries.filter { it.kind() == LocalPdfContent.Kind.HEADING }.map { it.text() })
        val image = entries.first { it.kind() == LocalPdfContent.Kind.IMAGE }
        assertEquals("会议 00:10", image.text()); assertArrayEquals(byteArrayOf(1, 2, 3), image.jpeg())
    }
    @Test fun keepsWholeTranscriptFallbackWithoutInventingMissingSections() {
        val result = result(MeetingResultContent(setOf("transcript.final"), "仅有全文的产物", emptyList(), emptyList(), null, emptyList()))
        assertEquals(listOf("字幕", "仅有全文的产物"), LocalPdfContent.from(result).map { it.text() })
    }
}
