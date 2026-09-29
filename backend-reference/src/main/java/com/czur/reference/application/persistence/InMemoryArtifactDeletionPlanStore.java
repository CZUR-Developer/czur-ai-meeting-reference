package com.czur.reference.application.persistence;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.MeetingDeletionPlan;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 纯 Java 契约测试使用的单进程删除计划 Store；不模拟数据库审计迁移。 */
public final class InMemoryArtifactDeletionPlanStore implements ArtifactDeletionPlanStore {
    private static final Logger LOGGER = Logger.getLogger(
            InMemoryArtifactDeletionPlanStore.class.getName());
    private final Object mutex = new Object();
    private final Map<String, MeetingDeletionPlan> plans = new HashMap<>();

    @Override
    public Optional<MeetingDeletionPlan> find(String meetingId) {
        requireMeetingId(meetingId);
        synchronized (mutex) {
            return Optional.ofNullable(plans.get(meetingId));
        }
    }

    @Override
    public PersistResult register(ArtifactDeletionPlan candidate) {
        Objects.requireNonNull(candidate, "candidate");
        MeetingDeletionPlan proposed = MeetingDeletionPlan.pending(candidate);
        synchronized (mutex) {
            MeetingDeletionPlan existing = plans.get(candidate.meetingId());
            if (existing == null) {
                plans.put(candidate.meetingId(), proposed);
                LOGGER.log(Level.FINE, "内存删除计划注册 status=APPLIED");
                return new PersistResult(PersistStatus.APPLIED, proposed);
            }
            PersistStatus status = existing.plan().equals(candidate)
                    ? PersistStatus.REPLAYED : PersistStatus.CONFLICT;
            LOGGER.log(status == PersistStatus.CONFLICT ? Level.WARNING : Level.FINE,
                    "内存删除计划注册 status={0}", status);
            return new PersistResult(status, existing);
        }
    }

    private static void requireMeetingId(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId must not be blank");
        }
    }
}
