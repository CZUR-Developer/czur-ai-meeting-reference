package com.czur.reference.application.persistence;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 删除计划端口的纯 Java 首次、重放、冲突和并发契约。 */
class InMemoryArtifactDeletionPlanStoreTest {
    private static final String MEETING = "mtg_plan_store_contract";
    private static final String CHECKSUM = "sha256:" + "a".repeat(64);

    @Test
    void firstRegistrationReplayAndConflictKeepOriginalWinner() {
        InMemoryArtifactDeletionPlanStore store = new InMemoryArtifactDeletionPlanStore();
        ArtifactDeletionPlan first = plan("artifact-a", 1);
        ArtifactDeletionPlan changed = plan("artifact-b", 1);

        assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                store.register(first).status());
        assertEquals(ArtifactDeletionPlanStore.PersistStatus.REPLAYED,
                store.register(first).status());
        ArtifactDeletionPlanStore.PersistResult conflict = store.register(changed);
        assertEquals(ArtifactDeletionPlanStore.PersistStatus.CONFLICT, conflict.status());
        assertEquals(first, conflict.plan().plan());
        assertEquals(first, store.find(MEETING).orElseThrow().plan());
    }

    @Test
    void concurrentSameCandidateHasOneAppliedAndOneReplay() throws Exception {
        InMemoryArtifactDeletionPlanStore store = new InMemoryArtifactDeletionPlanStore();
        ArtifactDeletionPlan candidate = plan("artifact-concurrent", 3);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return store.register(candidate).status();
            });
            var second = executor.submit(() -> {
                start.await();
                return store.register(candidate).status();
            });
            start.countDown();
            assertTrue(List.of(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    ArtifactDeletionPlanStore.PersistStatus.REPLAYED)
                    .contains(first.get()) && List.of(
                    ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    ArtifactDeletionPlanStore.PersistStatus.REPLAYED)
                    .contains(second.get()));
            assertTrue(first.get() != second.get());
        }
    }

    private static ArtifactDeletionPlan plan(String artifactId, int revision) {
        WriteIntentSpec spec = new WriteIntentSpec(
                artifactId, MEETING, "transcript.final", "application/json", "zh-CN",
                revision, 12, CHECKSUM);
        return new ArtifactDeletionPlan(MEETING,
                Instant.parse("2026-09-29T08:00:00.123456Z"), null, null,
                List.of(ArtifactDeletionTarget.fromSpec(spec)));
    }
}
