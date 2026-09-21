package com.czur.reference.application.outbox;

import com.czur.reference.application.port.OutboxStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 提交后 Outbox 的一次性分发器。
 *
 * <p>分发器只执行一次领取和处理，不创建线程、不启动定时器，也不保存进程内的
 * 待处理队列。调用方可以在自己的调度框架中周期性调用 {@link #runOnce()}；进程
 * 退出后下一次调用仍以数据库中的 Outbox 状态为准。这一边界使调度方式和事件
 * 处理业务保持正交，内存实现和 JDBC 实现也共用完全相同的 lease 栅栏。</p>
 *
 * <p>每种事件类型都必须通过 {@link HandlerRegistration} 显式注册。领取请求使用
 * 注册类型的关闭式白名单，因此未知事件不会被本 Worker 消费，也不会被错误地标记
 * 为已发布或死信。处理成功以后才调用 {@link OutboxStore#markPublished}；处理失败
 * 或抛出异常则使用固定延迟调用 {@link OutboxStore#markFailed}。mark 操作若因 lease
 * 栅栏失败，结果会明确返回 {@link ItemStatus#LEASE_LOST}，分发器不会使用旧句柄
 * 再尝试写入。</p>
 *
 * <p>处理较长事件时，Handler 可以通过 {@link DispatchContext#renew()} 显式续租。
 * 续租成功会旋转 lease token，后续完成/失败操作自动使用新句柄；续租失败会使本项
 * 进入 LEASE_LOST。续租没有后台线程，Handler 必须在仍拥有旧 lease 的时间窗口内
 * 调用它，并自行根据返回结果停止外部副作用。</p>
 */
public final class OutboxDispatcher {
    private static final Logger LOGGER = Logger.getLogger(OutboxDispatcher.class.getName());

    private final OutboxStore store;
    private final Map<String, Handler> handlers;
    private final Config config;

    /**
     * 处理一条已领取事件的业务回调。
     *
     * <p>回调不得修改 Outbox 状态；成功返回代表下游副作用已经按 eventId 做好幂等，
     * 分发器随后才会确认发布。回调抛出的 {@link Exception} 会被转换为稳定的
     * HANDLER_EXCEPTION 失败码，不会把异常正文写入日志或数据库。</p>
     */
    @FunctionalInterface
    public interface Handler {
        void handle(OutboxStore.OutboxEvent event, DispatchContext context) throws Exception;
    }

    /**
     * Handler 看到的当前租约上下文。
     *
     * <p>{@link #lease()} 只用于把当前句柄传给本分发器，不应序列化或写入日志。
     * {@link #renew()} 按分发器配置的时钟和 lease 时长执行一次原子续租；续租成功
     * 后上下文中的句柄已经更新。返回非 RENEWED 结果时，当前项不会再尝试
     * markPublished/markFailed。</p>
     */
    public interface DispatchContext {
        OutboxStore.LeaseToken lease();

        OutboxStore.RenewResult renew();
    }

    /**
     * 事件类型与处理器的显式绑定。
     *
     * <p>事件类型按精确字符串匹配，不会 trim、大小写转换或通配。相同类型重复注册
     * 会在构造分发器时拒绝，避免两个 Handler 对同一事件类型产生不确定行为。</p>
     */
    public record HandlerRegistration(String eventType, Handler handler) {
        public HandlerRegistration {
            requireText("eventType", eventType);
            Objects.requireNonNull(handler, "handler");
        }
    }

    /**
     * 一次分发器的固定运行参数。
     *
     * <p>{@code retryDelay} 是固定重试间隔，不根据异常正文或供应商响应动态改变。
     * {@code maxAttempts} 与 OutboxStore 的 attemptCount 共同决定何时进入 DEAD；
     * 首次失败将 attemptCount 从 0 提升为 1，达到上限时不会再次排队。</p>
     */
    public record Config(
            String leaseOwner,
            Clock clock,
            Duration leaseDuration,
            Duration retryDelay,
            int batchLimit,
            int maxAttempts) {
        public Config {
            requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            Objects.requireNonNull(retryDelay, "retryDelay");
            if (leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (retryDelay.isNegative()) {
                throw new IllegalArgumentException("retryDelay must not be negative");
            }
            if (batchLimit <= 0) {
                throw new IllegalArgumentException("batchLimit must be positive");
            }
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            try {
                clock.instant().plus(leaseDuration);
            } catch (RuntimeException invalidRange) {
                throw new IllegalArgumentException("leaseDuration exceeds Instant range", invalidRange);
            }
        }

        /** 参考实现使用的保守默认值；生产部署应按实际处理时长显式配置。 */
        public static Config defaults(String leaseOwner) {
            return new Config(
                    leaseOwner,
                    Clock.systemUTC(),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(5),
                    20,
                    3);
        }
    }

    /** 一条事件在本次 runOnce 中的稳定结果。 */
    public record ItemResult(
            String eventId,
            String eventType,
            ItemStatus status,
            String reasonCode) {
        public ItemResult {
            requireText("eventId", eventId);
            requireText("eventType", eventType);
            Objects.requireNonNull(status, "status");
            requireText("reasonCode", reasonCode);
        }
    }

    /**
     * 一次 runOnce 的汇总。
     *
     * <p>claimedCount 是 OutboxStore 实际成功加 lease 的数量；items 只包含本次
     * 真正进入 Handler 的事件。空注册集合会产生零领取结果，未知事件不会出现在
     * items 中。</p>
     */
    public record RunResult(int claimedCount, List<ItemResult> items) {
        public RunResult {
            if (claimedCount < 0) {
                throw new IllegalArgumentException("claimedCount must not be negative");
            }
            Objects.requireNonNull(items, "items");
            if (items.size() > claimedCount) {
                throw new IllegalArgumentException("items cannot exceed claimedCount");
            }
            items = List.copyOf(items);
        }

        public boolean idle() {
            return claimedCount == 0;
        }
    }

    /** 事件处理的稳定结果分类；不暴露底层异常正文。 */
    public enum ItemStatus {
        PUBLISHED,
        RETRY_SCHEDULED,
        DEAD,
        LEASE_LOST,
        ALREADY_PUBLISHED,
        ALREADY_DEAD
    }

    public OutboxDispatcher(
            OutboxStore store,
            Collection<HandlerRegistration> registrations,
            Config config) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(registrations, "registrations");
        Map<String, Handler> copiedHandlers = new HashMap<>();
        for (HandlerRegistration registration : registrations) {
            Objects.requireNonNull(registration, "registrations element");
            if (copiedHandlers.putIfAbsent(registration.eventType(), registration.handler()) != null) {
                throw new IllegalArgumentException(
                        "duplicate Outbox handler registration: " + registration.eventType());
            }
        }
        this.handlers = Map.copyOf(copiedHandlers);
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 执行一次无后台线程的领取、处理和确认循环。
     *
     * <p>时间只在本轮开始、失败回报和发布确认前读取；所有事件均通过同一批次
     * {@link OutboxStore.ClaimRequest} 领取。下游 Handler 抛异常时仍会尝试用当前
     * lease 记录失败；如果 lease 已过期或被其它 Worker 夺取，markFailed 的拒绝
     * 被如实返回为 LEASE_LOST，不会覆盖新 Worker 的结果。</p>
     *
     * @return 本轮实际领取数量及每个已领取事件的处理结果
     * @throws RuntimeException Outbox 持久化端口本身不可用时原样抛出；该情况没有
     *                          可安全写入的 lease 结果，外层调度器应稍后重新调用
     *                          {@code runOnce}
     */
    public RunResult runOnce() {
        Instant claimAt = config.clock().instant();
        List<OutboxStore.ClaimedEvent> claimed = store.claim(new OutboxStore.ClaimRequest(
                handlers.keySet(),
                config.leaseOwner(),
                claimAt,
                config.leaseDuration(),
                config.batchLimit()));
        if (claimed.isEmpty()) {
            LOGGER.log(Level.FINE, "Outbox Dispatcher 本轮没有可领取事件 owner={0}", config.leaseOwner());
            return new RunResult(0, List.of());
        }

        List<ItemResult> results = new ArrayList<>(claimed.size());
        for (OutboxStore.ClaimedEvent item : claimed) {
            results.add(process(item));
        }
        return new RunResult(claimed.size(), results);
    }

    private ItemResult process(OutboxStore.ClaimedEvent item) {
        OutboxStore.OutboxEvent event = item.event();
        Handler handler = handlers.get(event.eventType());
        // Store 的 claim 白名单和本地注册表来自同一快照；此分支只防御不合规适配器。
        if (handler == null) {
            LOGGER.log(Level.WARNING, "Outbox Dispatcher 收到未注册事件 eventId={0} eventType={1}",
                    new Object[]{event.eventId(), event.eventType()});
            return new ItemResult(event.eventId(), event.eventType(), ItemStatus.LEASE_LOST,
                    "UNREGISTERED_HANDLER");
        }

        DispatchContextImpl context = new DispatchContextImpl(item.lease());
        try {
            handler.handle(event, context);
            rethrowRenewPersistenceFailure(context);
            if (context.renewFailed()) {
                return leaseLost(event, "RENEW_FAILED");
            }
        } catch (Exception handlerFailure) {
            // renew() 的持久化故障必须原样穿透 Dispatcher，不能被当作 Handler
            // 业务异常记录为失败，否则会无依据地消耗 attemptCount。
            rethrowRenewPersistenceFailure(context);
            if (context.renewFailed()) {
                return leaseLost(event, "RENEW_FAILED");
            }
            // 只记录稳定异常类型，不记录异常正文、供应商响应或事件 payload。
            LOGGER.log(Level.WARNING, "Outbox Handler 执行异常 eventId={0} exception={1}",
                    new Object[]{event.eventId(), handlerFailure.getClass().getSimpleName()});
            return recordFailure(event, context.lease(), "HANDLER_EXCEPTION");
        }

        OutboxStore.PublishResult published = store.markPublished(
                context.lease(), config.clock().instant());
        return switch (published.status()) {
            case PUBLISHED -> {
                LOGGER.log(Level.INFO, "Outbox 事件处理并确认发布 eventId={0} eventType={1}",
                        new Object[]{event.eventId(), event.eventType()});
                yield new ItemResult(event.eventId(), event.eventType(), ItemStatus.PUBLISHED, "PUBLISHED");
            }
            case ALREADY_PUBLISHED -> new ItemResult(
                    event.eventId(), event.eventType(), ItemStatus.ALREADY_PUBLISHED, "ALREADY_PUBLISHED");
            case ALREADY_DEAD -> new ItemResult(
                    event.eventId(), event.eventType(), ItemStatus.ALREADY_DEAD, "ALREADY_DEAD");
            case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED -> leaseLost(event, published.status().name());
        };
    }

    private ItemResult recordFailure(
            OutboxStore.OutboxEvent event,
            OutboxStore.LeaseToken lease,
            String reasonCode) {
        Instant failedAt = config.clock().instant();
        Instant nextAvailableAt;
        try {
            nextAvailableAt = failedAt.plus(config.retryDelay());
        } catch (RuntimeException invalidRange) {
            // 配置构造已验证当前时钟范围；这里只保留防御性保护，不能写入不确定的重试时间。
            LOGGER.log(Level.SEVERE, "Outbox Dispatcher 重试时间溢出 eventId={0}", event.eventId());
            return leaseLost(event, "RETRY_TIME_INVALID");
        }
        OutboxStore.FailureResult failed = store.markFailed(
                lease, failedAt, nextAvailableAt, reasonCode, config.maxAttempts());
        return switch (failed.status()) {
            case RETRY_SCHEDULED -> {
                LOGGER.log(Level.INFO, "Outbox 事件已安排固定延迟重试 eventId={0}", event.eventId());
                yield new ItemResult(event.eventId(), event.eventType(), ItemStatus.RETRY_SCHEDULED,
                        reasonCode);
            }
            case DEAD -> {
                LOGGER.log(Level.WARNING, "Outbox 事件达到最大次数进入死信 eventId={0}", event.eventId());
                yield new ItemResult(event.eventId(), event.eventType(), ItemStatus.DEAD, reasonCode);
            }
            case ALREADY_PUBLISHED -> new ItemResult(
                    event.eventId(), event.eventType(), ItemStatus.ALREADY_PUBLISHED, "ALREADY_PUBLISHED");
            case ALREADY_DEAD -> new ItemResult(
                    event.eventId(), event.eventType(), ItemStatus.ALREADY_DEAD, "ALREADY_DEAD");
            case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED -> leaseLost(event, failed.status().name());
        };
    }

    private static void rethrowRenewPersistenceFailure(DispatchContextImpl context) {
        if (context.renewPersistenceFailure() != null) {
            throw context.renewPersistenceFailure();
        }
    }

    private ItemResult leaseLost(OutboxStore.OutboxEvent event, String reasonCode) {
        LOGGER.log(Level.WARNING, "Outbox 事件 lease 栅栏拒绝后续写入 eventId={0} reason={1}",
                new Object[]{event.eventId(), reasonCode});
        return new ItemResult(event.eventId(), event.eventType(), ItemStatus.LEASE_LOST, reasonCode);
    }

    private final class DispatchContextImpl implements DispatchContext {
        private OutboxStore.LeaseToken lease;
        private boolean renewFailed;
        private OutboxStore.RenewResult renewalResult;
        private RuntimeException renewPersistenceFailure;

        private DispatchContextImpl(OutboxStore.LeaseToken lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
        }

        @Override
        public OutboxStore.LeaseToken lease() {
            return lease;
        }

        @Override
        public OutboxStore.RenewResult renew() {
            if (renewFailed) {
                if (renewPersistenceFailure != null) {
                    throw renewPersistenceFailure;
                }
                return renewalResult;
            }
            OutboxStore.RenewResult result;
            try {
                result = store.renew(lease, config.clock().instant(), config.leaseDuration());
            } catch (RuntimeException persistenceFailure) {
                renewFailed = true;
                renewPersistenceFailure = persistenceFailure;
                throw persistenceFailure;
            }
            if (result.status() == OutboxStore.RenewStatus.RENEWED) {
                lease = result.lease();
            } else {
                renewFailed = true;
                renewalResult = result;
                LOGGER.log(Level.WARNING, "Outbox lease 续租失败 eventId={0} status={1}",
                        new Object[]{lease.eventId(), result.status()});
            }
            return result;
        }

        private boolean renewFailed() {
            return renewFailed;
        }

        private RuntimeException renewPersistenceFailure() {
            return renewPersistenceFailure;
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
