package com.czur.reference.application.persistence;

import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalUsageFactStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证最终用量事实内存端口的首次写入、重放、冲突保留和脱敏。 */
class InMemoryFinalUsageFactStoreTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-21T03:04:05Z");
    private static final String MEETING_ID = "meeting-final-usage-sensitive";
    private static final String INPUT_BINDING = "input-binding-sensitive";
    private static final String DIGEST = "sha256-final-usage-sensitive";

    @Test
    void firstFactIsAppliedAndCanBeFound() {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFact fact = fact(2_000, INPUT_BINDING, DIGEST);

        FinalUsageFactStore.PersistResult result = store.persist(fact);

        assertEquals(FinalUsageFactStore.PersistStatus.APPLIED, result.status());
        assertEquals(fact, result.fact());
        assertEquals(Optional.of(fact), store.find(MEETING_ID));
    }

    @Test
    void zeroDurationIsAValidFinalUsageFact() {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFact fact = fact(0, INPUT_BINDING, DIGEST);

        FinalUsageFactStore.PersistResult result = store.persist(fact);

        assertEquals(FinalUsageFactStore.PersistStatus.APPLIED, result.status());
        assertEquals(0, result.fact().actual().amount());
    }

    @Test
    void sameCompleteFactIsReplayedWithoutReplacingWinner() {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFact first = fact(2_000, INPUT_BINDING, DIGEST);
        FinalUsageFact equalButDistinct = fact(2_000, INPUT_BINDING, DIGEST);

        assertEquals(FinalUsageFactStore.PersistStatus.APPLIED, store.persist(first).status());
        FinalUsageFactStore.PersistResult replay = store.persist(equalButDistinct);

        assertEquals(FinalUsageFactStore.PersistStatus.REPLAYED, replay.status());
        assertEquals(first, replay.fact());
        assertEquals(first, store.find(MEETING_ID).orElseThrow());
    }

    @Test
    void differentDurationConflictsAndLeavesOriginalFactUntouched() {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFact first = fact(2_000, INPUT_BINDING, DIGEST);
        FinalUsageFact conflicting = fact(2_001, INPUT_BINDING, DIGEST);

        store.persist(first);
        FinalUsageFactStore.PersistResult result = store.persist(conflicting);

        assertEquals(FinalUsageFactStore.PersistStatus.CONFLICT, result.status());
        assertEquals(first, result.fact());
        assertEquals(first, store.find(MEETING_ID).orElseThrow());
    }

    @Test
    void differentInputBindingConflictsInsteadOfCrossBindingMeeting() {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFact first = fact(2_000, INPUT_BINDING, DIGEST);
        FinalUsageFact conflicting = fact(2_000, "another-input-binding", DIGEST);

        store.persist(first);
        FinalUsageFactStore.PersistResult result = store.persist(conflicting);

        assertEquals(FinalUsageFactStore.PersistStatus.CONFLICT, result.status());
        assertEquals(first, result.fact());
        assertEquals(first, store.find(MEETING_ID).orElseThrow());
    }

    @Test
    void concurrentSameCandidateHasExactlyOneApplyAndStableReplays() throws Exception {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFact candidate = fact(2_000, INPUT_BINDING, DIGEST);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<FinalUsageFactStore.PersistResult>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.persist(candidate);
                }));
            }
            ready.await();
            start.countDown();

            int applied = 0;
            int replayed = 0;
            for (Future<FinalUsageFactStore.PersistResult> future : futures) {
                FinalUsageFactStore.PersistResult result = future.get();
                if (result.status() == FinalUsageFactStore.PersistStatus.APPLIED) {
                    applied++;
                } else {
                    assertEquals(FinalUsageFactStore.PersistStatus.REPLAYED, result.status());
                    replayed++;
                }
                assertEquals(candidate, result.fact());
            }
            assertEquals(1, applied);
            assertEquals(workers - 1, replayed);
            assertEquals(candidate, store.find(MEETING_ID).orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentCandidatesHaveOneWinnerAndOneConflict() throws Exception {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFact firstCandidate = fact(2_000, INPUT_BINDING, DIGEST);
        FinalUsageFact secondCandidate = fact(2_001, INPUT_BINDING, DIGEST);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FinalUsageFactStore.PersistResult> first = executor.submit(
                    () -> persistAfterStart(store, firstCandidate, ready, start));
            Future<FinalUsageFactStore.PersistResult> second = executor.submit(
                    () -> persistAfterStart(store, secondCandidate, ready, start));
            ready.await();
            start.countDown();

            FinalUsageFactStore.PersistResult firstResult = first.get();
            FinalUsageFactStore.PersistResult secondResult = second.get();
            assertEquals(1, List.of(firstResult, secondResult).stream()
                    .filter(result -> result.status() == FinalUsageFactStore.PersistStatus.APPLIED)
                    .count());
            assertEquals(1, List.of(firstResult, secondResult).stream()
                    .filter(result -> result.status() == FinalUsageFactStore.PersistStatus.CONFLICT)
                    .count());
            FinalUsageFact winner = store.find(MEETING_ID).orElseThrow();
            assertTrue(winner.equals(firstCandidate) || winner.equals(secondCandidate));
            FinalUsageFactStore.PersistResult conflict = firstResult.status()
                    == FinalUsageFactStore.PersistStatus.CONFLICT ? firstResult : secondResult;
            assertEquals(winner, conflict.fact());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidInputsFailClosed() {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();

        assertThrows(IllegalArgumentException.class, () -> store.find(null));
        assertThrows(IllegalArgumentException.class, () -> store.find(" "));
        assertThrows(NullPointerException.class, () -> store.persist(null));
        assertThrows(NullPointerException.class,
                () -> new FinalUsageFactStore.PersistResult(null, fact(0, INPUT_BINDING, DIGEST)));
        assertThrows(NullPointerException.class,
                () -> new FinalUsageFactStore.PersistResult(
                        FinalUsageFactStore.PersistStatus.APPLIED, null));
        assertThrows(IllegalArgumentException.class,
                () -> FinalUsageFact.fromInput(new FinalizationInputFact(
                        MEETING_ID, INPUT_BINDING, 1, -1, "zh-CN", List.of(),
                        ACCEPTED_AT, DIGEST)));
    }

    @Test
    void diagnosticsDoNotExposeSensitiveSourceFields() {
        InMemoryFinalUsageFactStore store = new InMemoryFinalUsageFactStore();
        FinalUsageFactStore.PersistResult result = store.persist(
                fact(100, INPUT_BINDING, DIGEST));
        String text = result + " " + result.fact();

        assertFalse(text.contains(MEETING_ID));
        assertFalse(text.contains(INPUT_BINDING));
        assertFalse(text.contains(DIGEST));
        assertEquals("FinalUsageFactStore.PersistResult[status=APPLIED, fact=redacted]",
                result.toString());
    }

    private static FinalUsageFact fact(long durationMs, String binding, String digest) {
        return FinalUsageFact.fromInput(new FinalizationInputFact(
                MEETING_ID,
                binding,
                7,
                durationMs,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                digest));
    }

    private static FinalUsageFactStore.PersistResult persistAfterStart(
            InMemoryFinalUsageFactStore store,
            FinalUsageFact candidate,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return store.persist(candidate);
    }
}
