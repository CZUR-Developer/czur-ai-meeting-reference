package com.czur.reference.application.port;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会议事件的稳定分页结果。
 *
 * <p>分页游标由调用方通过 {@code afterSequence} 提供，服务端只返回大于该
 * 游标的事件；本记录保存返回页最后一个序号和是否还有后续页。事件必须按
 * 序号严格递增，避免 App 在断线补拉时把乱序事件误当作连续事实。</p>
 */
public record MeetingEventPage(
        List<EventLog> items,
        long lastSequence,
        boolean hasMore) {

    public MeetingEventPage {
        Objects.requireNonNull(items, "items");
        List<EventLog> copy = new ArrayList<>(items.size());
        long previous = 0;
        for (EventLog item : items) {
            Objects.requireNonNull(item, "items item");
            if (!copy.isEmpty() && item.eventSequence() <= previous) {
                throw new IllegalArgumentException(
                        "event page items must be strictly ordered by eventSequence");
            }
            previous = item.eventSequence();
            copy.add(item);
        }
        if (lastSequence < 0) {
            throw new IllegalArgumentException("lastSequence must not be negative");
        }
        if (!copy.isEmpty() && lastSequence != previous) {
            throw new IllegalArgumentException(
                    "lastSequence must equal the final event sequence");
        }
        items = Collections.unmodifiableList(copy);
    }

    /** 空页仍然可以携带已知游标，用于明确表示没有新的事件。 */
    public static MeetingEventPage empty(long lastSequence) {
        return new MeetingEventPage(List.of(), lastSequence, false);
    }

    /** 隐藏每条事件的正文，仅输出页大小和游标。 */
    @Override
    public String toString() {
        return "MeetingEventPage[itemCount=" + items.size()
                + ", lastSequence=" + lastSequence
                + ", hasMore=" + hasMore + ']';
    }
}
