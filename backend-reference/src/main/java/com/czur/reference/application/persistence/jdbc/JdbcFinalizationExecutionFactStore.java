package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.FinalizationExecutionFactStore;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V011 执行事实的连接级 JDBC 原语。
 *
 * <p>本类只使用调用方传入的 JDBC 连接，不创建连接、不提交或回滚事务，也不关闭
 * 连接。调用方必须先进入显式事务；这样执行事实可以和同一会议的其它收口事实共用
 * 一个数据库事务。写入采用 {@code ON CONFLICT DO NOTHING}，写入后无论是否为本次
 * 首次插入都重新读回完整行，再用 canonical JSON、JSONB 镜像、SHA-256 和领域对象
 * 四重校验裁决 APPLIED、REPLAYED 或 CONFLICT。</p>
 *
 * <p>每次操作先把当前事务的 search_path 固定为部署 schema 后接 pg_temp。这样连接
 * 中存在同名临时表时，未限定的业务表名仍不会被临时对象遮蔽；该设置由本事务自动
 * 回收，不改变调用方事务的提交/回滚责任。</p>
 */
final class JdbcFinalizationExecutionFactStore
        implements FinalizationExecutionFactStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcFinalizationExecutionFactStore.class.getName());

    private static final String PIN_SEARCH_PATH_SQL = """
            SELECT set_config(
                'search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;
    private static final String FIND_SQL = """
            SELECT meeting_id, canonical_json, execution_fact_json::text AS jsonb_text,
                   content_sha256
              FROM finalization_execution_fact
             WHERE meeting_id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO finalization_execution_fact (
                meeting_id, canonical_json, execution_fact_json, content_sha256)
            VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (meeting_id) DO NOTHING
            """;

    private final Connection connection;

    /** 绑定调用方当前连接；构造过程不获取或释放连接。 */
    JdbcFinalizationExecutionFactStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Optional<FinalizationExecutionFact> find(String meetingId) {
        requireMeetingId(meetingId);
        requireExplicitTransaction();
        try {
            pinSearchPath();
            try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
                statement.setString(1, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        logResult("FIND", "EMPTY");
                        return Optional.empty();
                    }
                    StoredFact stored = readStoredFact(rows, meetingId);
                    if (rows.next()) {
                        throw corruptData();
                    }
                    logResult("FIND", "PRESENT");
                    return Optional.of(stored.fact());
                }
            }
        } catch (StoredFactException failure) {
            logFailure("FIND", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("FIND", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("FIND");
        }
    }

    @Override
    public FinalizationExecutionFactStore.PersistResult persist(
            FinalizationExecutionFact candidate) {
        Objects.requireNonNull(candidate, "candidate");
        requireExplicitTransaction();
        String canonical = FinalizationExecutionFactJson.encode(candidate);
        byte[] digest = sha256(canonical);
        try {
            pinSearchPath();
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                statement.setString(1, candidate.meetingId());
                statement.setString(2, canonical);
                statement.setString(3, canonical);
                statement.setBytes(4, digest);
                inserted = statement.executeUpdate();
            }
            StoredFact winner = selectRequired(candidate.meetingId());
            if (inserted == 1) {
                if (!winner.fact().equals(candidate)
                        || !winner.canonical().equals(canonical)) {
                    throw corruptData();
                }
                logResult("PERSIST", "APPLIED");
                return new FinalizationExecutionFactStore.PersistResult(
                        FinalizationExecutionFactStore.PersistStatus.APPLIED,
                        winner.fact());
            }
            FinalizationExecutionFactStore.PersistStatus status =
                    winner.fact().equals(candidate)
                            ? FinalizationExecutionFactStore.PersistStatus.REPLAYED
                            : FinalizationExecutionFactStore.PersistStatus.CONFLICT;
            logResult("PERSIST", status.name());
            return new FinalizationExecutionFactStore.PersistResult(status, winner.fact());
        } catch (StoredFactException failure) {
            logFailure("PERSIST", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("PERSIST", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("PERSIST");
        }
    }

    private StoredFact selectRequired(String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw corruptData();
                }
                StoredFact result = readStoredFact(rows, meetingId);
                if (rows.next()) {
                    throw corruptData();
                }
                return result;
            }
        }
    }

    private static StoredFact readStoredFact(ResultSet rows, String requestedMeetingId)
            throws SQLException {
        try {
            String meetingId = requiredText(rows, "meeting_id");
            requireStored(meetingId.equals(requestedMeetingId));
            String canonical = requiredText(rows, "canonical_json");
            String mirror = requiredText(rows, "jsonb_text");
            byte[] storedDigest = rows.getBytes("content_sha256");
            requireStored(storedDigest != null && storedDigest.length == 32);

            FinalizationExecutionFact fact = FinalizationExecutionFactJson.decode(canonical);
            requireStored(fact.meetingId().equals(meetingId));
            requireStored(canonical.equals(FinalizationExecutionFactJson.encode(fact)));

            // JSONB 文本的对象字段顺序由 PostgreSQL 规范化，不能直接与 canonical
            // 文本比较；解码并重新 canonicalize 可以同时验证 JSONB 镜像语义完整。
            FinalizationExecutionFact mirrorFact = FinalizationExecutionFactJson.decode(mirror);
            requireStored(mirrorFact.equals(fact));
            requireStored(FinalizationExecutionFactJson.encode(mirrorFact).equals(canonical));
            requireStored(MessageDigest.isEqual(storedDigest, sha256(canonical)));
            return new StoredFact(canonical, fact);
        } catch (StoredFactException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw corruptData();
        }
    }

    private void pinSearchPath() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_schema()")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("schema is unavailable");
                }
                String schema = rows.getString(1);
                if (schema == null || schema.startsWith("pg_temp")) {
                    throw new SQLException("temporary schema is not a deployment schema");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(PIN_SEARCH_PATH_SQL)) {
            statement.execute();
        }
    }

    private void requireExplicitTransaction() {
        try {
            if (connection.getAutoCommit()) {
                throw new StoredFactException(FailureKind.TRANSACTION_REQUIRED);
            }
        } catch (StoredFactException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("TRANSACTION_CHECK", failure);
        }
    }

    private static void requireMeetingId(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
    }

    private static String requiredText(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            throw corruptData();
        }
        return value;
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void requireStored(boolean condition) {
        if (!condition) {
            throw corruptData();
        }
    }

    private static StoredFactException corruptData() {
        return new StoredFactException(FailureKind.CORRUPT_DATA);
    }

    private static StoredFactException sqlFailure(String phase, SQLException failure) {
        FailureKind kind = JdbcReadLookupSupport.classify(failure)
                == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                ? FailureKind.CORRUPT_DATA : FailureKind.STORAGE_UNAVAILABLE;
        logFailure(phase, kind, JdbcReadLookupSupport.safeSqlState(failure));
        return new StoredFactException(kind);
    }

    private static StoredFactException runtimeFailure(String phase) {
        logFailure(phase, FailureKind.STORAGE_UNAVAILABLE, "N/A");
        return new StoredFactException(FailureKind.STORAGE_UNAVAILABLE);
    }

    private static void logResult(String phase, String result) {
        LOGGER.log(Level.FINE, "Execution fact store phase={0} result={1}",
                new Object[]{phase, result});
    }

    private static void logFailure(String phase, FailureKind kind, String sqlState) {
        LOGGER.log(Level.WARNING, "Execution fact store failure phase={0} kind={1} sqlState={2}",
                new Object[]{phase, kind.name(), sqlState});
    }

    private record StoredFact(String canonical, FinalizationExecutionFact fact) {
    }

    /** 固定故障类别，不携带 SQL detail、会议标识或正文。 */
    enum FailureKind {
        CORRUPT_DATA,
        STORAGE_UNAVAILABLE,
        TRANSACTION_REQUIRED
    }

    /** 供调用方区分数据损坏、数据库不可用和缺少显式事务。 */
    static final class StoredFactException extends IllegalStateException {
        private final FailureKind kind;

        private StoredFactException(FailureKind kind) {
            super(switch (kind) {
                case CORRUPT_DATA -> "execution fact is inconsistent";
                case STORAGE_UNAVAILABLE -> "execution fact storage is unavailable";
                case TRANSACTION_REQUIRED ->
                        "execution fact store requires an explicit transaction";
            });
            this.kind = kind;
        }

        FailureKind kind() {
            return kind;
        }
    }
}
