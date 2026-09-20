package com.czur.reference.application.port;

import com.czur.reference.application.persistence.InMemoryMeetingFinalizationStore;
import com.czur.reference.application.persistence.InMemoryMeetingRepository;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeetingFinalizationStoreContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-20T01:00:00Z");
    private static final Instant STOPPED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant ACCEPTED_AT = STOPPED_AT.plusSeconds(30);
    private static final String RECEIPT = "receipt-sensitive-value";

    @Test
    void stopIntentPendingAndAcceptedAreSeparateStableFacts() {
        InMemoryMeetingRepository meetings = new InMemoryMeetingRepository();
        meetings.save(running("meeting-stable"));
        AtomicInteger generated = new AtomicInteger();
        var store = new InMemoryMeetingFinalizationStore(meetings, () -> {
            generated.incrementAndGet();
            return RECEIPT;
        });

        MeetingFinalizationStore.Found open = assertInstanceOf(
                MeetingFinalizationStore.Found.class, store.find("meeting-stable"));
        assertEquals(InputState.INPUT_OPEN, open.fact().inputState());
        assertEquals(null, open.fact().finalizationReceipt());
        assertInstanceOf(MeetingFinalizationStore.Conflict.class,
                store.requestInputCompletion("meeting-stable"),
                "未进入 STOPPING 前不能伪造输入完成意图");

        MeetingAggregate stopped = MeetingAggregate.fromSnapshot(
                meetings.find("meeting-stable").orElseThrow());
        stopped.apply(MeetingEvent.STOP_REQUESTED, STOPPED_AT);
        meetings.save(stopped.snapshot());

        var pending = assertInstanceOf(MeetingFinalizationStore.Applied.class,
                store.requestInputCompletion("meeting-stable"));
        assertEquals(InputState.INPUT_COMPLETE_PENDING, pending.fact().inputState());
        assertEquals(null, pending.fact().finalizationReceipt());
        assertInstanceOf(MeetingFinalizationStore.Replayed.class,
                store.requestInputCompletion("meeting-stable"));
        assertEquals(0, generated.get(), "Stop 只能写 pending，不能生成 receipt");

        var accepted = assertInstanceOf(MeetingFinalizationStore.Applied.class,
                store.acceptInput("meeting-stable", ACCEPTED_AT));
        assertEquals(InputState.INPUT_ACCEPTED, accepted.fact().inputState());
        assertEquals(RECEIPT, accepted.fact().finalizationReceipt());
        assertEquals(MeetingState.FINALIZING,
                meetings.find("meeting-stable").orElseThrow().state());

        var replay = assertInstanceOf(MeetingFinalizationStore.Replayed.class,
                store.acceptInput("meeting-stable", ACCEPTED_AT.plusSeconds(1)));
        assertEquals(RECEIPT, replay.fact().finalizationReceipt());
        assertEquals(1, generated.get(), "Worker 重跑不能重新生成 receipt");

        for (String text : List.of(
                accepted.toString(), replay.toString(), accepted.fact().toString())) {
            assertFalse(text.contains(RECEIPT), "诊断文本不得输出 receipt 正文");
        }
    }

    @Test
    void invalidTimeAndMissingMeetingDoNotGenerateReceipt() {
        InMemoryMeetingRepository meetings = new InMemoryMeetingRepository();
        meetings.save(stopping("meeting-time"));
        AtomicInteger generated = new AtomicInteger();
        var store = new InMemoryMeetingFinalizationStore(meetings, () -> {
            generated.incrementAndGet();
            return RECEIPT;
        });

        assertInstanceOf(MeetingFinalizationStore.NotFound.class,
                store.find("meeting-missing"));
        assertInstanceOf(MeetingFinalizationStore.NotFound.class,
                store.requestInputCompletion("meeting-missing"));
        store.requestInputCompletion("meeting-time");
        var conflict = assertInstanceOf(MeetingFinalizationStore.Conflict.class,
                store.acceptInput("meeting-time", STOPPED_AT.minusNanos(1)));
        assertEquals(MeetingFinalizationStore.ConflictReason.FACT_TIME_BEFORE_CURRENT,
                conflict.reason());
        assertEquals(0, generated.get());
    }

    @Test
    void concurrentWorkerRerunsPublishExactlyOneReceipt() throws Exception {
        InMemoryMeetingRepository meetings = new InMemoryMeetingRepository();
        meetings.save(stopping("meeting-concurrent"));
        AtomicInteger generated = new AtomicInteger();
        var store = new InMemoryMeetingFinalizationStore(meetings, () -> {
            int sequence = generated.incrementAndGet();
            return RECEIPT + '-' + sequence;
        });
        store.requestInputCompletion("meeting-concurrent");

        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<MeetingFinalizationStore.Result>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.acceptInput("meeting-concurrent", ACCEPTED_AT);
                }));
            }
            ready.await();
            start.countDown();
            int applied = 0;
            for (Future<MeetingFinalizationStore.Result> future : futures) {
                MeetingFinalizationStore.Result result = future.get();
                if (result instanceof MeetingFinalizationStore.Applied) {
                    applied++;
                } else {
                    assertInstanceOf(MeetingFinalizationStore.Replayed.class, result);
                }
            }
            assertEquals(1, applied);
            assertEquals(1, generated.get());
            var found = assertInstanceOf(MeetingFinalizationStore.Found.class,
                    store.find("meeting-concurrent"));
            assertTrue(found.fact().isAccepted());
            assertEquals(RECEIPT + "-1", found.fact().finalizationReceipt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void receiptCollisionAcrossMeetingsFailsBeforePublishingPartialFact() {
        InMemoryMeetingRepository meetings = new InMemoryMeetingRepository();
        meetings.save(stopping("meeting-collision-a"));
        meetings.save(stopping("meeting-collision-b"));
        var store = new InMemoryMeetingFinalizationStore(meetings, () -> RECEIPT);
        store.requestInputCompletion("meeting-collision-a");
        store.requestInputCompletion("meeting-collision-b");

        assertInstanceOf(MeetingFinalizationStore.Applied.class,
                store.acceptInput("meeting-collision-a", ACCEPTED_AT));
        IllegalStateException collision = assertThrows(IllegalStateException.class,
                () -> store.acceptInput("meeting-collision-b", ACCEPTED_AT));
        assertFalse(collision.getMessage().contains(RECEIPT));

        var second = assertInstanceOf(MeetingFinalizationStore.Found.class,
                store.find("meeting-collision-b"));
        assertEquals(InputState.INPUT_COMPLETE_PENDING, second.fact().inputState());
        assertEquals(MeetingState.STOPPING,
                meetings.find("meeting-collision-b").orElseThrow().state());
    }

    private static MeetingAggregate.MeetingSnapshot running(String meetingId) {
        MeetingAggregate aggregate = MeetingAggregate.legacyUnowned(
                meetingId, "deployment", "session-" + meetingId,
                "profile", 1, CREATED_AT);
        aggregate.apply(MeetingEvent.AUTHORIZE, CREATED_AT.plusSeconds(1));
        aggregate.apply(MeetingEvent.START, CREATED_AT.plusSeconds(2));
        aggregate.apply(MeetingEvent.START, CREATED_AT.plusSeconds(3));
        return aggregate.snapshot();
    }

    private static MeetingAggregate.MeetingSnapshot stopping(String meetingId) {
        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(running(meetingId));
        aggregate.apply(MeetingEvent.STOP_REQUESTED, STOPPED_AT);
        return aggregate.snapshot();
    }
}
