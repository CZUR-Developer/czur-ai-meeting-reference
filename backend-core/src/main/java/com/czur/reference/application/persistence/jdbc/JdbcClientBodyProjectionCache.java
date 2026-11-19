package com.czur.reference.application.persistence.jdbc;

import java.util.LinkedHashMap;

/**
 * 最终正文预算的进程内加速器，不替代事件表。只复用与数据库客户端序号完全一致的不可变状态。
 * 调用方持有 meeting 行锁，只有事务 commit 成功后才发布新状态；其它实例写入、重启或
 * 淘汰均回退到原事件表重建。最终结果发布仍直接读取数据库。
 */
final class JdbcClientBodyProjectionCache {
    private final int maxEntries;
    private final long maxWeight;
    private long weight;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    JdbcClientBodyProjectionCache() { this(16, 64L * 1024 * 1024); }

    JdbcClientBodyProjectionCache(int maxEntries, long maxWeight) {
        if (maxEntries <= 0 || maxWeight <= 0) throw new IllegalArgumentException("cache bounds must be positive");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
    }

    synchronized JdbcClientMeetingResultFinalizer.ClientEvents find(String meetingId, long sequence) {
        Entry entry = entries.get(meetingId);
        return entry != null && entry.sequence == sequence ? entry.events : null;
    }

    void committed(String meetingId, long sequence, JdbcClientMeetingResultFinalizer.ClientEvents events) {
        long nextWeight = events.estimatedWeight();
        synchronized (this) {
            Entry previous = entries.get(meetingId);
            // commit 释放数据库行锁后，另一线程可能已提交更晚的序号，不能倒退缓存。
            if (previous != null && previous.sequence > sequence) return;
            if (previous != null) {
                entries.remove(meetingId);
                weight -= previous.weight;
            }
            if (nextWeight > maxWeight) return;
            entries.put(meetingId, new Entry(sequence, events, nextWeight));
            weight += nextWeight;
            while (entries.size() > maxEntries || weight > maxWeight) {
                var oldest = entries.entrySet().iterator();
                weight -= oldest.next().getValue().weight;
                oldest.remove();
            }
        }
    }

    private record Entry(long sequence, JdbcClientMeetingResultFinalizer.ClientEvents events, long weight) { }
}
