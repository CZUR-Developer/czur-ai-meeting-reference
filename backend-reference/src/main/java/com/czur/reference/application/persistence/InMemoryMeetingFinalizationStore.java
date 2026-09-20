package com.czur.reference.application.persistence;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 最终收口端口的确定性内存参考实现，仅用于单元测试和示例演示。
 *
 * <p>会议是否存在以及生命周期来自注入的 {@link MeetingRepository}；本类只在
 * Map 中保存 meeting-level 输入责任事实。所有方法在同一实例锁内执行，使并发
 * Worker 只有一个调用 receipt 生成器。该原子性只适用于单进程测试，正式部署
 * 必须使用带会议行锁和数据库事务的 JDBC 实现。</p>
 */
public final class InMemoryMeetingFinalizationStore implements MeetingFinalizationStore {
    private final MeetingRepository meetings;
    private final FinalizationReceiptGenerator receiptGenerator;
    private final Map<String, FinalizationFact> facts = new HashMap<>();

    public InMemoryMeetingFinalizationStore(
            MeetingRepository meetings,
            FinalizationReceiptGenerator receiptGenerator) {
        this.meetings = Objects.requireNonNull(meetings, "meetings");
        this.receiptGenerator = Objects.requireNonNull(receiptGenerator, "receiptGenerator");
    }

    @Override
    public synchronized Result find(String meetingId) {
        MeetingFinalizationStore.requireMeetingId(meetingId);
        if (meetings.find(meetingId).isEmpty()) {
            return new NotFound();
        }
        return new Found(current(meetingId));
    }

    @Override
    public synchronized Result requestInputCompletion(String meetingId) {
        MeetingFinalizationStore.requireMeetingId(meetingId);
        var meeting = meetings.find(meetingId);
        if (meeting.isEmpty()) {
            return new NotFound();
        }
        FinalizationFact current = current(meetingId);
        if (current.inputState() == InputState.INPUT_ACCEPTED) {
            return new Replayed(current);
        }
        if (current.inputState() == InputState.INPUT_COMPLETE_PENDING) {
            return meeting.get().state() == MeetingState.STOPPING
                    ? new Replayed(current)
                    : new Conflict(ConflictReason.FACT_INCONSISTENT);
        }
        if (current.inputState() == InputState.INPUT_CANCELLED) {
            return new Conflict(ConflictReason.INVALID_INPUT_STATE);
        }
        if (meeting.get().state() != MeetingState.STOPPING) {
            return new Conflict(ConflictReason.INVALID_MEETING_STATE);
        }
        FinalizationFact pending = FinalizationFact.pending(
                InputState.INPUT_COMPLETE_PENDING);
        facts.put(meetingId, pending);
        return new Applied(pending);
    }

    @Override
    public synchronized Result acceptInput(String meetingId, Instant acceptedAt) {
        MeetingFinalizationStore.requireMeetingId(meetingId);
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        var meeting = meetings.find(meetingId);
        if (meeting.isEmpty()) {
            return new NotFound();
        }
        FinalizationFact current = current(meetingId);
        if (current.inputState() == InputState.INPUT_ACCEPTED) {
            return new Replayed(current);
        }
        if (current.inputState() != InputState.INPUT_COMPLETE_PENDING) {
            return new Conflict(ConflictReason.INVALID_INPUT_STATE);
        }
        MeetingAggregate.MeetingSnapshot snapshot = meeting.get();
        if (snapshot.state() != MeetingState.STOPPING) {
            return new Conflict(ConflictReason.INVALID_MEETING_STATE);
        }
        Instant canonicalTime = acceptedAt.truncatedTo(ChronoUnit.MICROS);
        if (canonicalTime.isBefore(snapshot.updatedAt())) {
            return new Conflict(ConflictReason.FACT_TIME_BEFORE_CURRENT);
        }

        String receipt;
        try {
            receipt = requireReceipt(receiptGenerator.newReceipt());
        } catch (RuntimeException generationFailure) {
            throw new IllegalStateException("finalization receipt generation failed");
        }
        // JDBC 实现依靠 V005 唯一索引在候选值碰撞时 fail closed。内存实现也必须
        // 保持同一端口契约，不能让两个会议发布相同 receipt；检查必须早于会议
        // 生命周期更新，避免碰撞失败后留下 FINALIZING + pending 的半事实。
        if (facts.values().stream()
                .anyMatch(fact -> receipt.equals(fact.finalizationReceipt()))) {
            throw new IllegalStateException("finalization receipt collision");
        }
        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(snapshot);
        aggregate.apply(MeetingEvent.INPUT_ACCEPTED, canonicalTime);
        // 先保存会议生命周期，再公布 receipt。若 Repository 拒绝保存，Map 不会
        // 留下“已接管但会议仍是 STOPPING”的半事实。
        meetings.save(aggregate.snapshot());
        FinalizationFact accepted = FinalizationFact.accepted(receipt);
        facts.put(meetingId, accepted);
        return new Applied(accepted);
    }

    private FinalizationFact current(String meetingId) {
        return facts.getOrDefault(
                meetingId, FinalizationFact.pending(InputState.INPUT_OPEN));
    }

    private static String requireReceipt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("generated receipt must not be blank");
        }
        return value;
    }
}
