package com.czur.reference.application.realtime;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.RealtimeStartFactsLookup;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 用可控端口确定性命中首次 Start 冲突后的 V011 赢家重放分支。 */
class RealtimeMeetingStartCoordinatorTest {
    private static final String MEETING = "meeting-realtime-start";
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant CANDIDATE_START = CREATED.plusSeconds(11);
    private static final Instant WINNER_START = CREATED.plusSeconds(12);

    @Test
    void firstConflictReadsWinnerAndReplaysFrozenStartInsteadOfCandidateTime() {
        ProductProfile profile = new ProductProfile(
                "profile", 1, Set.of("asr"), Set.of(), Map.of(), Map.of(), Map.of());
        var model = new CapabilityDescriptor(Set.of("asr"));
        var plan = new CapabilityPlanner().plan(model, profile, Set.of("asr"),
                Set.of("asr"), DeploymentLimits.NONE, 1L);
        FinalizationExecutionFact winner = FinalizationExecutionFact.freeze(
                MEETING, WINNER_START, "赢家标题", profile, plan);
        var reads = new AtomicInteger();
        RealtimeStartFactsLookup facts = (deployment, meetingId) -> {
            assertEquals("deployment", deployment);
            assertEquals(MEETING, meetingId);
            return reads.getAndIncrement() == 0
                    ? new RealtimeStartFactsLookup.First(
                            "subject", "首次标题", "profile", 1, Set.of("asr"))
                    : new RealtimeStartFactsLookup.Started("subject", winner);
        };
        MeetingAggregate meeting = MeetingAggregate.owned(
                MEETING, "deployment", "session", "subject", "grant",
                CREATED.plusSeconds(3_600), "reservation", "profile", 1, CREATED);
        meeting.apply(MeetingEvent.AUTHORIZE, WINNER_START);
        meeting.apply(MeetingEvent.START, WINNER_START);
        var writes = new AtomicInteger();
        var firstRequest = new AtomicReference<MeetingStartExecutionUnitOfWork.StartRequest>();
        var replayRequest = new AtomicReference<MeetingStartExecutionUnitOfWork.StartRequest>();
        MeetingStartExecutionUnitOfWork startUnit = new MeetingStartExecutionUnitOfWork() {
            @Override public Result start(StartRequest request) {
                throw new AssertionError("legacy Start must not be called");
            }

            @Override public Result startCompleteCreate(StartRequest request) {
                if (writes.getAndIncrement() == 0) {
                    firstRequest.set(request);
                    return new Failure(ErrorCode.EXECUTION_FACT_CONFLICT);
                }
                replayRequest.set(request);
                return new Success(SuccessCode.REPLAYED, meeting.snapshot(), winner);
            }
        };
        var coordinator = new RealtimeMeetingStartCoordinator(
                "deployment", facts, (id, revision) -> java.util.Optional.of(profile),
                model, DeploymentLimits.NONE,
                Clock.fixed(CANDIDATE_START, ZoneOffset.UTC), startUnit);

        var result = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                coordinator.start(MEETING));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED, result.code());
        assertEquals(2, reads.get());
        assertEquals(2, writes.get());
        assertEquals(CANDIDATE_START, firstRequest.get().startedAt());
        assertEquals(WINNER_START, replayRequest.get().startedAt());
        assertEquals("赢家标题", replayRequest.get().title());
        assertEquals(winner.productProfile(),
                com.czur.reference.application.finalization.ProductProfileSnapshot.from(
                        replayRequest.get().productProfile()));
    }
}
