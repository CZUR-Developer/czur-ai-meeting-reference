package com.czur.reference.application.command;

import com.czur.reference.application.port.CreateRealtimeSessionAllocator;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参考会话的进程内稳定身份、并发唯一分配及日志脱敏。 */
class ReferenceCreateRealtimeSessionAllocatorTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void simultaneousCallsForSameIdentityAllocateOnlyOneTokenAndExpiry() throws Exception {
        var random = new CountingRandom();
        var allocator = allocator(random);
        var key = request("deployment", "subject", "mtg_one", "reservation-one");
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<java.util.concurrent.Future<
                    CreateMeetingResponseFact.RealtimeSession>>();
            for (int i = 0; i < 16; i++) {
                calls.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return allocator.allocate(key);
                }));
            }
            start.countDown();
            var first = calls.getFirst().get(5, TimeUnit.SECONDS);
            for (var call : calls) {
                var actual = call.get(5, TimeUnit.SECONDS);
                assertEquals(first.sessionToken(), actual.sessionToken());
                assertEquals(first.expiresAt(), actual.expiresAt());
                assertEquals(first.url(), actual.url());
            }
            assertEquals(1, random.calls.get());
            assertEquals(NOW.plusSeconds(60), first.expiresAt());
            assertEquals(URI.create("wss://example.test/v1/realtime/mtg_one"), first.url());
            assertEquals(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, first.subprotocol());
            assertFalse(key.toString().contains("reservation-one"));
            assertFalse(first.toString().contains(first.sessionToken()));
            assertFalse(first.toString().contains("example.test"));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void meetingSubjectReservationAndDeploymentAllParticipateInIdentity() {
        var random = new CountingRandom();
        var allocator = allocator(random);
        var keys = List.of(
                request("deployment", "subject", "mtg_one", "reservation-one"),
                request("deployment", "subject", "mtg_one", "reservation-two"),
                request("deployment", "another-subject", "mtg_one", "reservation-one"),
                request("other-deployment", "subject", "mtg_one", "reservation-one"),
                request("deployment", "subject", "mtg_two", "reservation-one"));
        var tokens = keys.stream().map(allocator::allocate)
                .map(CreateMeetingResponseFact.RealtimeSession::sessionToken)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(5, tokens.size());
        assertEquals(5, random.calls.get());
        assertNotEquals(allocator.allocate(keys.getFirst()).sessionToken(),
                allocator.allocate(keys.get(1)).sessionToken());
        assertEquals(URI.create("wss://example.test/v1/realtime/mtg_one"),
                allocator.allocate(keys.getFirst()).url());
        assertEquals(URI.create("wss://example.test/v1/realtime/mtg_two"),
                allocator.allocate(keys.getLast()).url());
        assertEquals(5, random.calls.get());
    }

    @Test
    void expiredUncommittedCandidateGetsNewTokenWithoutInvalidatingOtherIdentity() {
        var clock = new MutableClock(NOW);
        var random = new CountingRandom();
        var allocator = new ReferenceCreateRealtimeSessionAllocator(
                URI.create("wss://example.test"), Duration.ofSeconds(60),
                clock, random);
        var firstKey = request("deployment", "subject", "mtg_one", "reservation-one");
        var otherKey = request("deployment", "subject", "mtg_two", "reservation-two");

        var first = allocator.allocate(firstKey);
        clock.advance(Duration.ofSeconds(30));
        var other = allocator.allocate(otherKey);
        clock.advance(Duration.ofSeconds(30));
        var renewedCandidate = allocator.allocate(firstKey);

        assertNotEquals(first.sessionToken(), renewedCandidate.sessionToken());
        assertEquals(NOW.plusSeconds(120), renewedCandidate.expiresAt());
        assertEquals(other, allocator.allocate(otherKey));
        assertEquals(3, random.calls.get());
    }

    @Test
    void sixtyFourthCallReapsExpiredMappingAndKeepsLiveMapping() throws Exception {
        var clock = new MutableClock(NOW);
        var random = new CountingRandom();
        var allocator = new ReferenceCreateRealtimeSessionAllocator(
                URI.create("wss://example.test"), Duration.ofSeconds(60),
                clock, random);
        var expiredKey = request("deployment", "subject", "mtg_old", "reservation-old");
        var liveKey = request("deployment", "subject", "mtg_live", "reservation-live");
        allocator.allocate(expiredKey); // 第 1 次；到 t=60 秒过期。
        clock.advance(Duration.ofSeconds(30));
        var live = allocator.allocate(liveKey); // 第 2 次；到 t=90 秒过期。
        clock.advance(Duration.ofSeconds(31));

        // 只观察本地缓存回收，不把该缓存误当成已持久化的会话授权来源。
        var field = ReferenceCreateRealtimeSessionAllocator.class.getDeclaredField("sessions");
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(allocator);
        for (int call = 3; call <= 63; call++) {
            assertEquals(live, allocator.allocate(liveKey));
        }
        assertEquals(2, cache.size());
        assertTrue(cache.containsKey(expiredKey));

        assertEquals(live, allocator.allocate(liveKey)); // 第 64 次触发惰性清理。
        assertEquals(1, cache.size());
        assertFalse(cache.containsKey(expiredKey));
        assertTrue(cache.containsKey(liveKey));
        assertEquals(2, random.calls.get());
    }

    @Test
    void rejectsUnsafeEndpointAndNonpositiveTtl() {
        var random = new CountingRandom();
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceCreateRealtimeSessionAllocator(
                        URI.create("ws://example.test"), Duration.ofSeconds(60),
                        clock(), random));
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceCreateRealtimeSessionAllocator(
                        URI.create("wss://example.test?token=secret"),
                        Duration.ofSeconds(60), clock(), random));
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceCreateRealtimeSessionAllocator(
                        URI.create("wss://example.test/another-path"),
                        Duration.ofSeconds(60), clock(), random));
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceCreateRealtimeSessionAllocator(
                        URI.create("wss://example.test"), Duration.ZERO,
                        clock(), random));
        assertThrows(IllegalArgumentException.class, () ->
                request("deployment", "subject", "mtg_one/escape", "reservation-one"));
    }

    @Test
    void expiryOverflowFailsWithoutIssuingToken() {
        var random = new CountingRandom();
        var allocator = new ReferenceCreateRealtimeSessionAllocator(
                URI.create("wss://example.test"), Duration.ofSeconds(60),
                Clock.fixed(Instant.MAX, ZoneOffset.UTC), random);
        assertThrows(IllegalStateException.class, () -> allocator.allocate(
                request("deployment", "subject", "mtg_one", "reservation-one")));
        assertEquals(0, random.calls.get());
    }

    private static CreateRealtimeSessionAllocator.Request request(
            String deployment, String subject, String meeting, String reservation) {
        return new CreateRealtimeSessionAllocator.Request(
                deployment, subject, meeting, reservation);
    }

    private static ReferenceCreateRealtimeSessionAllocator allocator(SecureRandom random) {
        return new ReferenceCreateRealtimeSessionAllocator(
                URI.create("wss://example.test"), Duration.ofSeconds(60),
                clock(), random);
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** 不输出敏感随机字节，只统计真正的签发次数。 */
    private static final class CountingRandom extends SecureRandom {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void nextBytes(byte[] bytes) {
            int generation = calls.incrementAndGet();
            java.util.Arrays.fill(bytes, (byte) generation);
        }
    }

    /** 仅用于精确跨越 Token 到期边界，不等待真实时间。 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
