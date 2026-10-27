package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.entitlement.ExpiredReservationCleanupWorker;
import com.czur.reference.application.outbox.OutboxDispatcher;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.LeaseAwareReservationCanceller;

import java.util.Collection;

/**
 * client-model 宿主的 Worker 装配点。
 *
 * <p>该类只复用调用方唯一的 {@link JdbcDatabaseContext} 连接池和引擎，
 * 不创建第二套数据源、不启动线程，也不猜测调度周期。宿主负责在自己的
 * 调度框架中周期调用返回对象的 {@code runOnce()}，并在关闭上下文前停止调度。</p>
 */
public final class JdbcClientModelWorkers {
    private JdbcClientModelWorkers() {
    }

    /**
     * 创建提交后 Outbox 分发器；事件类型必须由宿主显式注册。
     * MySQL 5.7 使用事务内条件 UPDATE，不依赖 SKIP LOCKED/RETURNING。
     */
    public static OutboxDispatcher outboxDispatcher(
            JdbcDatabaseContext context,
            Collection<OutboxDispatcher.HandlerRegistration> registrations,
            OutboxDispatcher.Config config) {
        return new OutboxDispatcher(new JdbcOutboxStore(context), registrations, config);
    }

    /**
     * 创建过期 Reservation 清理 Worker；canceller 必须是本装配的
     * lease-aware 权益端口，不能用普通 cancel 替代。
     */
    public static ExpiredReservationCleanupWorker expiredReservationCleanupWorker(
            JdbcDatabaseContext context,
            LeaseAwareReservationCanceller canceller,
            EntitlementIdGenerator ids,
            ExpiredReservationCleanupWorker.Config config) {
        return new ExpiredReservationCleanupWorker(
                new JdbcExpiredReservationStore(context.dataSource(), ids, context.engine()),
                canceller, config);
    }
}
