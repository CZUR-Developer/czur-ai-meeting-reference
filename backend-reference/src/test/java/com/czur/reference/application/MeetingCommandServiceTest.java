package com.czur.reference.application;

import com.czur.reference.application.command.CreateMeetingCommand;
import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.command.MeetingCommandService;
import com.czur.reference.application.command.StopMeetingCommand;
import com.czur.reference.application.error.IdempotencyConflictException;
import com.czur.reference.application.error.MeetingAlreadyExistsException;
import com.czur.reference.application.error.MeetingNotFoundException;
import com.czur.reference.application.persistence.InMemoryIdempotencyStore;
import com.czur.reference.application.persistence.InMemoryMeetingRepository;
import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeetingCommandServiceTest {
    private static final Instant CREATED = Instant.parse("2026-09-15T01:00:00Z");
    private static final Instant STOPPED = Instant.parse("2026-09-15T01:00:02Z");
    private MeetingCommandService service;
    private InMemoryMeetingRepository meetings;

    @BeforeEach
    void setUp() {
        meetings = new InMemoryMeetingRepository();
        service = new MeetingCommandService(meetings, new InMemoryIdempotencyStore());
    }

    @Test
    void createsAndPersistsMeeting() {
        MeetingCommandResult result = service.create(create("create-1"));
        assertEquals(MeetingCommandResult.Operation.CREATED, result.operation());
        assertEquals(MeetingState.CREATED, result.meeting().state());
        assertEquals("default", result.meeting().deploymentScope());
        assertEquals(result.meeting(), meetings.find("m-1").orElseThrow());
    }

    @Test
    void rejectsDuplicateClientSessionWithinSameDeploymentScope() {
        service.create(create("create-1", "scope-a", "m-1"));

        CreateMeetingCommand duplicateSession = new CreateMeetingCommand(
                "m-2", "scope-a", "session-1", "profile-1", 1, CREATED, "create-2");

        assertThrows(MeetingAlreadyExistsException.class,
                () -> service.create(duplicateSession));
    }

    @Test
    void allowsSameClientSessionInDifferentDeploymentScopes() {
        MeetingCommandResult first = service.create(create("create-a", "scope-a", "m-a"));
        MeetingCommandResult second = service.create(create("create-b", "scope-b", "m-b"));

        assertEquals("scope-a", first.meeting().deploymentScope());
        assertEquals("scope-b", second.meeting().deploymentScope());
        assertEquals("session-1", first.meeting().clientSessionId());
        assertEquals("session-1", second.meeting().clientSessionId());
    }

    @Test
    void deploymentScopeIsPartOfCreateRequestDigest() {
        service.create(create("same-key", "scope-a", "m-a"));
        CreateMeetingCommand changedScope = new CreateMeetingCommand(
                "m-b", "scope-b", "session-1", "profile-1", 1, CREATED, "same-key");

        assertThrows(IdempotencyConflictException.class,
                () -> service.create(changedScope));
    }

    @Test
    void deploymentScopeMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingCommand(
                        "m-1", " ", "session-1", "profile-1", 1, CREATED, "create-1"));
    }

    @Test
    void repeatedCreateReturnsTheOriginalResult() {
        MeetingCommandResult first = service.create(create("create-1"));
        MeetingCommandResult retry = service.create(create("create-1"));
        assertEquals(first, retry);
    }

    @Test
    void concurrentCreateWithSameKeyReturnsOneStableResult() throws Exception {
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MeetingCommandResult>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.create(create("concurrent-create"));
                }));
            }
            start.countDown();
            MeetingCommandResult first = futures.get(0).get();
            for (Future<MeetingCommandResult> future : futures) {
                assertEquals(first, future.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reusingKeyForDifferentRequestIsRejected() {
        service.create(create("create-1"));
        CreateMeetingCommand changed = new CreateMeetingCommand(
                "m-2", "session-1", "profile-1", 1, CREATED, "create-1");
        assertThrows(IdempotencyConflictException.class, () -> service.create(changed));
    }

    @Test
    void duplicateMeetingWithoutIdempotencyRecordIsRejected() {
        service.create(create("create-1"));
        assertThrows(MeetingAlreadyExistsException.class, () -> service.create(create("create-2")));
    }

    @Test
    void stopIsIdempotentAndPersistsStoppingState() {
        service.create(create("create-1"));
        MeetingAggregate running = MeetingAggregate.fromSnapshot(meetings.find("m-1").orElseThrow());
        running.apply(MeetingEvent.AUTHORIZE, CREATED);
        running.apply(MeetingEvent.START, CREATED);
        running.apply(MeetingEvent.START, CREATED);
        meetings.save(running.snapshot());
        MeetingCommandResult first = service.stop(new StopMeetingCommand("m-1", STOPPED, "stop-1"));
        MeetingCommandResult retry = service.stop(new StopMeetingCommand("m-1", STOPPED, "stop-1"));
        assertEquals(MeetingState.STOPPING, first.meeting().state());
        assertEquals(first, retry);
    }

    @Test
    void stoppingUnknownMeetingIsRejected() {
        assertThrows(MeetingNotFoundException.class,
                () -> service.stop(new StopMeetingCommand("missing", STOPPED, "stop-1")));
    }

    @Test
    void stopRetryReplaysAfterMeetingSnapshotWasDeleted() {
        MutableMeetingRepository repository = new MutableMeetingRepository();
        MeetingCommandService retryable = new MeetingCommandService(
                repository, new InMemoryIdempotencyStore());
        retryable.create(create("create-for-stop-retry"));
        MeetingAggregate running = MeetingAggregate.fromSnapshot(repository.find("m-1").orElseThrow());
        running.apply(MeetingEvent.AUTHORIZE, CREATED);
        running.apply(MeetingEvent.START, CREATED);
        repository.save(running.snapshot());

        StopMeetingCommand command = new StopMeetingCommand("m-1", STOPPED, "stop-after-delete");
        MeetingCommandResult first = retryable.stop(command);
        repository.delete("m-1");

        assertEquals(first, retryable.stop(command));
    }

    @Test
    void concurrentReserveAllowsOnlyOneOwner() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        MeetingAggregate aggregate = new MeetingAggregate("m-reserve", "s-1", "p-1", 1, CREATED);
        MeetingCommandResult result = new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED, aggregate.snapshot());
        var candidate = new com.czur.reference.application.port.IdempotencyStore.IdempotencyRecord(
                "meeting.create", "reserve-key", "digest-1", result);
        int callers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<com.czur.reference.application.port.IdempotencyStore.Reservation>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return store.reserve(candidate);
                }));
            }
            start.countDown();
            long acquired = 0;
            long replayed = 0;
            for (Future<com.czur.reference.application.port.IdempotencyStore.Reservation> future : futures) {
                var status = future.get().status();
                if (status == com.czur.reference.application.port.IdempotencyStore.ReservationStatus.ACQUIRED) {
                    acquired++;
                } else if (status == com.czur.reference.application.port.IdempotencyStore.ReservationStatus.REPLAY) {
                    replayed++;
                }
            }
            assertEquals(1, acquired);
            assertEquals(callers - 1, replayed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedPersistenceReleasesReservationForRetry() {
        InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
        MeetingRepository failingMeetings = new MeetingRepository() {
            private final InMemoryMeetingRepository delegate = new InMemoryMeetingRepository();
            private boolean fail = true;

            @Override
            public Optional<MeetingAggregate.MeetingSnapshot> find(String meetingId) {
                return delegate.find(meetingId);
            }

            @Override
            public synchronized void save(MeetingAggregate.MeetingSnapshot meeting) {
                if (fail) {
                    fail = false;
                    throw new IllegalStateException("simulated persistence failure");
                }
                delegate.save(meeting);
            }
        };
        MeetingCommandService retryable = new MeetingCommandService(failingMeetings, idempotency);
        CreateMeetingCommand command = create("retry-after-failure");
        assertThrows(IllegalStateException.class, () -> retryable.create(command));
        MeetingCommandResult retried = retryable.create(command);
        assertEquals(MeetingCommandResult.Operation.CREATED, retried.operation());
    }

    private static CreateMeetingCommand create(String key) {
        return new CreateMeetingCommand("m-1", "session-1", "profile-1", 1, CREATED, key);
    }

    private static CreateMeetingCommand create(String key, String deploymentScope, String meetingId) {
        return new CreateMeetingCommand(
                meetingId, deploymentScope, "session-1", "profile-1", 1, CREATED, key);
    }

    private static final class MutableMeetingRepository implements MeetingRepository {
        private final Map<String, MeetingAggregate.MeetingSnapshot> meetings = new ConcurrentHashMap<>();

        @Override
        public Optional<MeetingAggregate.MeetingSnapshot> find(String meetingId) {
            return Optional.ofNullable(meetings.get(meetingId));
        }

        @Override
        public void save(MeetingAggregate.MeetingSnapshot meeting) {
            meetings.put(meeting.meetingId(), meeting);
        }

        void delete(String meetingId) {
            meetings.remove(meetingId);
        }
    }
}
