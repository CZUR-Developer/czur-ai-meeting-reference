package com.czur.aimeeting.client

import com.czur.aimeeting.model.TranscriptEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 沿用原用例，只有最新 final 进入按媒体时间排序的纪要输入。 */
class FinalTranscriptCollectorTest {
    @Test fun summaryUsesOnlyLatestFinalSegmentsInTimeOrder() {
        val collector = FinalTranscriptCollector()
        collector.apply(TranscriptEvent("later", 1, 100, 200, "zh-CN", "后句", true))
        collector.apply(TranscriptEvent("first", 1, 0, 100, "zh-CN", "初稿", false))
        collector.apply(TranscriptEvent("first", 2, 0, 100, "zh-CN", "前句", true))
        collector.apply(TranscriptEvent("first", 3, 0, 100, "zh-CN", "迟到的未定稿", false))
        collector.apply(TranscriptEvent("first", 1, 0, 100, "zh-CN", "旧稿", false))
        assertEquals("前句\n后句", collector.input("zh-CN").transcript())
        collector.apply(TranscriptEvent("first", 4, 0, 100, "zh-CN", "修订终稿", true))
        assertEquals("修订终稿\n后句", collector.input("zh-CN").transcript())
        collector.clear()
        assertThrows(IllegalArgumentException::class.java) { collector.input("zh-CN") }
    }
}
