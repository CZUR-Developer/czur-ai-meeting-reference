package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandResult;

import java.util.Optional;

/**
 * 幂等记录端口。
 *
 * <p>记录以“操作范围 + 幂等键”唯一定位，同时保存请求摘要和第一次
 * 返回值。实现必须提供原子性的“查询后写入”能力（数据库中通常使用
 * 唯一索引）；否则并发重试可能重复调用外部模型、存储或计费服务。</p>
 */
public interface IdempotencyStore {
    Optional<IdempotencyRecord> find(String scope, String key);

    void save(IdempotencyRecord record);

    /**
     * 以一次不可分割的操作尝试登记幂等记录。
     *
     * <p>调用方必须在执行会产生外部副作用的动作前调用此方法。只有返回
     * {@link ReservationStatus#ACQUIRED} 的调用方拥有执行权；重试请求得到
     * {@link ReservationStatus#REPLAY} 并直接复用原结果；相同键但摘要不同
     * 时返回 {@link ReservationStatus#CONFLICT}。数据库实现应将
     * {@code (scope, key)} 建为唯一键，并在同一事务中完成该语义。</p>
     *
     * <p>默认实现通过端口自身的监视器兼容旧实现，但分布式实现必须覆盖此
     * 方法以使用数据库原子插入或等价的 compare-and-set 机制。</p>
     *
     * @param candidate 待登记的请求摘要和预期结果
     * @return 本次请求获得的幂等处理结果
     */
    default Reservation reserve(IdempotencyRecord candidate) {
        synchronized (this) {
            Optional<IdempotencyRecord> existing = find(candidate.scope(), candidate.key());
            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                if (!record.requestDigest().equals(candidate.requestDigest())) {
                    return new Reservation(ReservationStatus.CONFLICT, record);
                }
                return new Reservation(ReservationStatus.REPLAY, record);
            }
            save(candidate);
            return new Reservation(ReservationStatus.ACQUIRED, candidate);
        }
    }

    /**
     * 释放尚未完成业务副作用的预占记录。
     *
     * <p>应用操作失败时调用，确保同一幂等键可以安全重试。持久化实现应
     * 仅删除与摘要完全匹配的记录，避免误删后来请求。</p>
     */
    default void release(IdempotencyRecord candidate) {
        // 旧端口实现没有删除能力时保持兼容；生产实现应覆盖该方法。
    }

    enum ReservationStatus {
        ACQUIRED,
        REPLAY,
        CONFLICT
    }

    record Reservation(ReservationStatus status, IdempotencyRecord record) {
        public Reservation {
            if (status == null || record == null) {
                throw new NullPointerException("status/record");
            }
        }
    }

    record IdempotencyRecord(
            String scope,
            String key,
            String requestDigest,
            MeetingCommandResult result) {
        public IdempotencyRecord {
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("scope must not be blank");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            if (requestDigest == null || requestDigest.isBlank()) {
                throw new IllegalArgumentException("requestDigest must not be blank");
            }
            if (result == null) {
                throw new NullPointerException("result");
            }
        }
    }
}
