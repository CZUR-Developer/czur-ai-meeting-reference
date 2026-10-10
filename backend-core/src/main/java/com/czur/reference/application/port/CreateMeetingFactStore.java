package com.czur.reference.application.port;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingResponseFact;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * 完整 Create 请求/首次响应事实的连接级持久化端口。
 *
 * <p>这个端口只描述事实源语义，不负责获取连接、开始事务、提交、回滚、关闭
 * 连接或调用 HTTP/Provider。JDBC 实现由调用方用同一个显式事务连接构造，保证
 * 请求事实、响应事实及会议行可以在更高层原子提交。旧的 {@code meeting.create.v1}
 * 和旧 Outcome JSON 不属于本端口的兼容范围。</p>
 */
public interface CreateMeetingFactStore {
    /**
     * 按服务端生成的 opaque scope 和 HTTP 幂等键读取完整赢家事实。
     *
     * @param scope 服务端计算的幂等范围，不应来自客户端拼接
     * @param key 本次 Create 的幂等键，必须与请求快照中的键完全相等
     * @return 已保存记录；不存在时为空
     */
    Optional<Record> find(String scope, String key);

    /**
     * 在调用方当前事务中原子写入事实，或返回已持久化的赢家。
     *
     * @param candidate 待写入的完整请求/响应事实
     * @return 首次写入、相同事实重放或同键不同事实冲突
     */
    PersistResult persist(Record candidate);

    /** 持久化裁决状态。 */
    enum PersistStatus {
        /** 候选记录首次写入并成为唯一赢家。 */
        APPLIED,
        /** 数据库已保存完全相同的事实，本次为幂等重放。 */
        REPLAYED,
        /** 同一 scope/key 已有不同事实，候选未覆盖赢家。 */
        CONFLICT
    }

    /**
     * 与 V013 主键绑定的完整记录。
     *
     * <p>构造器验证 scope/key 非空、createdAt 存在，并强制 key 与请求快照的
     * {@code idempotencyKey} 完全一致。默认诊断输出只报告记录存在，不输出 scope、
     * 幂等键、请求正文、会议 ID、响应正文或 realtime token。</p>
     */
    record Record(
            String scope,
            String key,
            CreateMeetingRequestFact request,
            CreateMeetingResponseFact response,
            Instant createdAt) {
        public Record {
            requireText("scope", scope);
            requireText("key", key);
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(createdAt, "createdAt");
            // PostgreSQL TIMESTAMPTZ stores microseconds.  Normalize at the port
            // boundary so a record read back from JDBC compares equal to the
            // candidate that was persisted, even when callers supplied nanos.
            createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
            if (!key.equals(request.requestSnapshot().idempotencyKey())) {
                throw new IllegalArgumentException(
                        "key must match request snapshot idempotency key");
            }
        }

        @Override
        public String toString() {
            return "CreateMeetingFactStore.Record[requestPresent=true,responsePresent=true,"
                    + "createdAtPresent=true]";
        }
    }

    /** 一次写入裁决和数据库当前赢家。 */
    record PersistResult(PersistStatus status, Record record) {
        public PersistResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(record, "record");
        }

        @Override
        public String toString() {
            return "CreateMeetingFactStore.PersistResult[status=" + status
                    + ", record=redacted]";
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
