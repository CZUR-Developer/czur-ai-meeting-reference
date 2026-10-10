package com.czur.reference.application.persistence;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.MeetingEventPage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单进程事件日志参考适配器。
 *
 * <p>锁内完成 eventId 幂等判断、序号校验和追加，模拟数据库会议行锁的
 * 串行化边界。它只用于参考实现和纯单元测试，进程退出后数据不会恢复，不能
 * 作为生产事实源。日志只记录固定结果和序号，不输出事件正文。</p>
 */
public final class InMemoryEventLogStore implements EventLogStore {
    private static final Logger LOGGER = Logger.getLogger(InMemoryEventLogStore.class.getName());

    private final Object mutex = new Object();
    private final Map<String, EventLog> byEventId = new HashMap<>();
    private final Map<String, List<EventLog>> byMeeting = new HashMap<>();

    @Override
    public AppendResult append(EventLog event) {
        Objects.requireNonNull(event, "event");
        synchronized (mutex) {
            EventLog existing = byEventId.get(event.eventId());
            if (existing != null) {
                if (sameEnvelope(existing, event)) {
                    LOGGER.log(Level.FINE, "内存事件日志幂等重放 status=REPLAYED");
                    return new AppendResult(AppendStatus.REPLAYED, existing);
                }
                LOGGER.log(Level.WARNING, "内存事件日志事件标识冲突 status=CONFLICT");
                return new AppendResult(AppendStatus.CONFLICT, existing);
            }

            List<EventLog> events = byMeeting.computeIfAbsent(
                    event.meetingId(), ignored -> new ArrayList<>());
            long expectedSequence = events.size() + 1L;
            if (event.eventSequence() != expectedSequence) {
                throw new IllegalArgumentException("eventSequence must be the next meeting sequence");
            }
            events.add(event);
            byEventId.put(event.eventId(), event);
            LOGGER.log(Level.INFO, "内存事件日志已追加 status=APPENDED sequence={0}",
                    event.eventSequence());
            return new AppendResult(AppendStatus.APPENDED, event);
        }
    }

    @Override
    public MeetingEventPage readPage(String meetingId, long afterSequence, int limit) {
        EventLogStore.validatePage(meetingId, afterSequence, limit);
        synchronized (mutex) {
            List<EventLog> events = byMeeting.getOrDefault(meetingId, List.of());
            List<EventLog> page = events.stream()
                    .filter(event -> event.eventSequence() > afterSequence)
                    .limit(limit + 1L)
                    .toList();
            boolean hasMore = page.size() > limit;
            if (hasMore) {
                page = page.subList(0, limit);
            }
            if (page.isEmpty()) {
                return MeetingEventPage.empty(afterSequence);
            }
            return new MeetingEventPage(page, page.get(page.size() - 1).eventSequence(), hasMore);
        }
    }

    private static boolean sameEnvelope(EventLog left, EventLog right) {
        return left.specVersion().equals(right.specVersion())
                && left.meetingId().equals(right.meetingId())
                && left.streamId().equals(right.streamId())
                && left.eventId().equals(right.eventId())
                && left.eventSequence() == right.eventSequence()
                && left.emittedAt().equals(right.emittedAt())
                && left.eventType().equals(right.eventType())
                && left.canonicalPayloadJson().equals(right.canonicalPayloadJson());
    }
}
