package com.czur.aimeeting.client

import com.czur.aimeeting.model.TranscriptEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 抖动进程后续办用的字幕日志：只收 final、按片段保最高修订、坏行跳过。 */
class TranscriptJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun finalSegmentsBecomeOrderedSummaryInput() {
        val journal = TranscriptJournal(temporary.newFile("journal.jsonl"))
        journal.append(event("b", 1, 2000, "第二段"))
        journal.append(event("a", 1, 1000, "第一段"))
        journal.append(event("c", 1, 3000, "partial", final = false))
        val input = journal.summaryInput("zh")!!
        assertEquals("zh", input.language())
        assertEquals("第一段\n第二段", input.transcript())
    }

    @Test fun higherRevisionWins() {
        val journal = TranscriptJournal(temporary.newFile("journal.jsonl"))
        journal.append(event("a", 1, 1000, "旧文"))
        journal.append(event("a", 2, 1000, "新文"))
        assertEquals("新文", journal.summaryInput("zh")!!.transcript())
    }

    @Test fun truncatedLastLineIsIgnored() {
        val journalFile = temporary.newFile("journal.jsonl")
        val journal = TranscriptJournal(journalFile)
        journal.append(event("a", 1, 1000, "第一段"))
        journalFile.appendText("{\"segmentId\":\"b\",\"revis")
        assertEquals("第一段", journal.summaryInput("zh")!!.transcript())
    }

    @Test fun emptyJournalReadsNull() {
        assertNull(TranscriptJournal(temporary.newFile("journal.jsonl")).summaryInput("zh"))
    }

    @Test fun deleteRemovesFile() {
        val journalFile = temporary.newFile("journal.jsonl")
        val journal = TranscriptJournal(journalFile)
        journal.append(event("a", 1, 1000, "第一段"))
        journal.delete()
        assertTrue(!journalFile.exists())
    }

    private fun event(segmentId: String, revision: Int, startMs: Long, text: String, final: Boolean = true) =
        TranscriptEvent(segmentId, revision, startMs, startMs + 500, "zh", text, final)
}
