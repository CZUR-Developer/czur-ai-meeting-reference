package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalUsageFactStore;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.entitlement.UsageUnit;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V009 {@code final_usage_fact} 的事务连接内原语。
 *
 * <p>该类不持有 {@code DataSource}，也不拥有调用方传入的连接。更高层工作单元
 * 必须先关闭 auto-commit、锁定对应 meeting 行，再用同一个连接调用本类；本类
 * 从不提交、回滚或关闭连接。这样最终用量首次固化可以在后续任务中与 Reservation
 * 结算和其它收口事实组成单个本地事务。</p>
 *
 * <p>{@code source_accepted_at} 来源必须已经经过 V008 durable input 持久化，
 * 因而必须是数据库时间列可无损表达的微秒精度。这里在执行
 * SQL 前拒绝更高精度时间，避免驱动舍入后把同一 Java 候选在重试时错误分类为
 * 冲突或重放。</p>
 */
final class JdbcFinalUsageFactStore {
    private static final String FIND_SQL = """
            SELECT f.meeting_id, f.actual_unit, f.actual_amount,
                   f.source_input_binding, f.source_input_digest, f.source_accepted_at,
                   d.meeting_id AS durable_meeting_id,
                   d.input_binding AS durable_input_binding,
                   d.last_accepted_sequence AS durable_last_accepted_sequence,
                   d.audio_duration_ms AS durable_audio_duration_ms,
                   d.source_language AS durable_source_language,
                   d.target_languages AS durable_target_languages,
                   d.accepted_at AS durable_accepted_at,
                   d.content_digest AS durable_content_digest
              FROM final_usage_fact f
              LEFT JOIN durable_input_fact d ON d.meeting_id = f.meeting_id
             WHERE f.meeting_id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO final_usage_fact (
                meeting_id, actual_unit, actual_amount,
                source_input_binding, source_input_digest, source_accepted_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private JdbcFinalUsageFactStore() {
    }

    /**
     * 在当前事务连接中读取已提交或本事务已写入的最终用量赢家。
     *
     * <p>V009 不复制 durable input 的语言和 sequence。读回时通过同一连接读取
     * V008 完整来源事实并使用 {@link FinalUsageFact#fromInput} 派生候选，再逐项
     * 核对 V009 镜像字段。来源缺失或任一字段漂移都作为持久事实损坏失败关闭。</p>
     */
    static Optional<FinalUsageFact> find(Connection connection, String meetingId)
            throws SQLException {
        requireTransactionalConnection(connection);
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                StoredFact stored = readStoredFact(rows);
                FinalUsageFact derived = FinalUsageFact.fromInput(readSourceFact(rows));
                if (!stored.matches(derived)) {
                    throw malformedFact();
                }
                return Optional.of(derived);
            }
        }
    }

    /**
     * 在当前事务连接中按完整持久事实裁决首次写入、幂等重放或冲突。
     *
     * <p>该方法假定外层已经锁定同一 meeting 行；它不会自行打开第二个连接，
     * 也不会捕获唯一键异常后猜测赢家。冲突返回数据库中的原赢家，绝不覆盖。</p>
     */
    static FinalUsageFactStore.PersistResult persist(
            Connection connection,
            FinalUsageFact fact) throws SQLException {
        requireTransactionalConnection(connection);
        Objects.requireNonNull(fact, "fact");
        // 在读取既有赢家或来源事实前校验精度，避免纳秒候选因比较顺序不同
        // 被误报为 REPLAYED/CONFLICT，而不是明确指出无法无损写入数据库时间列。
        requirePersistentTimestamp(fact.sourceAcceptedAt());
        Optional<FinalUsageFact> existing = find(connection, fact.meetingId());
        if (existing.isPresent()) {
            FinalUsageFact winner = existing.orElseThrow();
            FinalUsageFactStore.PersistStatus status = winner.equals(fact)
                    ? FinalUsageFactStore.PersistStatus.REPLAYED
                    : FinalUsageFactStore.PersistStatus.CONFLICT;
            return new FinalUsageFactStore.PersistResult(status, winner);
        }

        FinalUsageFact sourceFact = JdbcDurableInputFactStore.find(
                        connection, fact.meetingId())
                .map(FinalUsageFact::fromInput)
                .orElseThrow(JdbcFinalUsageFactStore::malformedFact);
        if (!sourceFact.equals(fact)) {
            throw new IllegalArgumentException(
                    "final usage candidate does not match durable input fact");
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, fact.meetingId());
            statement.setString(2, fact.actual().unit().name());
            statement.setLong(3, fact.actual().amount());
            statement.setString(4, fact.sourceInputBinding());
            statement.setString(5, fact.sourceInputDigest());
            statement.setTimestamp(6, Timestamp.from(fact.sourceAcceptedAt()));
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("final usage fact insert affected no row");
            }
        }
        return new FinalUsageFactStore.PersistResult(
                FinalUsageFactStore.PersistStatus.APPLIED, fact);
    }

    private static StoredFact readStoredFact(ResultSet rows) throws SQLException {
        String meetingId = requiredText(rows, "meeting_id");
        String actualUnit = requiredText(rows, "actual_unit");
        if (!UsageUnit.MEETING_DURATION_MS.name().equals(actualUnit)) {
            throw malformedFact();
        }
        long actualAmount = rows.getLong("actual_amount");
        if (rows.wasNull() || actualAmount < 0) {
            throw malformedFact();
        }
        String sourceBinding = requiredText(rows, "source_input_binding");
        String sourceDigest = requiredText(rows, "source_input_digest");
        Timestamp sourceAcceptedAt = rows.getTimestamp("source_accepted_at");
        if (sourceAcceptedAt == null) {
            throw malformedFact();
        }
        Instant acceptedAt = sourceAcceptedAt.toInstant();
        requirePersistentTimestamp(acceptedAt);
        return new StoredFact(
                meetingId, actualUnit, actualAmount, sourceBinding, sourceDigest, acceptedAt);
    }

    /** 从同一条 JOIN 结果还原真实 V008 来源事实，不为缺失字段填充业务默认值。 */
    private static FinalizationInputFact readSourceFact(ResultSet rows) throws SQLException {
        String meetingId = requiredText(rows, "durable_meeting_id");
        String inputBinding = requiredText(rows, "durable_input_binding");
        long sequence = rows.getLong("durable_last_accepted_sequence");
        if (rows.wasNull()) {
            throw malformedFact();
        }
        long duration = rows.getLong("durable_audio_duration_ms");
        if (rows.wasNull()) {
            throw malformedFact();
        }
        String sourceLanguage = requiredText(rows, "durable_source_language");
        Array sqlArray = rows.getArray("durable_target_languages");
        if (sqlArray == null) {
            throw malformedFact();
        }
        Object rawArray;
        try {
            rawArray = sqlArray.getArray();
        } finally {
            sqlArray.free();
        }
        if (!(rawArray instanceof Object[] values)) {
            throw malformedFact();
        }
        List<String> targetLanguages = new ArrayList<>(values.length);
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw malformedFact();
            }
            targetLanguages.add(text);
        }
        Timestamp acceptedAt = rows.getTimestamp("durable_accepted_at");
        String contentDigest = requiredText(rows, "durable_content_digest");
        if (acceptedAt == null) {
            throw malformedFact();
        }
        try {
            return new FinalizationInputFact(
                    meetingId,
                    inputBinding,
                    sequence,
                    duration,
                    sourceLanguage,
                    targetLanguages,
                    acceptedAt.toInstant(),
                    contentDigest);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw malformedFact();
        }
    }

    private static String requiredText(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            throw malformedFact();
        }
        return value;
    }

    private static IllegalStateException malformedFact() {
        return new IllegalStateException("final usage fact is inconsistent");
    }

    private static void requirePersistentTimestamp(Instant value) {
        Objects.requireNonNull(value, "sourceAcceptedAt");
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "sourceAcceptedAt must use database microsecond precision");
        }
    }

    private static void requireTransactionalConnection(Connection connection)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                    "final usage fact operation requires autoCommit=false");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** V009 原始镜像；只用于与真实 V008 派生事实逐项比对，不独立生成业务事实。 */
    private record StoredFact(
            String meetingId,
            String actualUnit,
            long actualAmount,
            String sourceInputBinding,
            String sourceInputDigest,
            Instant sourceAcceptedAt) {
        private boolean matches(FinalUsageFact fact) {
            return meetingId.equals(fact.meetingId())
                    && actualUnit.equals(fact.actual().unit().name())
                    && actualAmount == fact.actual().amount()
                    && sourceInputBinding.equals(fact.sourceInputBinding())
                    && sourceInputDigest.equals(fact.sourceInputDigest())
                    && sourceAcceptedAt.equals(fact.sourceAcceptedAt());
        }
    }
}
