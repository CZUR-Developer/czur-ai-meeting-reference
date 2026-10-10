package com.czur.reference.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
     * 按 Worker 已注册的事件类型原子领取当前可发布事件。
     *
     * <p>只有 {@link ClaimRequest#registeredEventTypes()} 中精确匹配的类型
     * 才能进入候选集。实现必须先应用类型白名单，再按
     * {@code availableAt}、{@code occurredAt}、{@code eventId} 的确定性顺序
     * 排序并应用 limit。未注册类型不得占用批次名额，也不得改变
     * status、attempt 或 lease；空白名单必须立即返回空集合，不能退化为
     * “领取全部”。</p>
     *
     * <p>可领取条件为 {@code availableAt <= now}，且记录处于 PENDING，或
     * 处于 LEASED 但原 lease 已过期。成功领取会把记录更新为 LEASED，
     * 旋转不透明 token，并使 {@code leaseVersion} 在事件的当前值上单调递增。
     * lease 过期后允许其它 Worker 重新领取，但旧句柄的续租、完成和
     * 失败回报都必须被栅栏拒绝。</p>
     *
     * <p>生产实现必须使用数据库行锁、条件 UPDATE/RETURNING 或等价的
     * 原子机制，不得以“先无锁查询、后无条件更新”代替。返回值只包含
     * 本次真正成功持有 lease 的事件。</p>
     *
     * @param request 包含关闭式类型白名单、owner、判定时间、lease 时长和批次上限
     * @return 已成功加 lease 的事件，按确定性候选顺序返回
     */
    List<ClaimedEvent> claim(ClaimRequest request);

    /**
     * 按 Worker 已显式注册的事件类型领取 Outbox 事件时使用的不可变请求。
     *
     * <p>{@code registeredEventTypes} 是关闭式消费边界：后续按本请求领取时，
     * 只有集合中精确匹配的事件类型才允许进入候选集。空集合是合法输入，语义
     * 是本轮不领取任何事件，绝不能退化成“领取全部”。构造时会校验每个元素，
     * 再按自然顺序建立防御副本；因此调用方之后修改原集合不会影响请求，读取
     * 到的集合也不可修改且迭代顺序确定。事件类型不会被 trim 或改写，避免把
     * 两个不同的协议标识静默合并。</p>
     *
     * <p>适配器必须在筛选、排序和 limit 之前应用类型白名单，
     * 使未知类型既不改变状态，也不占用本批次数量。</p>
     *
     * @param registeredEventTypes 当前 Worker 显式注册的事件类型；集合和元素均不可为 null，
     *                             元素不可为空白，空集合合法
     * @param leaseOwner Worker 实例稳定且非空白的 owner 标识
     * @param now 本次领取的判定时间
     * @param leaseDuration 本次领取的 lease 时长，必须严格为正
     * @param limit 本次最多领取的事件数，必须严格为正
     */
    record ClaimRequest(
            Set<String> registeredEventTypes,
            String leaseOwner,
            Instant now,
            Duration leaseDuration,
            int limit) {
        public ClaimRequest {
            Objects.requireNonNull(registeredEventTypes, "registeredEventTypes");
            Set<String> orderedTypes = new TreeSet<>();
            for (String eventType : registeredEventTypes) {
                requireText("registeredEventTypes element", eventType);
                orderedTypes.add(eventType);
            }
            registeredEventTypes = Collections.unmodifiableSet(orderedTypes);
            requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }
    }

    /**
     * 原子续租一个尚未过期的 Outbox lease。
     *
     * <p>只有事件仍为 LEASED，且当前记录与 {@code lease} 的 eventId、
     * owner、不透明 token、leaseVersion 和 expiresAt 全部一致时才允许续租。
     * {@code renewedAt} 不得早于本句柄的 claimedAt，且必须严格早于
     * 旧 expiresAt；{@code renewedAt + leaseDuration} 还必须严格晚于旧
     * expiresAt，以防止续租缩短 lease 或原地不动。</p>
     *
     * <p>续租成功必须在同一原子更新中生成新 token、将 leaseVersion
     * 加一，并把 claimedAt 更新为 renewedAt；返回的 {@link RenewResult#lease()}
     * 是唯一可继续使用的新句柄。旧句柄必须立即失效，其后的 renew、
     * markPublished 和 markFailed 均按 NOT_OWNER 拒绝。续租不得改变
     * attemptCount、availableAt、payload 或业务事件类型。</p>
     *
     * <p>失败结果必须使用 {@link RenewStatus} 的关闭式分类：不存在、
     * 栅栏不匹配、租约过期、已发布和已死信不得相互混淆；任何失败
     * 都不得返回可使用的 lease。</p>
     *
     * @param lease 当前 claim 或上一次成功 renew 返回的完整句柄
     * @param renewedAt 本次续租的判定时间
     * @param leaseDuration 从 renewedAt 开始计算的新 lease 时长，必须严格为正
     * @return 旋转后的新句柄及事件快照，或一个明确的失败分类
     */
    RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration);

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
     * {@link #renew(LeaseToken, Instant, Duration)}、
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

    /**
     * 续租操作的关闭式状态集合。
     *
     * <p>{@link #RENEWED} 表示当前租约完成了原子旋转，并由
     * {@link RenewResult#lease()} 返回新的唯一句柄；调用方必须立即丢弃旧句柄。
     * 其它状态均不会返回可继续使用的 lease，调用方不得猜测续租成功。</p>
     */
    enum RenewStatus {
        RENEWED,
        NOT_FOUND,
        NOT_OWNER,
        LEASE_EXPIRED,
        ALREADY_PUBLISHED,
        ALREADY_DEAD
    }

    /**
     * Outbox lease 续租结果。
     *
     * <p>该结果通过构造校验排除含糊组合：成功时必须同时携带 LEASED 事件快照
     * 和新 lease，且两者的 eventId、owner、到期时间完全一致；NOT_FOUND 时
     * 事件与 lease 均为空；其它失败状态必须携带与状态一致的当前事件快照且不得
     * 携带 lease。LEASE_EXPIRED 只对应 LEASED 快照，两个终态结果分别只对应
     * PUBLISHED 和 DEAD。NOT_OWNER 允许 PENDING 或 LEASED：旧句柄对应的事件
     * 可能已经失败并回到 PENDING，也可能仍由另一个句柄保持 LEASED；终态快照
     * 必须使用专门的 ALREADY_PUBLISHED 或 ALREADY_DEAD，不能归入 NOT_OWNER。
     * 当前 {@link OutboxEvent} 不保存 token、leaseVersion 或 claimedAt，因而这些
     * 字段只能由具体续租实现原子校验，结果值对象不臆造无法从快照证明的关系。</p>
     *
     * @param status 明确的续租结果状态
     * @param event 续租后的或当前的事件快照；仅 NOT_FOUND 时为空
     * @param lease 成功后旋转得到的新 lease；仅 RENEWED 时非空
     */
    record RenewResult(RenewStatus status, OutboxEvent event, LeaseToken lease) {
        public RenewResult {
            Objects.requireNonNull(status, "status");
            switch (status) {
                case RENEWED -> {
                    Objects.requireNonNull(event, "event");
                    Objects.requireNonNull(lease, "lease");
                    if (event.status() != EventStatus.LEASED) {
                        throw new IllegalArgumentException("RENEWED requires a LEASED event");
                    }
                    if (!event.eventId().equals(lease.eventId())
                            || !lease.owner().equals(event.leaseOwner())
                            || !lease.expiresAt().equals(event.leaseUntil())) {
                        throw new IllegalArgumentException(
                                "RENEWED event and lease must align on eventId, owner and expiresAt");
                    }
                }
                case NOT_FOUND -> {
                    if (event != null || lease != null) {
                        throw new IllegalArgumentException("NOT_FOUND must not carry event or lease");
                    }
                }
                case NOT_OWNER -> {
                    requireRenewalFailureEventWithoutLease(event, lease);
                    // 现有实现会在失败回队后返回 PENDING，句柄冲突时返回 LEASED；终态先单独分类。
                    if (event.status() != EventStatus.PENDING
                            && event.status() != EventStatus.LEASED) {
                        throw new IllegalArgumentException(
                                "NOT_OWNER requires a PENDING or LEASED event");
                    }
                }
                case LEASE_EXPIRED -> {
                    requireRenewalFailureEventWithoutLease(event, lease);
                    requireRenewalEventStatus(status, event, EventStatus.LEASED);
                }
                case ALREADY_PUBLISHED -> {
                    requireRenewalFailureEventWithoutLease(event, lease);
                    requireRenewalEventStatus(status, event, EventStatus.PUBLISHED);
                }
                case ALREADY_DEAD -> {
                    requireRenewalFailureEventWithoutLease(event, lease);
                    requireRenewalEventStatus(status, event, EventStatus.DEAD);
                }
            }
        }

        private static void requireRenewalFailureEventWithoutLease(
                OutboxEvent event, LeaseToken lease) {
            if (event == null || lease != null) {
                throw new IllegalArgumentException(
                        "renewal failure must carry an event and must not carry a lease");
            }
        }

        private static void requireRenewalEventStatus(
                RenewStatus renewalStatus, OutboxEvent event, EventStatus requiredEventStatus) {
            if (event.status() != requiredEventStatus) {
                throw new IllegalArgumentException(
                        renewalStatus + " requires a " + requiredEventStatus + " event");
            }
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
