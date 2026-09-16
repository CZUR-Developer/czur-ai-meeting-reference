package com.czur.reference.application;

import com.czur.reference.application.persistence.InMemoryOutboxStore;
import com.czur.reference.application.port.OutboxStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxStoreTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void appendIsIdempotentAndRejectsSameIdWithDifferentMessage() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxStore.OutboxEvent event = event("event-1", "MeetingCreated");

        assertEquals(OutboxStore.AppendStatus.APPENDED, store.append(event).status());
        OutboxStore.AppendResult replay = store.append(event);
        assertEquals(OutboxStore.AppendStatus.REPLAYED, replay.status());
        assertEquals(event, replay.event());

        OutboxStore.OutboxEvent conflicting = OutboxStore.OutboxEvent.pending(
                "event-1", "meeting", "meeting-1", "StopRequested", "{}", T0, T0);
        OutboxStore.AppendResult conflict = store.append(conflicting);
        assertEquals(OutboxStore.AppendStatus.CONFLICT, conflict.status());
        assertEquals("MeetingCreated", conflict.event().eventType());
    }

    @Test
    void concurrentClaimsGiveOneWorkerTheSameEvent() throws Exception {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.append(event("event-2", "InputAccepted"));

        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<OutboxStore.ClaimedEvent>>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            String owner = "worker-" + i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.claim(owner, T0, Duration.ofSeconds(30), 1);
            }));
        }
        ready.await();
        start.countDown();
        int claimers = 0;
        String owner = null;
        for (Future<List<OutboxStore.ClaimedEvent>> future : futures) {
            List<OutboxStore.ClaimedEvent> claimed = future.get();
            if (!claimed.isEmpty()) {
                claimers++;
                owner = claimed.get(0).lease().owner();
            }
        }
        executor.shutdownNow();
        assertEquals(1, claimers);
        assertTrue(owner.startsWith("worker-"));
    }

    @Test
    void expiredLeaseCanBeClaimedAgainAndOldTokenCannotPublish() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.append(event("event-3", "TranscriptFinalized"));

        OutboxStore.ClaimedEvent first = store.claim(
                "worker-a", T0, Duration.ofSeconds(10), 1).get(0);
        assertTrue(store.claim("worker-b", T0.plusSeconds(5), Duration.ofSeconds(10), 1).isEmpty());

        OutboxStore.ClaimedEvent second = store.claim(
                "worker-b", T0.plusSeconds(10), Duration.ofSeconds(10), 1).get(0);
        assertNotEquals(first.lease().token(), second.lease().token());
        assertEquals(OutboxStore.PublishStatus.NOT_OWNER,
                store.markPublished(first.lease(), T0.plusSeconds(10)).status());
        assertEquals(OutboxStore.PublishStatus.PUBLISHED,
                store.markPublished(second.lease(), T0.plusSeconds(11)).status());
    }

    @Test
    void publishedEventIsNeverClaimedAgain() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxStore.OutboxEvent original = event("event-4", "ArtifactReady");
        store.append(original);
        OutboxStore.ClaimedEvent claimed = store.claim(
                "worker", T0, Duration.ofMinutes(1), 1).get(0);

        OutboxStore.PublishResult result = store.markPublished(claimed.lease(), T0.plusSeconds(1));
        assertEquals(OutboxStore.PublishStatus.PUBLISHED, result.status());
        assertTrue(store.claim("worker-2", T0.plus(Duration.ofMinutes(2)), Duration.ofMinutes(1), 1).isEmpty());
        assertEquals(OutboxStore.EventStatus.PUBLISHED,
                store.find("event-4").orElseThrow().status());
        assertEquals(OutboxStore.PublishStatus.ALREADY_PUBLISHED,
                store.markPublished(claimed.lease(), T0.plusSeconds(2)).status());
        OutboxStore.AppendResult replay = store.append(original);
        assertEquals(OutboxStore.AppendStatus.REPLAYED, replay.status());
        assertEquals(OutboxStore.EventStatus.PUBLISHED, replay.event().status());
    }

    @Test
    void unknownEventOperationsReturnExplicitNotFoundWithoutThrowing() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxStore.LeaseToken unknown = new OutboxStore.LeaseToken(
                "missing", "worker", "token", T0.plusSeconds(10), T0);

        assertEquals(OutboxStore.PublishStatus.NOT_FOUND,
                store.markPublished(unknown, T0.plusSeconds(1)).status());
        assertEquals(OutboxStore.FailureStatus.NOT_FOUND,
                store.markFailed(unknown, T0.plusSeconds(1), T0.plusSeconds(2), "not found", 3).status());
    }

    @Test
    void failuresIncrementAttemptsAndMoveToDeadLetter() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxStore.OutboxEvent original = event("event-5", "NotificationRequested");
        store.append(original);

        OutboxStore.ClaimedEvent first = store.claim(
                "worker", T0, Duration.ofSeconds(30), 1).get(0);
        OutboxStore.FailureResult retry1 = store.markFailed(
                first.lease(), T0.plusSeconds(1), T0.plusSeconds(10), "timeout", 3);
        assertEquals(OutboxStore.FailureStatus.RETRY_SCHEDULED, retry1.status());
        assertEquals(1, retry1.event().attemptCount());
        assertTrue(store.claim("worker", T0.plusSeconds(9), Duration.ofSeconds(30), 1).isEmpty());
        OutboxStore.AppendResult retryReplay = store.append(original);
        assertEquals(OutboxStore.AppendStatus.REPLAYED, retryReplay.status());
        assertEquals(T0.plusSeconds(10), retryReplay.event().availableAt());
        assertEquals(1, retryReplay.event().attemptCount());

        OutboxStore.ClaimedEvent second = store.claim(
                "worker", T0.plusSeconds(10), Duration.ofSeconds(30), 1).get(0);
        OutboxStore.FailureResult retry2 = store.markFailed(
                second.lease(), T0.plusSeconds(11), T0.plusSeconds(20), "provider unavailable", 3);
        assertEquals(OutboxStore.FailureStatus.RETRY_SCHEDULED, retry2.status());
        assertEquals(2, retry2.event().attemptCount());

        OutboxStore.ClaimedEvent third = store.claim(
                "worker", T0.plusSeconds(20), Duration.ofSeconds(30), 1).get(0);
        OutboxStore.FailureResult dead = store.markFailed(
                third.lease(), T0.plusSeconds(21), T0.plusSeconds(30), "permanent failure", 3);
        assertEquals(OutboxStore.FailureStatus.DEAD, dead.status());
        assertEquals(3, dead.event().attemptCount());
        assertEquals(OutboxStore.EventStatus.DEAD, dead.event().status());
        assertFalse(store.find("event-5").orElseThrow().lastError().isBlank());
        assertTrue(store.claim("worker", T0.plus(Duration.ofMinutes(1)), Duration.ofSeconds(30), 1).isEmpty());
        OutboxStore.AppendResult deadReplay = store.append(original);
        assertEquals(OutboxStore.AppendStatus.REPLAYED, deadReplay.status());
        assertEquals(OutboxStore.EventStatus.DEAD, deadReplay.event().status());
        assertEquals(3, deadReplay.event().attemptCount());
    }

    private static OutboxStore.OutboxEvent event(String id, String type) {
        return OutboxStore.OutboxEvent.pending(
                id, "meeting", "meeting-1", type, "{\"id\":\"meeting-1\"}", T0, T0);
    }
}
