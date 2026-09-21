package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DurableInputFactStore;
import com.czur.reference.application.port.FinalizationInputFact;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V008 durable input fact 的事务连接内原语。
 *
 * <p>该类故意不是应用端口实现，也不持有 {@code DataSource}。调用方必须已经
 * 锁定对应 meeting 行并关闭 auto-commit，再通过本类在同一连接上读取和裁决事实；
 * 本类从不提交、回滚或关闭连接。这样首次写入、重放和冲突可以与会议级 receipt
 * 收口绑定在一个本地持久事务中。</p>
 */
final class JdbcDurableInputFactStore {
    private static final String FIND_SQL = """
            SELECT meeting_id, input_binding, last_accepted_sequence,
                   audio_duration_ms, source_language, target_languages,
                   accepted_at, content_digest
              FROM durable_input_fact
             WHERE meeting_id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO durable_input_fact (
                meeting_id, input_binding, last_accepted_sequence, audio_duration_ms,
                source_language, target_languages, accepted_at, content_digest)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private JdbcDurableInputFactStore() {
    }

    /** 在当前事务连接中读取已提交或本事务已写入的 durable fact。 */
    static Optional<FinalizationInputFact> find(
            Connection connection,
            String meetingId) throws SQLException {
        requireTransactionalConnection(connection);
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readFact(rows)) : Optional.empty();
            }
        }
    }

    /**
     * 在当前事务连接中按完整不可变事实裁决首次写入、重放或冲突。
     *
     * <p>meeting 行锁由外层协同端口负责；本方法不自行打开第二个连接，也不
     * 捕获唯一键异常去猜测赢家。</p>
     */
    static DurableInputFactStore.PersistResult persist(
            Connection connection,
            FinalizationInputFact fact) throws SQLException {
        requireTransactionalConnection(connection);
        Objects.requireNonNull(fact, "fact");
        Optional<FinalizationInputFact> existing = find(connection, fact.meetingId());
        if (existing.isPresent()) {
            FinalizationInputFact winner = existing.orElseThrow();
            DurableInputFactStore.PersistStatus status = winner.equals(fact)
                    ? DurableInputFactStore.PersistStatus.REPLAYED
                    : DurableInputFactStore.PersistStatus.CONFLICT;
            return new DurableInputFactStore.PersistResult(status, winner);
        }

        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, fact.meetingId());
            statement.setString(2, fact.inputBinding());
            statement.setLong(3, fact.lastAcceptedSequence());
            statement.setLong(4, fact.audioDurationMs());
            statement.setString(5, fact.sourceLanguage());
            Array targetLanguages = connection.createArrayOf(
                    "text", fact.targetLanguages().toArray(String[]::new));
            try {
                statement.setArray(6, targetLanguages);
                statement.setTimestamp(7, Timestamp.from(fact.acceptedAt()));
                statement.setString(8, fact.contentDigest());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("durable input fact insert affected no row");
                }
            } finally {
                try {
                    targetLanguages.free();
                } catch (SQLException ignored) {
                    // 连接随后仍由外层事务拥有；释放 JDBC Array 失败不应覆盖主结果。
                }
            }
        }
        return new DurableInputFactStore.PersistResult(
                DurableInputFactStore.PersistStatus.APPLIED, fact);
    }

    private static FinalizationInputFact readFact(ResultSet rows) throws SQLException {
        String meetingId = requiredText(rows, "meeting_id");
        String inputBinding = requiredText(rows, "input_binding");
        long sequence = rows.getLong("last_accepted_sequence");
        if (rows.wasNull()) {
            throw malformedFact();
        }
        long duration = rows.getLong("audio_duration_ms");
        if (rows.wasNull()) {
            throw malformedFact();
        }
        String sourceLanguage = requiredText(rows, "source_language");
        Array sqlArray = rows.getArray("target_languages");
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
        List<String> targetLanguages = new java.util.ArrayList<>(values.length);
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw malformedFact();
            }
            targetLanguages.add(text);
        }
        Timestamp acceptedAt = rows.getTimestamp("accepted_at");
        if (acceptedAt == null) {
            throw malformedFact();
        }
        String contentDigest = requiredText(rows, "content_digest");
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
        return new IllegalStateException("durable input fact is inconsistent");
    }

    private static void requireTransactionalConnection(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                    "durable input fact operation requires autoCommit=false");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
