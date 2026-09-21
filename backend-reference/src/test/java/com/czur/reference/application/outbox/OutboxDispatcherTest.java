package com.czur.reference.application.outbox;

import com.czur.reference.application.persistence.InMemoryOutboxStore;
import com.czur.reference.application.port.OutboxStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T14-05 Dispatcher 的内存端口定向门禁。 */
class OutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void onlyExplicitlyRegisteredTypesAreClaimedAndPublished() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        append(store, "known", "KnownEvent");
        append(store, "unknown", "UnknownEvent");
        AtomicInteger handled = new AtomicInteger();

        OutboxDispatcher dispatcher = new OutboxDispatcher(
                store,
                List.of(new OutboxDispatcher.HandlerRegistration(
                        "KnownEvent", (event, context) -> handled.incrementAndGet())),
                config(3));

        OutboxDispatcher.RunResult result = dispatcher.runOnce();

        assertEquals(1, result.claimedCount());
        assertEquals(OutboxDispatcher.ItemStatus.PUBLISHED, result.items().get(0).status());
        assertEquals(1, handled.get());
        assertEquals(OutboxStore.EventStatus.PUBLISHED, store.find("known").orElseThrow().status());
        assertEquals(OutboxStore.EventStatus.PENDING, store.find("unknown").orElseThrow().status());
    }

    @Test
    void handlerExceptionUsesFixedRetryAndThenDeadLetter() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        append(store, "retry", "RetryEvent");
        AtomicInteger handled = new AtomicInteger();
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                store,
                List.of(new OutboxDispatcher.HandlerRegistration(
                        "RetryEvent", (event, context) -> {
                            handled.incrementAndGet();
                            throw new IllegalStateException("secret response must not be persisted");
                        })),
                config(2));

        OutboxDispatcher.RunResult first = dispatcher.runOnce();
        OutboxDispatcher.RunResult second = dispatcher.runOnce();

        assertEquals(OutboxDispatcher.ItemStatus.RETRY_SCHEDULED, first.items().get(0).status());
        assertEquals("HANDLER_EXCEPTION", first.items().get(0).reasonCode());
        assertEquals(OutboxDispatcher.ItemStatus.DEAD, second.items().get(0).status());
        assertEquals(OutboxStore.EventStatus.DEAD, store.find("retry").orElseThrow().status());
        assertEquals(2, handled.get());
        assertFalse(store.find("retry").orElseThrow().lastError().contains("secret"));
    }

    @Test
    void renewFailureHasExplicitLeaseLostResultAndDoesNotAcknowledge() {
        InMemoryOutboxStore delegate = new InMemoryOutboxStore();
        append(delegate, "renew-loss", "RenewEvent");
        RenewFailingStore store = new RenewFailingStore(delegate);
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                store,
                List.of(new OutboxDispatcher.HandlerRegistration(
                        "RenewEvent", (event, context) -> {
                            assertEquals(OutboxStore.RenewStatus.LEASE_EXPIRED, context.renew().status());
                        })),
                config(3));

        OutboxDispatcher.RunResult result = dispatcher.runOnce();

        assertEquals(OutboxDispatcher.ItemStatus.LEASE_LOST, result.items().get(0).status());
        assertEquals("RENEW_FAILED", result.items().get(0).reasonCode());
        assertEquals(0, store.publishCalls);
        assertEquals(OutboxStore.EventStatus.LEASED, delegate.find("renew-loss").orElseThrow().status());
    }

    @Test
    void renewPersistenceFailureIsReThrownWithoutConsumingAttempt() {
        InMemoryOutboxStore delegate = new InMemoryOutboxStore();
        append(delegate, "renew-storage-failure", "RenewStorageEvent");
        RuntimeException storageFailure = new IllegalStateException("database unavailable");
        RenewThrowingStore store = new RenewThrowingStore(delegate, storageFailure);
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                store,
                List.of(new OutboxDispatcher.HandlerRegistration(
                        "RenewStorageEvent", (event, context) -> context.renew())),
                config(3));

        RuntimeException thrown = assertThrows(RuntimeException.class, dispatcher::runOnce);

        assertSame(storageFailure, thrown);
        assertEquals(0, store.failureCalls);
        OutboxStore.OutboxEvent event = delegate.find("renew-storage-failure").orElseThrow();
        assertEquals(OutboxStore.EventStatus.LEASED, event.status());
        assertEquals(0, event.attemptCount());
    }

    @Test
    void swallowedRenewPersistenceFailureIsStillReThrownAfterHandlerReturns() {
        InMemoryOutboxStore delegate = new InMemoryOutboxStore();
        append(delegate, "swallowed-renew-failure", "SwallowedRenewEvent");
        RuntimeException storageFailure = new IllegalStateException("database unavailable");
        RenewThrowingStore store = new RenewThrowingStore(delegate, storageFailure);
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                store,
                List.of(new OutboxDispatcher.HandlerRegistration(
                        "SwallowedRenewEvent", (event, context) -> {
                            try {
                                context.renew();
                            } catch (RuntimeException expected) {
                                assertSame(storageFailure, expected);
                            }
                        })),
                config(3));

        RuntimeException thrown = assertThrows(RuntimeException.class, dispatcher::runOnce);

        assertSame(storageFailure, thrown);
        assertEquals(0, store.failureCalls);
        assertEquals(0, delegate.find("swallowed-renew-failure").orElseThrow().attemptCount());
    }

    @Test
    void publishFenceFailureHasExplicitLeaseLostResult() {
        InMemoryOutboxStore delegate = new InMemoryOutboxStore();
        append(delegate, "publish-loss", "PublishEvent");
        PublishFailingStore store = new PublishFailingStore(delegate);
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                store,
                List.of(new OutboxDispatcher.HandlerRegistration(
                        "PublishEvent", (event, context) -> { })),
                config(3));

        OutboxDispatcher.RunResult result = dispatcher.runOnce();

        assertEquals(OutboxDispatcher.ItemStatus.LEASE_LOST, result.items().get(0).status());
        assertEquals(OutboxStore.PublishStatus.NOT_OWNER.name(), result.items().get(0).reasonCode());
    }

    @Test
    void duplicateRegistrationIsRejectedAndDispatcherDoesNotStartThreads() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxDispatcher.Handler handler = (event, context) -> { };
        assertThrows(IllegalArgumentException.class, () -> new OutboxDispatcher(
                store,
                List.of(
                        new OutboxDispatcher.HandlerRegistration("Same", handler),
                        new OutboxDispatcher.HandlerRegistration("Same", handler)),
                config(3)));
    }

    private static OutboxDispatcher.Config config(int maxAttempts) {
        return new OutboxDispatcher.Config(
                "dispatcher-test",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30),
                Duration.ZERO,
                10,
                maxAttempts);
    }

    private static void append(InMemoryOutboxStore store, String id, String type) {
        store.append(OutboxStore.OutboxEvent.pending(
                id, "meeting", "meeting-1", type, "{}", NOW, NOW));
    }

    private abstract static class DelegatingStore implements OutboxStore {
        protected final OutboxStore delegate;

        private DelegatingStore(OutboxStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AppendResult append(OutboxEvent event) { return delegate.append(event); }

        @Override
        public Optional<OutboxEvent> find(String eventId) { return delegate.find(eventId); }

        @Override
        public List<ClaimedEvent> claim(ClaimRequest request) { return delegate.claim(request); }

        @Override
        public RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
            return delegate.renew(lease, renewedAt, leaseDuration);
        }

        @Override
        public PublishResult markPublished(LeaseToken lease, Instant publishedAt) {
            return delegate.markPublished(lease, publishedAt);
        }

        @Override
        public FailureResult markFailed(
                LeaseToken lease, Instant failedAt, Instant nextAvailableAt, String error, int maxAttempts) {
            return delegate.markFailed(lease, failedAt, nextAvailableAt, error, maxAttempts);
        }
    }

    private static final class RenewFailingStore extends DelegatingStore {
        private int publishCalls;

        private RenewFailingStore(OutboxStore delegate) { super(delegate); }

        @Override
        public RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
            OutboxEvent event = delegate.find(lease.eventId()).orElseThrow();
            return new RenewResult(RenewStatus.LEASE_EXPIRED, event, null);
        }

        @Override
        public PublishResult markPublished(LeaseToken lease, Instant publishedAt) {
            publishCalls++;
            return super.markPublished(lease, publishedAt);
        }
    }

    private static final class RenewThrowingStore extends DelegatingStore {
        private final RuntimeException failure;
        private int failureCalls;

        private RenewThrowingStore(OutboxStore delegate, RuntimeException failure) {
            super(delegate);
            this.failure = failure;
        }

        @Override
        public RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
            throw failure;
        }

        @Override
        public FailureResult markFailed(
                LeaseToken lease, Instant failedAt, Instant nextAvailableAt, String error, int maxAttempts) {
            failureCalls++;
            return super.markFailed(lease, failedAt, nextAvailableAt, error, maxAttempts);
        }
    }

    private static final class PublishFailingStore extends DelegatingStore {
        private PublishFailingStore(OutboxStore delegate) { super(delegate); }

        @Override
        public PublishResult markPublished(LeaseToken lease, Instant publishedAt) {
            OutboxEvent event = delegate.find(lease.eventId()).orElseThrow();
            return new PublishResult(PublishStatus.NOT_OWNER, event);
        }
    }
}
