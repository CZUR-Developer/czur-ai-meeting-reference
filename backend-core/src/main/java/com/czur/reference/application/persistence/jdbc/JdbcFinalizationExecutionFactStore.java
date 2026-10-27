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
 * 一个数据库事务。写入命中唯一键时无论是否为本次首次插入都重新读回完整行，
 * 再用 canonical JSON、JSON 镜像、SHA-256 和领域对象四重校验裁决 APPLIED、
 * REPLAYED 或 CONFLICT。</p>
 *
 * <p>本类只按当前数据库内的物理表名访问事实表，不改变调用方事务的提交/回滚责任。</p>
 */
final class JdbcFinalizationExecutionFactStore
        implements FinalizationExecutionFactStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcFinalizationExecutionFactStore.class.getName());

    private static final String FIND_SQL = """
            SELECT meeting_id, canonical_json, %s AS jsonb_text,
                   content_sha256
              FROM %s
             WHERE meeting_id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO %s (
                meeting_id, canonical_json, execution_fact_json, content_sha256)
            VALUES (?, ?, %s, ?)
            """;

    private final Connection connection;
    private final JdbcDatabaseEngine engine;

    /** 绑定调用方当前连接；构造过程不获取或释放连接。 */
    JdbcFinalizationExecutionFactStore(Connection connection) {
        this(connection, JdbcDatabaseEngine.MYSQL);
    }

    /** 显式选择引擎；连接及事务仍完全由外层工作单元持有。 */
    JdbcFinalizationExecutionFactStore(Connection connection, JdbcDatabaseEngine engine) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Optional<FinalizationExecutionFact> find(String meetingId) {
        requireMeetingId(meetingId);
        requireExplicitTransaction();
        try {
            try (PreparedStatement statement = connection.prepareStatement(findSql(false))) {
                JdbcValues.setText(statement, 1, engine, meetingId);
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
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL.formatted(
                    JdbcDatabaseTable.FINALIZATION_EXECUTION_FACT.physicalName(engine),
                    JdbcValues.jsonParameter()))) {
                JdbcValues.setText(statement, 1, engine, candidate.meetingId());
                statement.setString(2, canonical);
                statement.setString(3, canonical);
                statement.setBytes(4, digest);
                try {
                    inserted = statement.executeUpdate();
                } catch (SQLException duplicate) {
                    if (!JdbcSqlErrors.isDuplicate(engine, duplicate)) throw duplicate;
                    inserted = 0;
                }
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
        try (PreparedStatement statement = connection.prepareStatement(findSql(true))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
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

            // JSON 列读出的对象字段顺序会被重新规范化，不能直接与 canonical
            // 文本比较；解码并重新 canonicalize 可以同时验证 JSON 镜像语义完整。
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
        String value = "meeting_id".equals(column)
                ? JdbcValues.text(rows, column) : rows.getString(column);
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

    /** 普通 find 可用于只读快照；插入裁决才使用 MySQL 当前读，避免 RR 旧快照。 */
    private String findSql(boolean currentRead) {
        return FIND_SQL.formatted(JdbcValues.jsonText("execution_fact_json"),
                JdbcDatabaseTable.FINALIZATION_EXECUTION_FACT.physicalName(engine))
                + (currentRead ? " FOR UPDATE" : "");
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
