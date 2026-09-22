package com.czur.reference.application.persistence;

import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalUsageFactStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link FinalUsageFactStore} 的单进程参考实现。
 *
 * <p>互斥区模拟数据库唯一键或行锁：首次写入成为赢家，相同完整事实重放，
 * 不同事实冲突并保留原赢家。进程退出后数据会丢失，所以本类只用于纯 Java
 * 契约测试和编排开发，不能替代计划中的 JDBC durable store。</p>
 *
 * <p>候选事实必须在进入本类前通过
 * {@link FinalUsageFact#fromInput(com.czur.reference.application.port.FinalizationInputFact)}
 * 生成；本类不会重新计算用量、读取系统时钟或修改来源事实。</p>
 */
public final class InMemoryFinalUsageFactStore implements FinalUsageFactStore {
    private static final Logger LOGGER =
            Logger.getLogger(InMemoryFinalUsageFactStore.class.getName());

    private final Object mutex = new Object();
    private final Map<String, FinalUsageFact> factsByMeeting = new HashMap<>();

    @Override
    public Optional<FinalUsageFact> find(String meetingId) {
        requireMeetingId(meetingId);
        synchronized (mutex) {
            return Optional.ofNullable(factsByMeeting.get(meetingId));
        }
    }

    @Override
    public PersistResult persist(FinalUsageFact fact) {
        Objects.requireNonNull(fact, "fact");
        synchronized (mutex) {
            FinalUsageFact existing = factsByMeeting.get(fact.meetingId());
            if (existing == null) {
                factsByMeeting.put(fact.meetingId(), fact);
                LOGGER.log(Level.FINE, "内存最终用量事实已保存 status=APPLIED");
                return new PersistResult(PersistStatus.APPLIED, fact);
            }
            if (existing.equals(fact)) {
                LOGGER.log(Level.FINE, "内存最终用量事实幂等重放 status=REPLAYED");
                return new PersistResult(PersistStatus.REPLAYED, existing);
            }
            LOGGER.log(Level.WARNING, "内存最终用量事实冲突 status=CONFLICT");
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
