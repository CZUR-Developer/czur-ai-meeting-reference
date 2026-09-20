package com.czur.reference.application.persistence;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.MeetingEventPage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存事件日志的 meeting-global 分页契约。
 *
 * <p>该测试刻意交错两个 {@code streamId}，确保断线补拉使用会议
 * 全局序号，不会因为逻辑流切换丢失事件或跳过游标。</p>
 */
class InMemoryEventLogStoreTest {
    private static final String MEETING_ID = "meeting-global-page";
    private static final Instant EMITTED_AT = Instant.parse("2026-09-20T09:00:00Z");

    @Test
    void readsInterleavedStreamsByMeetingGlobalSequence() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        store.append(event(1, "stream-a", "event-a-1"));
        store.append(event(2, "stream-b", "event-b-2"));
        store.append(event(3, "stream-a", "event-a-3"));

        MeetingEventPage first = store.readPage(MEETING_ID, 0, 2);
        assertEquals(List.of(1L, 2L), first.items().stream()
                .map(EventLog::eventSequence).toList());
        assertEquals(List.of("stream-a", "stream-b"), first.items().stream()
                .map(EventLog::streamId).toList());
        assertEquals(2, first.lastSequence());
        assertTrue(first.hasMore());

        MeetingEventPage second = store.readPage(MEETING_ID, first.lastSequence(), 2);
        assertEquals(List.of(3L), second.items().stream()
                .map(EventLog::eventSequence).toList());
        assertEquals("stream-a", second.items().getFirst().streamId());
        assertEquals(3, second.lastSequence());
        assertFalse(second.hasMore());

        MeetingEventPage empty = store.readPage(MEETING_ID, second.lastSequence(), 2);
        assertTrue(empty.items().isEmpty());
        assertEquals(3, empty.lastSequence());
        assertFalse(empty.hasMore());
    }

    @Test
    void validatesMeetingGlobalPageInputsWithoutRequiringStreamId() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();

        assertThrows(IllegalArgumentException.class, () -> store.readPage(" ", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> store.readPage(MEETING_ID, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> store.readPage(MEETING_ID, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> store.readPage(MEETING_ID, 0, 1001));
    }

    private static EventLog event(long sequence, String streamId, String eventId) {
        return new EventLog(
                "0.1",
                MEETING_ID,
                streamId,
                eventId,
                sequence,
                EMITTED_AT.plusSeconds(sequence),
                "heartbeat",
                "{\"heartbeat\":true}");
    }
}
