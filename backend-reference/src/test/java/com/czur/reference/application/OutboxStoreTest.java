package com.czur.reference.application;

import com.czur.reference.application.persistence.InMemoryOutboxStore;
import com.czur.reference.application.port.OutboxStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxStoreTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void claimRequestDefensivelyCopiesTypesAndUsesDeterministicOrder() {
        Set<String> callerTypes = new LinkedHashSet<>(List.of(
                "TranscriptFinalized", "ArtifactReady", "MeetingCreated"));

        OutboxStore.ClaimRequest request = new OutboxStore.ClaimRequest(
                callerTypes, "worker-a", T0, Duration.ofSeconds(30), 10);
        callerTypes.add("NotificationRequested");

        assertEquals(List.of("ArtifactReady", "MeetingCreated", "TranscriptFinalized"),
                new ArrayList<>(request.registeredEventTypes()));
        assertFalse(request.registeredEventTypes().contains("NotificationRequested"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.registeredEventTypes().add("InputAccepted"));

        OutboxStore.ClaimRequest empty = new OutboxStore.ClaimRequest(
                Set.of(), "worker-a", T0, Duration.ofSeconds(30), 1);
        assertTrue(empty.registeredEventTypes().isEmpty());
    }

    @Test
    void claimRequestRejectsInvalidInputs() {
        Set<String> typeWithNull = new LinkedHashSet<>();
        typeWithNull.add(null);

        assertThrows(NullPointerException.class,
                () -> new OutboxStore.ClaimRequest(null, "worker", T0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(typeWithNull, "worker", T0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("  "), "worker", T0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), null, T0,
                        Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), " ", T0,
                        Duration.ofSeconds(1), 1));
        assertThrows(NullPointerException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), "worker", null,
                        Duration.ofSeconds(1), 1));
        assertThrows(NullPointerException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), "worker", T0, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), "worker", T0,
                        Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), "worker", T0,
                        Duration.ofSeconds(-1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), "worker", T0,
                        Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.ClaimRequest(Set.of("MeetingCreated"), "worker", T0,
                        Duration.ofSeconds(1), -1));
    }

    @Test
    void renewResultAcceptsOnlyClosedValidCombinations() {
        OutboxStore.LeaseToken lease = lease("event-renewed", "worker-a", T0.plusSeconds(60));
        OutboxStore.OutboxEvent leased = leasedEvent(
                "event-renewed", "worker-a", T0.plusSeconds(60));
        OutboxStore.OutboxEvent pending = event("event-pending", "MeetingCreated");
        OutboxStore.OutboxEvent published = publishedEvent("event-published");
        OutboxStore.OutboxEvent dead = deadEvent("event-dead");

        OutboxStore.RenewResult renewed = new OutboxStore.RenewResult(
                OutboxStore.RenewStatus.RENEWED, leased, lease);
        assertEquals(leased, renewed.event());
        assertEquals(lease, renewed.lease());

        OutboxStore.RenewResult missing = new OutboxStore.RenewResult(
                OutboxStore.RenewStatus.NOT_FOUND, null, null);
        assertNull(missing.event());
        assertNull(missing.lease());

        assertRenewalFailure(OutboxStore.RenewStatus.NOT_OWNER, leased);
        assertRenewalFailure(OutboxStore.RenewStatus.NOT_OWNER, pending);
        assertRenewalFailure(OutboxStore.RenewStatus.LEASE_EXPIRED, leased);
        assertRenewalFailure(OutboxStore.RenewStatus.ALREADY_PUBLISHED, published);
        assertRenewalFailure(OutboxStore.RenewStatus.ALREADY_DEAD, dead);
    }

    @Test
    void renewResultRejectsAmbiguousOrMisalignedCombinations() {
        OutboxStore.LeaseToken lease = lease("event-renewed", "worker-a", T0.plusSeconds(60));
        OutboxStore.OutboxEvent leased = leasedEvent(
                "event-renewed", "worker-a", T0.plusSeconds(60));

        assertThrows(NullPointerException.class,
                () -> new OutboxStore.RenewResult(null, leased, lease));
        assertThrows(NullPointerException.class,
                () -> new OutboxStore.RenewResult(OutboxStore.RenewStatus.RENEWED, null, lease));
        assertThrows(NullPointerException.class,
                () -> new OutboxStore.RenewResult(OutboxStore.RenewStatus.RENEWED, leased, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.RENEWED, event("event-renewed", "MeetingCreated"), lease));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.RENEWED,
                        leasedEvent("other-event", "worker-a", T0.plusSeconds(60)), lease));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.RENEWED,
                        leasedEvent("event-renewed", "worker-b", T0.plusSeconds(60)), lease));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.RENEWED,
                        leasedEvent("event-renewed", "worker-a", T0.plusSeconds(61)), lease));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(OutboxStore.RenewStatus.NOT_FOUND, leased, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(OutboxStore.RenewStatus.NOT_FOUND, null, lease));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(OutboxStore.RenewStatus.NOT_OWNER, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(OutboxStore.RenewStatus.NOT_OWNER, leased, lease));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.LEASE_EXPIRED,
                        event("event-pending", "MeetingCreated"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.ALREADY_PUBLISHED, leased, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.ALREADY_DEAD, publishedEvent("event-published"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.NOT_OWNER, publishedEvent("event-published"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxStore.RenewResult(
                        OutboxStore.RenewStatus.NOT_OWNER, deadEvent("event-dead"), null));
    }

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
    void claimUsesExactTypeAllowlistBeforeOrderingAndLimit() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.append(event("event-00-unknown", "UnknownType"));
        store.append(event("event-01-known", "MeetingCreated"));

        assertTrue(store.claim(new OutboxStore.ClaimRequest(
                Set.of(), "worker", T0, Duration.ofSeconds(30), 1)).isEmpty());
        assertTrue(store.claim(new OutboxStore.ClaimRequest(
                Set.of("Unknown"), "worker", T0, Duration.ofSeconds(30), 1)).isEmpty());

        List<OutboxStore.ClaimedEvent> claimed = store.claim(new OutboxStore.ClaimRequest(
                Set.of("MeetingCreated"), "worker", T0, Duration.ofSeconds(30), 1));
        assertEquals(List.of("event-01-known"), claimed.stream()
                .map(item -> item.event().eventId()).toList());
        assertEquals(OutboxStore.EventStatus.PENDING,
                store.find("event-00-unknown").orElseThrow().status());
    }

    @Test
    void renewRotatesFenceAndRejectsOldLeaseForEveryOperation() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.append(event("event-renew", "MeetingCreated"));
        OutboxStore.ClaimedEvent claimed = store.claim(new OutboxStore.ClaimRequest(
                Set.of("MeetingCreated"), "worker", T0, Duration.ofSeconds(10), 1)).get(0);

        OutboxStore.RenewResult renewed = store.renew(
                claimed.lease(), T0.plusSeconds(5), Duration.ofSeconds(10));

        assertEquals(OutboxStore.RenewStatus.RENEWED, renewed.status());
        assertNotEquals(claimed.lease().token(), renewed.lease().token());
        assertEquals(claimed.lease().leaseVersion() + 1, renewed.lease().leaseVersion());
        assertEquals(T0.plusSeconds(15), renewed.lease().expiresAt());
        assertEquals(T0.plusSeconds(5), renewed.lease().claimedAt());
        assertEquals(OutboxStore.RenewStatus.NOT_OWNER,
                store.renew(claimed.lease(), T0.plusSeconds(6), Duration.ofSeconds(10)).status());
        assertEquals(OutboxStore.PublishStatus.NOT_OWNER,
                store.markPublished(claimed.lease(), T0.plusSeconds(6)).status());
        assertEquals(OutboxStore.FailureStatus.NOT_OWNER,
                store.markFailed(claimed.lease(), T0.plusSeconds(6), T0.plusSeconds(7),
                        "stale lease", 3).status());
        assertEquals(OutboxStore.PublishStatus.PUBLISHED,
                store.markPublished(renewed.lease(), T0.plusSeconds(6)).status());
    }

    @Test
    void claimedAtIsOnlyAnOperationWindowAndNotAnOwnershipFence() {
        InMemoryOutboxStore renewStore = new InMemoryOutboxStore();
        renewStore.append(event("event-claimed-at-renew", "ClaimedAtRenew"));
        OutboxStore.LeaseToken renewLease = renewStore.claim(new OutboxStore.ClaimRequest(
                Set.of("ClaimedAtRenew"), "worker", T0, Duration.ofSeconds(10), 1)).get(0).lease();
        OutboxStore.LeaseToken shiftedRenewLease = withClaimedAt(
                renewLease, renewLease.claimedAt().plusNanos(1_000));
        assertEquals(OutboxStore.RenewStatus.RENEWED,
                renewStore.renew(
                        shiftedRenewLease, T0.plusSeconds(5), Duration.ofSeconds(10)).status());

        InMemoryOutboxStore publishStore = new InMemoryOutboxStore();
        publishStore.append(event("event-claimed-at-publish", "ClaimedAtPublish"));
        OutboxStore.LeaseToken publishLease = publishStore.claim(new OutboxStore.ClaimRequest(
                Set.of("ClaimedAtPublish"), "worker", T0, Duration.ofSeconds(10), 1)).get(0).lease();
        assertEquals(OutboxStore.PublishStatus.PUBLISHED,
                publishStore.markPublished(
                        withClaimedAt(publishLease, publishLease.claimedAt().plusNanos(1_000)),
                        T0.plusSeconds(1)).status());

        InMemoryOutboxStore failureStore = new InMemoryOutboxStore();
        failureStore.append(event("event-claimed-at-fail", "ClaimedAtFail"));
        OutboxStore.LeaseToken failureLease = failureStore.claim(new OutboxStore.ClaimRequest(
                Set.of("ClaimedAtFail"), "worker", T0, Duration.ofSeconds(10), 1)).get(0).lease();
        assertEquals(OutboxStore.FailureStatus.RETRY_SCHEDULED,
                failureStore.markFailed(
                        withClaimedAt(failureLease, failureLease.claimedAt().plusNanos(1_000)),
                        T0.plusSeconds(1), T0.plusSeconds(2), "retry", 3).status());
    }

    @Test
    void frozenLeaseFieldsRemainOwnershipFences() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.append(event("event-field-fences", "FieldFences"));
        OutboxStore.LeaseToken legal = store.claim(new OutboxStore.ClaimRequest(
                Set.of("FieldFences"), "worker", T0, Duration.ofSeconds(10), 1)).get(0).lease();
        List<OutboxStore.LeaseToken> mismatched = List.of(
                new OutboxStore.LeaseToken(
                        legal.eventId(), "other-worker", legal.token(), legal.expiresAt(),
                        legal.claimedAt(), legal.leaseVersion()),
                new OutboxStore.LeaseToken(
                        legal.eventId(), legal.owner(), "other-token", legal.expiresAt(),
                        legal.claimedAt(), legal.leaseVersion()),
                new OutboxStore.LeaseToken(
                        legal.eventId(), legal.owner(), legal.token(), legal.expiresAt(),
                        legal.claimedAt(), legal.leaseVersion() + 1),
                new OutboxStore.LeaseToken(
                        legal.eventId(), legal.owner(), legal.token(), legal.expiresAt().plusSeconds(1),
                        legal.claimedAt(), legal.leaseVersion()));

        for (OutboxStore.LeaseToken invalid : mismatched) {
            assertEquals(OutboxStore.RenewStatus.NOT_OWNER,
                    store.renew(invalid, T0.plusSeconds(1), Duration.ofSeconds(20)).status());
            assertEquals(OutboxStore.PublishStatus.NOT_OWNER,
                    store.markPublished(invalid, T0.plusSeconds(1)).status());
            assertEquals(OutboxStore.FailureStatus.NOT_OWNER,
                    store.markFailed(invalid, T0.plusSeconds(1), T0.plusSeconds(2),
                            "invalid fence", 3).status());
        }
        assertEquals(OutboxStore.PublishStatus.PUBLISHED,
                store.markPublished(legal, T0.plusSeconds(1)).status());
    }

    @Test
    void renewClassifiesMissingExpiredAndTerminalEvents() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxStore.LeaseToken missing = new OutboxStore.LeaseToken(
                "missing-renew", "worker", "token", T0.plusSeconds(10), T0, 1);
        assertEquals(OutboxStore.RenewStatus.NOT_FOUND,
                store.renew(missing, T0.plusSeconds(1), Duration.ofSeconds(10)).status());

        store.append(event("event-expired", "Expired"));
        OutboxStore.ClaimedEvent expired = store.claim(new OutboxStore.ClaimRequest(
                Set.of("Expired"), "worker", T0, Duration.ofSeconds(10), 1)).get(0);
        assertEquals(OutboxStore.RenewStatus.LEASE_EXPIRED,
                store.renew(expired.lease(), T0.plusSeconds(10), Duration.ofSeconds(10)).status());

        store.append(event("event-published", "Published"));
        OutboxStore.ClaimedEvent published = store.claim(new OutboxStore.ClaimRequest(
                Set.of("Published"), "worker", T0, Duration.ofSeconds(10), 1)).get(0);
        store.markPublished(published.lease(), T0.plusSeconds(1));
        assertEquals(OutboxStore.RenewStatus.ALREADY_PUBLISHED,
                store.renew(published.lease(), T0.plusSeconds(2), Duration.ofSeconds(10)).status());

        store.append(event("event-dead", "Dead"));
        OutboxStore.ClaimedEvent dead = store.claim(new OutboxStore.ClaimRequest(
                Set.of("Dead"), "worker", T0, Duration.ofSeconds(10), 1)).get(0);
        store.markFailed(dead.lease(), T0.plusSeconds(1), T0.plusSeconds(2), "dead", 1);
        assertEquals(OutboxStore.RenewStatus.ALREADY_DEAD,
                store.renew(dead.lease(), T0.plusSeconds(2), Duration.ofSeconds(10)).status());
    }

    @Test
    void concurrentRenewHasExactlyOneWinner() throws Exception {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.append(event("event-concurrent-renew", "MeetingCreated"));
        OutboxStore.LeaseToken lease = store.claim(new OutboxStore.ClaimRequest(
                Set.of("MeetingCreated"), "worker", T0, Duration.ofSeconds(10), 1)).get(0).lease();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OutboxStore.RenewResult> first = executor.submit(() -> {
                start.await();
                return store.renew(lease, T0.plusSeconds(5), Duration.ofSeconds(10));
            });
            Future<OutboxStore.RenewResult> second = executor.submit(() -> {
                start.await();
                return store.renew(lease, T0.plusSeconds(5), Duration.ofSeconds(10));
            });
            start.countDown();
            List<OutboxStore.RenewStatus> statuses = List.of(
                    first.get(5, TimeUnit.SECONDS).status(), second.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, statuses.stream()
                    .filter(status -> status == OutboxStore.RenewStatus.RENEWED).count());
            assertEquals(1, statuses.stream()
                    .filter(status -> status == OutboxStore.RenewStatus.NOT_OWNER).count());
        } finally {
            executor.shutdownNow();
        }
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
                return store.claim(new OutboxStore.ClaimRequest(
                        Set.of("InputAccepted"), owner, T0, Duration.ofSeconds(30), 1));
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

        OutboxStore.ClaimedEvent first = store.claim(new OutboxStore.ClaimRequest(
                Set.of("TranscriptFinalized"), "worker-a", T0, Duration.ofSeconds(10), 1)).get(0);
        assertTrue(store.claim(new OutboxStore.ClaimRequest(
                Set.of("TranscriptFinalized"), "worker-b", T0.plusSeconds(5), Duration.ofSeconds(10), 1)).isEmpty());

        OutboxStore.ClaimedEvent second = store.claim(new OutboxStore.ClaimRequest(
                Set.of("TranscriptFinalized"), "worker-b", T0.plusSeconds(10), Duration.ofSeconds(10), 1)).get(0);
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
        OutboxStore.ClaimedEvent claimed = store.claim(new OutboxStore.ClaimRequest(
                Set.of("ArtifactReady"), "worker", T0, Duration.ofMinutes(1), 1)).get(0);

        OutboxStore.PublishResult result = store.markPublished(claimed.lease(), T0.plusSeconds(1));
        assertEquals(OutboxStore.PublishStatus.PUBLISHED, result.status());
        assertTrue(store.claim(new OutboxStore.ClaimRequest(
                Set.of("ArtifactReady"), "worker-2", T0.plus(Duration.ofMinutes(2)), Duration.ofMinutes(1), 1)).isEmpty());
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

        OutboxStore.ClaimedEvent first = store.claim(new OutboxStore.ClaimRequest(
                Set.of("NotificationRequested"), "worker", T0, Duration.ofSeconds(30), 1)).get(0);
        OutboxStore.FailureResult retry1 = store.markFailed(
                first.lease(), T0.plusSeconds(1), T0.plusSeconds(10), "timeout", 3);
        assertEquals(OutboxStore.FailureStatus.RETRY_SCHEDULED, retry1.status());
        assertEquals(1, retry1.event().attemptCount());
        assertTrue(store.claim(new OutboxStore.ClaimRequest(
                Set.of("NotificationRequested"), "worker", T0.plusSeconds(9), Duration.ofSeconds(30), 1)).isEmpty());
        OutboxStore.AppendResult retryReplay = store.append(original);
        assertEquals(OutboxStore.AppendStatus.REPLAYED, retryReplay.status());
        assertEquals(T0.plusSeconds(10), retryReplay.event().availableAt());
        assertEquals(1, retryReplay.event().attemptCount());

        OutboxStore.ClaimedEvent second = store.claim(new OutboxStore.ClaimRequest(
                Set.of("NotificationRequested"), "worker", T0.plusSeconds(10), Duration.ofSeconds(30), 1)).get(0);
        OutboxStore.FailureResult retry2 = store.markFailed(
                second.lease(), T0.plusSeconds(11), T0.plusSeconds(20), "provider unavailable", 3);
        assertEquals(OutboxStore.FailureStatus.RETRY_SCHEDULED, retry2.status());
        assertEquals(2, retry2.event().attemptCount());

        OutboxStore.ClaimedEvent third = store.claim(new OutboxStore.ClaimRequest(
                Set.of("NotificationRequested"), "worker", T0.plusSeconds(20), Duration.ofSeconds(30), 1)).get(0);
        OutboxStore.FailureResult dead = store.markFailed(
                third.lease(), T0.plusSeconds(21), T0.plusSeconds(30), "permanent failure", 3);
        assertEquals(OutboxStore.FailureStatus.DEAD, dead.status());
        assertEquals(3, dead.event().attemptCount());
        assertEquals(OutboxStore.EventStatus.DEAD, dead.event().status());
        assertFalse(store.find("event-5").orElseThrow().lastError().isBlank());
        assertTrue(store.claim(new OutboxStore.ClaimRequest(
                Set.of("NotificationRequested"), "worker", T0.plus(Duration.ofMinutes(1)), Duration.ofSeconds(30), 1)).isEmpty());
        OutboxStore.AppendResult deadReplay = store.append(original);
        assertEquals(OutboxStore.AppendStatus.REPLAYED, deadReplay.status());
        assertEquals(OutboxStore.EventStatus.DEAD, deadReplay.event().status());
        assertEquals(3, deadReplay.event().attemptCount());
    }

    private static OutboxStore.OutboxEvent event(String id, String type) {
        return OutboxStore.OutboxEvent.pending(
                id, "meeting", "meeting-1", type, "{\"id\":\"meeting-1\"}", T0, T0);
    }

    private static OutboxStore.OutboxEvent leasedEvent(String id, String owner, Instant expiresAt) {
        return new OutboxStore.OutboxEvent(
                id, "meeting", "meeting-1", "MeetingCreated", "{\"id\":\"meeting-1\"}",
                T0, T0, OutboxStore.EventStatus.LEASED, 0,
                owner, expiresAt, null, null);
    }

    private static OutboxStore.OutboxEvent publishedEvent(String id) {
        return new OutboxStore.OutboxEvent(
                id, "meeting", "meeting-1", "MeetingCreated", "{\"id\":\"meeting-1\"}",
                T0, T0, OutboxStore.EventStatus.PUBLISHED, 0,
                null, null, T0.plusSeconds(30), null);
    }

    private static OutboxStore.OutboxEvent deadEvent(String id) {
        return new OutboxStore.OutboxEvent(
                id, "meeting", "meeting-1", "MeetingCreated", "{\"id\":\"meeting-1\"}",
                T0, T0, OutboxStore.EventStatus.DEAD, 3,
                null, null, null, "delivery failed");
    }

    private static OutboxStore.LeaseToken lease(String eventId, String owner, Instant expiresAt) {
        return new OutboxStore.LeaseToken(
                eventId, owner, "opaque-token", expiresAt, T0.plusSeconds(1), 2);
    }

    private static OutboxStore.LeaseToken withClaimedAt(
            OutboxStore.LeaseToken lease, Instant claimedAt) {
        return new OutboxStore.LeaseToken(
                lease.eventId(), lease.owner(), lease.token(), lease.expiresAt(),
                claimedAt, lease.leaseVersion());
    }

    private static void assertRenewalFailure(
            OutboxStore.RenewStatus status, OutboxStore.OutboxEvent event) {
        OutboxStore.RenewResult failure = new OutboxStore.RenewResult(status, event, null);
        assertEquals(status, failure.status());
        assertEquals(event, failure.event());
        assertNull(failure.lease());
    }
}
