package com.czur.reference.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 可靠事件 Outbox 的持久化端口。
 *
 * <p>Outbox 记录与 {@code outbox_event} 表一一对应。应用服务必须在修改
 * 会议状态的同一数据库事务中调用 {@link #append(OutboxEvent)}，提交以后
 * 再由 Dispatcher 领取和发布事件。这样即使进程在提交后立即退出，事件
 * 仍可以从数据库恢复；反过来，消息队列发布成功不能代替本端事实源的
 * 事务提交。</p>
 *
 * <p>事件发布采用至少一次语义。调用方应使用稳定的 {@code eventId} 和
 * 下游幂等键，不能依赖“恰好发布一次”。生产实现必须把 claim、publish
 * 和 fail 映射为数据库的原子条件更新，并在多实例 Worker 之间保证同一
 * 事件在一个未过期 lease 内只有一个 owner。</p>
 */
public interface OutboxStore {

    /**
     * 追加一条待发布事件，并按 {@code eventId} 提供幂等语义。
     *
     * <p>相同 {@code eventId} 且内容完全一致时返回 {@link AppendStatus#REPLAYED}
     * 并复用已存在记录；相同 ID 但 aggregate、事件类型、载荷或发生时间
     * 任一不同，则返回 {@link AppendStatus#CONFLICT}，调用方不得覆盖原事件。
     * 数据库实现应使用 {@code outbox_event.event_id} 主键，并在写入聚合
     * 状态的同一事务中完成插入。</p>
     *
     * @param event 初始状态必须为 {@link EventStatus#PENDING}，且 attemptCount
     *              为 0、没有 lease 和 publishedAt
     * @return 追加、重放或冲突结果及数据库中的事件记录
     */
    AppendResult append(OutboxEvent event);

    /**
     * 查询事件当前快照，主要用于诊断和测试，不应被调用方当作并发锁。
     */
    Optional<OutboxEvent> find(String eventId);

    /**
     * 原子领取当前可发布的事件。
     *
     * <p>可领取条件为 {@code availableAt <= now}，且记录处于 PENDING，或
     * 处于 LEASED 但原 lease 已过期。领取会把记录更新为 LEASED，并返回
     * 一次性的 {@link LeaseToken}。其它 Worker 在 lease 未过期前看不到该
     * 事件；lease 过期后可以重新领取。{@code limit} 是本次批量上限，不是
     * 全局消费者上限。</p>
     *
     * <p>实现必须使用数据库行锁、条件 UPDATE/RETURNING 或等价的原子机制。
     * “先查询再更新”在多实例下会导致重复发布。lease token 不应由调用方
     * 自行构造，完成和失败操作必须携带本次 claim 返回的 token，旧 owner
     * 的迟到操作必须被拒绝。</p>
     *
     * @param leaseOwner Worker 实例稳定且可观测的 owner 标识
     * @param now 当前时间；生产实现应使用数据库时间或时钟偏差可控的时间
     * @param leaseDuration 本次领取的 lease 时长，必须为正
     * @param limit 最多领取的事件数，必须为正
     * @return 已成功加 lease 的事件，顺序由实现保证为确定性顺序
     */
    List<ClaimedEvent> claim(String leaseOwner, Instant now, Duration leaseDuration, int limit);

    /**
     * 以 lease token 标记发布成功。
     *
     * <p>只有当前 owner、token 未过期且记录仍为 LEASED 时才会成功。成功
     * 后记录进入 PUBLISHED，之后永远不会再次被 claim。发布动作本身可能
     * 在数据库提交后重复发生，因此下游仍必须按 eventId 幂等。</p>
     */
    PublishResult markPublished(LeaseToken lease, Instant publishedAt);

    /**
     * 报告本次发布失败并安排重试或死信。
     *
     * <p>每次被当前 lease 合法报告的失败都会将 {@code attemptCount} 加一。
     * 当新次数达到 {@code maxAttempts} 时，记录进入 DEAD 且不再自动领取；
     * 否则回到 PENDING，并在 {@code nextAvailableAt} 之后重新可领。错误
     * 文本只作为脱敏诊断摘要保存，禁止放入密码、Token、原始音频、完整
     * 会议正文或供应商完整响应。</p>
     *
     * <p>过期 lease、错误 owner 或重复处理不会改变记录，调用方应丢弃
     * 该 token 并等待新的 claim。生产实现必须以 owner、token/版本和
     * lease_until 作为条件更新，防止旧 Worker 覆盖新 Worker 的结果。</p>
     *
     * @param lease 当前 claim 返回的 lease token
     * @param failedAt 失败发生时间，不能早于 claim 时间
     * @param nextAvailableAt 下一次允许领取的时间；进入 DEAD 时仍用于记录
     * @param error 脱敏错误摘要
     * @param maxAttempts 最大尝试次数，必须为正
     */
    FailureResult markFailed(
            LeaseToken lease,
            Instant failedAt,
            Instant nextAvailableAt,
            String error,
            int maxAttempts);

    /**
     * 便于调用方按 lease 时长领取事件的值对象。
     *
     * <p>该 token 是不透明的完成凭证；调用方只能原样传回
     * {@link #markPublished(LeaseToken, Instant)} 或
     * {@link #markFailed(LeaseToken, Instant, Instant, String, int)}，不能持久化
     * 它来跨进程恢复。{@code leaseVersion} 来自数据库单调递增的
     * {@code outbox_event.lease_version}；生产完成/失败条件更新必须同时校验
     * owner、token、version 和 lease 到期时间。若数据库只保留 owner/until 字段，
     * 就无法防止同一 owner 重用时旧请求误更新。</p>
     */
    record LeaseToken(String eventId, String owner, String token, Instant expiresAt, Instant claimedAt,
                      long leaseVersion) {
        /**
         * 兼容 P2-T08 之前的内存适配器；未连接数据库时版本使用 0。
         * PostgreSQL 适配器必须使用数据库返回的单调版本构造完整令牌。
         */
        public LeaseToken(String eventId, String owner, String token, Instant expiresAt, Instant claimedAt) {
            this(eventId, owner, token, expiresAt, claimedAt, 0);
        }

        public LeaseToken {
            requireText("eventId", eventId);
            requireText("owner", owner);
            requireText("token", token);
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(claimedAt, "claimedAt");
            if (!expiresAt.isAfter(claimedAt)) {
                throw new IllegalArgumentException("expiresAt must be after claimedAt");
            }
            if (leaseVersion < 0) {
                throw new IllegalArgumentException("leaseVersion must not be negative");
            }
        }
    }

    /** 已领取的事件和对应 lease，payload 仅供 Worker 传给下游，不应写日志。 */
    record ClaimedEvent(OutboxEvent event, LeaseToken lease) {
        public ClaimedEvent {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(lease, "lease");
            if (!event.eventId().equals(lease.eventId())) {
                throw new IllegalArgumentException("event and lease eventId must match");
            }
        }
    }

    /** Outbox 状态，名称与 V001__initial_schema.sql 的 CHECK 约束一致。 */
    enum EventStatus {
        PENDING,
        LEASED,
        PUBLISHED,
        DEAD
    }

    enum AppendStatus {
        APPENDED,
        REPLAYED,
        CONFLICT
    }

    record AppendResult(AppendStatus status, OutboxEvent event) {
        public AppendResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(event, "event");
        }
    }

    enum PublishStatus {
        PUBLISHED,
        NOT_FOUND,
        NOT_OWNER,
        LEASE_EXPIRED,
        ALREADY_PUBLISHED,
        ALREADY_DEAD
    }

    record PublishResult(PublishStatus status, OutboxEvent event) {
        public PublishResult {
            Objects.requireNonNull(status, "status");
            // NOT_FOUND 时没有可返回的快照；其它状态通常会携带当前记录。
        }
    }

    enum FailureStatus {
        RETRY_SCHEDULED,
        DEAD,
        NOT_FOUND,
        NOT_OWNER,
        LEASE_EXPIRED,
        ALREADY_PUBLISHED,
        ALREADY_DEAD
    }

    record FailureResult(FailureStatus status, OutboxEvent event) {
        public FailureResult {
            Objects.requireNonNull(status, "status");
            // NOT_FOUND 时没有可返回的快照；其它状态通常会携带当前记录。
        }
    }

    /**
     * 与 outbox_event 表字段对应的不可变事件快照。
     *
     * <p>{@code payload} 是 canonical JSON 文本；领域层只把它视作不透明
     * 载荷，不解析供应商私有结构。追加时只能提交 PENDING/attempt=0 的
     * 快照，后续生命周期字段由 OutboxStore 管理。</p>
     */
    record OutboxEvent(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            Instant occurredAt,
            Instant availableAt,
            EventStatus status,
            int attemptCount,
            String leaseOwner,
            Instant leaseUntil,
            Instant publishedAt,
            String lastError) {
        public OutboxEvent {
            requireText("eventId", eventId);
            requireText("aggregateType", aggregateType);
            requireText("aggregateId", aggregateId);
            requireText("eventType", eventType);
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(availableAt, "availableAt");
            Objects.requireNonNull(status, "status");
            if (attemptCount < 0) {
                throw new IllegalArgumentException("attemptCount must not be negative");
            }
            if (status == EventStatus.LEASED && (leaseOwner == null || leaseOwner.isBlank() || leaseUntil == null)) {
                throw new IllegalArgumentException("leased event requires owner and leaseUntil");
            }
            if (status == EventStatus.PUBLISHED && publishedAt == null) {
                throw new IllegalArgumentException("published event requires publishedAt");
            }
        }

        /** 创建符合 append 前置条件的 PENDING 事件。 */
        public static OutboxEvent pending(
                String eventId,
                String aggregateType,
                String aggregateId,
                String eventType,
                String payload,
                Instant occurredAt,
                Instant availableAt) {
            return new OutboxEvent(
                    eventId, aggregateType, aggregateId, eventType, payload,
                    occurredAt, availableAt, EventStatus.PENDING, 0,
                    null, null, null, null);
        }

        /**
         * 仅比较决定事件身份的不可变业务字段。
         *
         * <p>{@code availableAt} 是失败重试时会更新的调度字段，与 status、
         * attemptCount、lease、publishedAt 和 lastError 一样不参与消息身份
         * 比较。因此同一原始事件在重试、发布或进入死信后再次 append，仍应
         * 返回 REPLAYED，而不能被误判为内容冲突。</p>
         */
        public boolean sameMessage(OutboxEvent other) {
            return other != null
                    && eventId.equals(other.eventId)
                    && aggregateType.equals(other.aggregateType)
                    && aggregateId.equals(other.aggregateId)
                    && eventType.equals(other.eventType)
                    && payload.equals(other.payload)
                    && occurredAt.equals(other.occurredAt);
        }

        private static void requireText(String name, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
