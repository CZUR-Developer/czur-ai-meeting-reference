package com.czur.reference.application.persistence;

import com.czur.reference.application.port.DurableInputFactStore;
import com.czur.reference.application.port.FinalizationInputFact;
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

/** 验证内存参考实现的唯一事实、幂等重放、冲突保留和查询契约。 */
class InMemoryDurableInputFactStoreTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-21T02:03:04Z");
    private static final String MEETING_ID = "mtg_durable_input";
    private static final String INPUT_BINDING = "input-binding-sensitive";
    private static final String CONTENT_DIGEST = "sha256-content-digest-sensitive";

    @Test
    void firstWriteIsAppliedAndCanBeQueried() {
        InMemoryDurableInputFactStore store = new InMemoryDurableInputFactStore();
        FinalizationInputFact fact = fact(5, 1200, CONTENT_DIGEST);

        DurableInputFactStore.PersistResult result = store.persist(fact);

        assertEquals(DurableInputFactStore.PersistStatus.APPLIED, result.status());
        assertEquals(fact, result.fact());
        assertEquals(Optional.of(fact), store.find(MEETING_ID));
    }

    @Test
    void sameFactIsReplayedWithoutReplacingTheStoredWinner() {
        InMemoryDurableInputFactStore store = new InMemoryDurableInputFactStore();
        FinalizationInputFact first = fact(5, 1200, CONTENT_DIGEST);
        FinalizationInputFact equalButDistinct = fact(5, 1200, CONTENT_DIGEST);

        assertEquals(DurableInputFactStore.PersistStatus.APPLIED, store.persist(first).status());
        DurableInputFactStore.PersistResult replay = store.persist(equalButDistinct);

        assertEquals(DurableInputFactStore.PersistStatus.REPLAYED, replay.status());
        assertEquals(first, replay.fact());
        assertEquals(first, store.find(MEETING_ID).orElseThrow());
    }

    @Test
    void differentFactConflictsAndLeavesOriginalFactUntouched() {
        InMemoryDurableInputFactStore store = new InMemoryDurableInputFactStore();
        FinalizationInputFact first = fact(5, 1200, CONTENT_DIGEST);
        FinalizationInputFact conflicting = fact(6, 1200, "sha256-other-digest");

        store.persist(first);
        DurableInputFactStore.PersistResult result = store.persist(conflicting);

        assertEquals(DurableInputFactStore.PersistStatus.CONFLICT, result.status());
        assertEquals(first, result.fact());
        assertEquals(first, store.find(MEETING_ID).orElseThrow());
        assertFalse(store.find(MEETING_ID).orElseThrow().equals(conflicting));
    }

    @Test
    void concurrentSameFactWritesHaveOneWinnerAndStableReplays() throws Exception {
        InMemoryDurableInputFactStore store = new InMemoryDurableInputFactStore();
        FinalizationInputFact candidate = fact(8, 2400, CONTENT_DIGEST);
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<DurableInputFactStore.PersistResult>> futures = new ArrayList<>();
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
            for (Future<DurableInputFactStore.PersistResult> future : futures) {
                DurableInputFactStore.PersistResult result = future.get();
                if (result.status() == DurableInputFactStore.PersistStatus.APPLIED) {
                    applied++;
                } else {
                    assertEquals(DurableInputFactStore.PersistStatus.REPLAYED, result.status());
                    replayed++;
                }
                assertEquals(candidate, result.fact());
            }
            assertEquals(1, applied);
            assertEquals(workers - 1, replayed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidQueriesAndResultsFailClosed() {
        InMemoryDurableInputFactStore store = new InMemoryDurableInputFactStore();

        assertThrows(IllegalArgumentException.class, () -> store.find(null));
        assertThrows(IllegalArgumentException.class, () -> store.find(" "));
        assertThrows(NullPointerException.class, () -> store.persist(null));
        assertThrows(NullPointerException.class,
                () -> new DurableInputFactStore.PersistResult(null, fact(0, 0, CONTENT_DIGEST)));
        assertThrows(NullPointerException.class,
                () -> new DurableInputFactStore.PersistResult(
                        DurableInputFactStore.PersistStatus.APPLIED, null));
    }

    @Test
    void diagnosticsDoNotExposeMeetingBindingDigestOrReceipt() {
        InMemoryDurableInputFactStore store = new InMemoryDurableInputFactStore();
        DurableInputFactStore.PersistResult result = store.persist(fact(1, 100, CONTENT_DIGEST));
        String text = result + " " + result.fact();

        assertFalse(text.contains(MEETING_ID));
        assertFalse(text.contains(INPUT_BINDING));
        assertFalse(text.contains(CONTENT_DIGEST));
        assertFalse(text.contains("receipt-sensitive-value"));
        assertEquals("DurableInputFactStore.PersistResult[status=APPLIED, fact=redacted]",
                result.toString());
    }

    private static FinalizationInputFact fact(
            long sequence, long duration, String digest) {
        return new FinalizationInputFact(
                MEETING_ID,
                INPUT_BINDING,
                sequence,
                duration,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                digest);
    }
}
