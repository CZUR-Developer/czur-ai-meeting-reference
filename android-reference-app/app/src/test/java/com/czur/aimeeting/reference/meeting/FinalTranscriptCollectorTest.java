package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.FinalTranscriptCollector;

import static org.junit.Assert.assertEquals;

import com.czur.aimeeting.model.TranscriptEvent;
import org.junit.Test;

public final class FinalTranscriptCollectorTest {
    @Test public void summaryUsesOnlyLatestFinalSegmentsInTimeOrder() {
        FinalTranscriptCollector collector = new FinalTranscriptCollector();
        collector.apply(new TranscriptEvent("later", 1, 100, 200, "zh-CN", "后句", true));
        collector.apply(new TranscriptEvent("first", 1, 0, 100, "zh-CN", "初稿", false));
        collector.apply(new TranscriptEvent("first", 2, 0, 100, "zh-CN", "前句", true));
        collector.apply(new TranscriptEvent("first", 3, 0, 100, "zh-CN", "迟到的未定稿", false));
        collector.apply(new TranscriptEvent("first", 1, 0, 100, "zh-CN", "旧稿", false));
        assertEquals("前句\n后句", collector.input("zh-CN").transcript());
        collector.apply(new TranscriptEvent("first", 4, 0, 100, "zh-CN", "修订终稿", true));
        assertEquals("修订终稿\n后句", collector.input("zh-CN").transcript());
        collector.clear();
        try {
            collector.input("zh-CN");
            org.junit.Assert.fail("无最终字幕不得生成纪要");
        } catch (IllegalArgumentException expected) {
            // 不能把空会或 partial 伪装为已生成的标准纪要。
        }
    }
}
