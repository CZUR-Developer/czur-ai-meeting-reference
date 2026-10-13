package com.czur.reference.application.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 标准纪要随最终快照交付；仅验证值对象，无网络、数据库或模型替身平台。 */
class ClientFinalSnapshotSummaryTest {
    @Test
    void completedSummaryRemainsIndependentOfLegacyFormatConversion() {
        var summary = new ObjectMapper().createObjectNode();
        summary.put("language", "zh-CN").put("title", "本场纪要").put("overview", "正常完成");
        summary.putArray("keyPoints").add("只使用本场最终文字");
        summary.putArray("actionItems").add("查看结果");
        var snapshot = new JdbcClientMeetingResultFinalizer.Ready(List.of(), List.of(),
                "zh-CN", 1200, true, "zh-CN", "开场标题", summary);
        summary.put("title", "外部修改");
        assertEquals("本场纪要", snapshot.summary().path("title").asText());
        snapshot.summary().put("title", "旧格式转换修改");
        assertEquals("本场纪要", snapshot.summary().path("title").asText());
        assertEquals("开场标题", snapshot.title());
    }

    @Test
    void transcriptOnlyCallKeepsNoSummaryFact() {
        var snapshot = new JdbcClientMeetingResultFinalizer.Ready(List.of(), List.of(),
                "und", 0, true, "zh-CN", "标题");
        assertNull(snapshot.summary());
    }
}
