package com.czur.reference.domain.meeting;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain aggregate for one meeting.
 *
 * <p>The aggregate owns identity, the accepted product profile snapshot and
 * lifecycle transitions. Persistence, authentication and provider calls stay
 * outside this class.</p>
 */
public final class MeetingAggregate {
    private final String meetingId;
    private final String clientSessionId;
    private final String profileId;
    private final int profileRevision;
    private final Instant createdAt;
    private final MeetingStateMachine stateMachine;
    private Instant updatedAt;

    public MeetingAggregate(
            String meetingId,
            String clientSessionId,
            String profileId,
            int profileRevision,
            Instant createdAt) {
        this.meetingId = requiredId("meetingId", meetingId);
        this.clientSessionId = requiredId("clientSessionId", clientSessionId);
        this.profileId = requiredId("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        this.profileRevision = profileRevision;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
        this.stateMachine = new MeetingStateMachine();
    }

    public String meetingId() {
        return meetingId;
    }

    public String clientSessionId() {
        return clientSessionId;
    }

    public String profileId() {
        return profileId;
    }

    public int profileRevision() {
        return profileRevision;
    }

    public MeetingState state() {
        return stateMachine.state();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Applies a lifecycle event and records the event time.
     *
     * <p>Equal timestamps are accepted for idempotent retries. Older event
     * times are rejected so the aggregate never moves its update clock
     * backwards.</p>
     */
    public MeetingState apply(MeetingEvent event, Instant eventTime) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(eventTime, "eventTime");
        if (eventTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("eventTime cannot be before updatedAt");
        }
        MeetingState nextState = stateMachine.apply(event);
        updatedAt = eventTime;
        return nextState;
    }

    public MeetingSnapshot snapshot() {
        return new MeetingSnapshot(
                meetingId,
                clientSessionId,
                profileId,
                profileRevision,
                state(),
                createdAt,
                updatedAt);
    }

    private static String requiredId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Immutable view suitable for persistence or transport mapping. */
    public record MeetingSnapshot(
            String meetingId,
            String clientSessionId,
            String profileId,
            int profileRevision,
            MeetingState state,
            Instant createdAt,
            Instant updatedAt) {
        public MeetingSnapshot {
            Objects.requireNonNull(meetingId, "meetingId");
            Objects.requireNonNull(clientSessionId, "clientSessionId");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (profileRevision <= 0) {
                throw new IllegalArgumentException("profileRevision must be positive");
            }
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt cannot be before createdAt");
            }
        }
    }
}
