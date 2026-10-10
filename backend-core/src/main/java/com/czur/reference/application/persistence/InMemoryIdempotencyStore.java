package com.czur.reference.application.persistence;

import com.czur.reference.application.port.IdempotencyStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的内存幂等仓库。
 *
 * <p>该实现用于本地参考部署；生产实现必须用持久化数据库和唯一约束，
 * 以便进程重启后仍能识别重试请求。</p>
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        return Optional.ofNullable(records.get(compositeKey(scope, key)));
    }

    @Override
    public void save(IdempotencyRecord record) {
        records.putIfAbsent(compositeKey(record.scope(), record.key()), record);
    }

    /**
     * 使用 ConcurrentHashMap.compute 原子完成预占，模拟生产数据库的唯一键
     * 约束。compute 回调内不会执行任何业务副作用，只负责决定记录归属。
     */
    @Override
    public Reservation reserve(IdempotencyRecord candidate) {
        String mapKey = compositeKey(candidate.scope(), candidate.key());
        Reservation[] outcome = new Reservation[1];
        records.compute(mapKey, (ignored, existing) -> {
            if (existing == null) {
                outcome[0] = new Reservation(ReservationStatus.ACQUIRED, candidate);
                return candidate;
            }
            if (!existing.requestDigest().equals(candidate.requestDigest())) {
                outcome[0] = new Reservation(ReservationStatus.CONFLICT, existing);
            } else {
                outcome[0] = new Reservation(ReservationStatus.REPLAY, existing);
            }
            return existing;
        });
        return outcome[0];
    }

    @Override
    public void release(IdempotencyRecord candidate) {
        records.remove(compositeKey(candidate.scope(), candidate.key()), candidate);
    }

    private static String compositeKey(String scope, String key) {
        return scope + '\u0000' + key;
    }
}
