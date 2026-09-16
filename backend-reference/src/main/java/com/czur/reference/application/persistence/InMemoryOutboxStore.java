package com.czur.reference.application.persistence;

import com.czur.reference.application.port.OutboxStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 线程安全的内存 Outbox 适配器，仅用于参考后端演示和单元测试。
 *
 * <p>所有状态迁移在同一把锁内完成，因此可以验证单进程中的幂等和 lease
 * 语义。该类不提供进程重启后的恢复能力，也不能作为生产事实源；生产
 * 实现必须将同样的条件更新映射到数据库，并把聚合状态和 Outbox 追加
 * 放在同一事务中。</p>
 */
public final class InMemoryOutboxStore implements OutboxStore {
    private static final Logger LOGGER = Logger.getLogger(InMemoryOutboxStore.class.getName());
    private static final Comparator<OutboxEvent> CLAIM_ORDER = Comparator
            .comparing(OutboxEvent::availableAt)
            .thenComparing(OutboxEvent::occurredAt)
            .thenComparing(OutboxEvent::eventId);

    private final Object mutex = new Object();
    private final Map<String, OutboxEvent> events = new HashMap<>();
    private final Map<String, LeaseToken> activeLeases = new HashMap<>();

    @Override
    public AppendResult append(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.status() != EventStatus.PENDING
                || event.attemptCount() != 0
                || event.leaseOwner() != null
                || event.leaseUntil() != null
                || event.publishedAt() != null) {
            throw new IllegalArgumentException(
                    "append requires a PENDING event with no attempt, lease or publication timestamp");
        }
        synchronized (mutex) {
            OutboxEvent existing = events.get(event.eventId());
            if (existing == null) {
                events.put(event.eventId(), event);
                LOGGER.log(Level.INFO, "Outbox 事件已追加 eventId={0} eventType={1}",
                        new Object[]{event.eventId(), event.eventType()});
                return new AppendResult(AppendStatus.APPENDED, event);
            }
            if (existing.sameMessage(event)) {
                LOGGER.log(Level.FINE, "Outbox 事件幂等重放 eventId={0}", event.eventId());
                return new AppendResult(AppendStatus.REPLAYED, existing);
            }
            LOGGER.log(Level.WARNING, "Outbox eventId 内容冲突 eventId={0}", event.eventId());
            return new AppendResult(AppendStatus.CONFLICT, existing);
        }
    }

    @Override
    public Optional<OutboxEvent> find(String eventId) {
        requireText("eventId", eventId);
        synchronized (mutex) {
            return Optional.ofNullable(events.get(eventId));
        }
    }

    @Override
    public List<ClaimedEvent> claim(String leaseOwner, Instant now, Duration leaseDuration, int limit) {
        requireText("leaseOwner", leaseOwner);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Instant leaseUntil = now.plus(leaseDuration);
        synchronized (mutex) {
            List<OutboxEvent> candidates = events.values().stream()
                    .filter(event -> isClaimable(event, now))
                    .sorted(CLAIM_ORDER)
                    .limit(limit)
                    .toList();
            List<ClaimedEvent> claimed = new ArrayList<>(candidates.size());
            for (OutboxEvent event : candidates) {
                LeaseToken lease = new LeaseToken(
                        event.eventId(), leaseOwner, UUID.randomUUID().toString(), leaseUntil, now);
                OutboxEvent leased = new OutboxEvent(
                        event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(), event.payload(),
                        event.occurredAt(), event.availableAt(), EventStatus.LEASED, event.attemptCount(),
                        leaseOwner, leaseUntil, null, event.lastError());
                events.put(event.eventId(), leased);
                activeLeases.put(event.eventId(), lease);
                claimed.add(new ClaimedEvent(leased, lease));
            }
            if (!claimed.isEmpty()) {
                LOGGER.log(Level.FINE, "Outbox 事件已领取 owner={0} count={1}",
                        new Object[]{leaseOwner, claimed.size()});
            }
            return List.copyOf(claimed);
        }
    }

    @Override
    public PublishResult markPublished(LeaseToken lease, Instant publishedAt) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(publishedAt, "publishedAt");
        synchronized (mutex) {
            OutboxEvent current = events.get(lease.eventId());
            if (current == null) {
                return new PublishResult(PublishStatus.NOT_FOUND, null);
            }
            if (current.status() == EventStatus.PUBLISHED) {
                return new PublishResult(PublishStatus.ALREADY_PUBLISHED, current);
            }
            if (current.status() == EventStatus.DEAD) {
                return new PublishResult(PublishStatus.ALREADY_DEAD, current);
            }
            PublishStatus ownership = verifyLease(current, lease, publishedAt);
            if (ownership != PublishStatus.PUBLISHED) {
                LOGGER.log(Level.WARNING, "Outbox 发布未获授权 eventId={0} status={1}",
                        new Object[]{lease.eventId(), ownership});
                return new PublishResult(ownership, current);
            }
            OutboxEvent published = new OutboxEvent(
                    current.eventId(), current.aggregateType(), current.aggregateId(), current.eventType(),
                    current.payload(), current.occurredAt(), current.availableAt(), EventStatus.PUBLISHED,
                    current.attemptCount(), null, null, publishedAt, null);
            events.put(current.eventId(), published);
            activeLeases.remove(current.eventId());
            LOGGER.log(Level.INFO, "Outbox 事件已发布 eventId={0} attempt={1}",
                    new Object[]{current.eventId(), current.attemptCount()});
            return new PublishResult(PublishStatus.PUBLISHED, published);
        }
    }

    @Override
    public FailureResult markFailed(
            LeaseToken lease,
            Instant failedAt,
            Instant nextAvailableAt,
            String error,
            int maxAttempts) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(nextAvailableAt, "nextAvailableAt");
        requireText("error", error);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (nextAvailableAt.isBefore(failedAt)) {
            throw new IllegalArgumentException("nextAvailableAt cannot be before failedAt");
        }
        synchronized (mutex) {
            OutboxEvent current = events.get(lease.eventId());
            if (current == null) {
                return new FailureResult(FailureStatus.NOT_FOUND, null);
            }
            if (current.status() == EventStatus.PUBLISHED) {
                return new FailureResult(FailureStatus.ALREADY_PUBLISHED, current);
            }
            if (current.status() == EventStatus.DEAD) {
                return new FailureResult(FailureStatus.ALREADY_DEAD, current);
            }
            PublishStatus ownership = verifyLease(current, lease, failedAt);
            if (ownership != PublishStatus.PUBLISHED) {
                FailureStatus status = switch (ownership) {
                    case LEASE_EXPIRED -> FailureStatus.LEASE_EXPIRED;
                    case NOT_OWNER -> FailureStatus.NOT_OWNER;
                    case NOT_FOUND -> FailureStatus.NOT_FOUND;
                    default -> throw new IllegalStateException("Unexpected lease status: " + ownership);
                };
                LOGGER.log(Level.WARNING, "Outbox 失败回报未获授权 eventId={0} status={1}",
                        new Object[]{lease.eventId(), status});
                return new FailureResult(status, current);
            }
            int nextAttempt = current.attemptCount() + 1;
            boolean dead = nextAttempt >= maxAttempts;
            OutboxEvent updated = new OutboxEvent(
                    current.eventId(), current.aggregateType(), current.aggregateId(), current.eventType(),
                    current.payload(), current.occurredAt(), nextAvailableAt,
                    dead ? EventStatus.DEAD : EventStatus.PENDING,
                    nextAttempt, null, null, null, error);
            events.put(current.eventId(), updated);
            activeLeases.remove(current.eventId());
            FailureStatus status = dead ? FailureStatus.DEAD : FailureStatus.RETRY_SCHEDULED;
            LOGGER.log(dead ? Level.WARNING : Level.INFO,
                    dead ? "Outbox 事件进入死信 eventId={0} attempt={1}"
                            : "Outbox 事件安排重试 eventId={0} attempt={1}",
                    new Object[]{current.eventId(), nextAttempt});
            return new FailureResult(status, updated);
        }
    }

    private boolean isClaimable(OutboxEvent event, Instant now) {
        if (event.availableAt().isAfter(now)) {
            return false;
        }
        return switch (event.status()) {
            case PENDING -> true;
            case LEASED -> event.leaseUntil() == null || !event.leaseUntil().isAfter(now);
            case PUBLISHED, DEAD -> false;
        };
    }

    private PublishStatus verifyLease(OutboxEvent current, LeaseToken requested, Instant at) {
        if (current.status() != EventStatus.LEASED) {
            return PublishStatus.NOT_OWNER;
        }
        LeaseToken active = activeLeases.get(current.eventId());
        if (active == null
                || !active.token().equals(requested.token())
                || !active.owner().equals(requested.owner())
                || !current.leaseOwner().equals(requested.owner())
                || !current.leaseUntil().equals(requested.expiresAt())) {
            return PublishStatus.NOT_OWNER;
        }
        if (at.isBefore(requested.claimedAt()) || !at.isBefore(requested.expiresAt())) {
            return PublishStatus.LEASE_EXPIRED;
        }
        return PublishStatus.PUBLISHED;
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
