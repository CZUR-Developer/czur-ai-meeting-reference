package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 列表标题只来自服务端已发布的总结标题；缺失或异常时保持 null，不从正文猜名字。
 */
class MeetingListTitleParsingTest {
    @Test
    void readsExplicitTitleFromPublishedSummary() {
        assertEquals("职场趣事与读书群闲聊", JdbcMeetingQueryPort.summaryTitleFromJson(
                "{\"language\":\"zh-CN\",\"title\":\"职场趣事与读书群闲聊\",\"overview\":\"…\"}"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("习惯养成", JdbcMeetingQueryPort.summaryTitleFromJson("{\"title\":\"  习惯养成  \"}"));
    }

    @Test
    void missingOrBlankTitleStaysNull() {
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson(null));
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson("   "));
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson("{}"));
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson("{\"title\":\"   \"}"));
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson("{\"title\":null}"));
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson("{\"title\":123}"));
    }

    @Test
    void invalidJsonDoesNotBreakTheWholePage() {
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson("{oops"));
        assertNull(JdbcMeetingQueryPort.summaryTitleFromJson("[1,2,3]"));
    }
}
