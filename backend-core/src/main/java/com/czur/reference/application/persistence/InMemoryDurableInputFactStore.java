package com.czur.reference.application.persistence;

import com.czur.reference.application.port.DurableInputFactStore;
import com.czur.reference.application.port.FinalizationInputFact;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * durable input fact 端口的单进程参考实现。
 *
 * <p>互斥区模拟持久化实现按 {@code meetingId} 串行裁决唯一事实：首次保存成功，
 * 相同完整事实重放，不同事实冲突且不覆盖赢家。该类只用于纯 Java 契约测试和
 * 编排开发，进程退出后数据会丢失，不能替代数据库或作为跨进程 durable store。</p>
 *
 * <p>日志只写固定状态，不写会议标识、input binding、语言、摘要或 receipt；返回
 * 对象本身也通过 {@link DurableInputFactStore.PersistResult#toString()} 脱敏。</p>
 */
public final class InMemoryDurableInputFactStore implements DurableInputFactStore {
    private static final Logger LOGGER =
            Logger.getLogger(InMemoryDurableInputFactStore.class.getName());

    private final Object mutex = new Object();
    private final Map<String, FinalizationInputFact> factsByMeeting = new HashMap<>();

    @Override
    public Optional<FinalizationInputFact> find(String meetingId) {
        requireMeetingId(meetingId);
        synchronized (mutex) {
            return Optional.ofNullable(factsByMeeting.get(meetingId));
        }
    }

    @Override
    public PersistResult persist(FinalizationInputFact fact) {
        Objects.requireNonNull(fact, "fact");
        synchronized (mutex) {
            FinalizationInputFact existing = factsByMeeting.get(fact.meetingId());
            if (existing == null) {
                factsByMeeting.put(fact.meetingId(), fact);
                LOGGER.log(Level.FINE, "内存 durable input fact 已保存 status=APPLIED");
                return new PersistResult(PersistStatus.APPLIED, fact);
            }
            if (existing.equals(fact)) {
                LOGGER.log(Level.FINE, "内存 durable input fact 幂等重放 status=REPLAYED");
                return new PersistResult(PersistStatus.REPLAYED, existing);
            }
            LOGGER.log(Level.FINE, "内存 durable input fact 冲突 status=CONFLICT");
            return new PersistResult(PersistStatus.CONFLICT, existing);
        }
    }

    private static String requireMeetingId(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId must not be blank");
        }
        return meetingId;
    }
}
