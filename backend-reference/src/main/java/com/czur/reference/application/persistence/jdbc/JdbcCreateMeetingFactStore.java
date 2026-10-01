package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.protocol.json.CreateMeetingRequestFactJson;
import com.czur.reference.protocol.json.CreateMeetingResponseFactJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V013 完整 Create 事实的连接级 JDBC 原语。
 *
 * <p>实例只绑定调用方已经取得的 JDBC 连接；不会创建、提交、回滚、恢复、关闭
 * 连接或改变 auto-commit。每次操作都要求显式事务，并把 search_path 固定到当前
 * 部署 schema 后接 {@code pg_temp}，防止同名临时表遮蔽物理事实表。调用方负责把
 * 本 Store 与会议行、权益 Reservation 及 Outbox 放入更大的原子事务。</p>
 *
 * <p>写入保存新 codec 产生的规范请求/响应 JSON、请求事实摘要和响应 UTF-8
 * SHA-256；使用 {@code ON CONFLICT DO NOTHING} 后必定重读赢家，再按完整规范正文、
 * 摘要、会议标识和创建时间分类 APPLIED、REPLAYED 或 CONFLICT。读取逐列核对并
 * 严格解码，任一正文、哈希、schema、scope/key 或响应会议标识损坏都会 fail closed。
 * 日志只包含阶段、结果类别、故障类别和 SQLState，不输出正文、Token、scope、key 或
 * 会议 ID。</p>
 */
final class JdbcCreateMeetingFactStore implements CreateMeetingFactStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcCreateMeetingFactStore.class.getName());

    private static final String PIN_SEARCH_PATH_SQL = """
            SELECT set_config(
                'search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;
    private static final String FIND_SQL = """
            SELECT scope, idempotency_key,
                   request_schema_version, request_digest, request_json,
                   response_schema_version, response_status, response_type,
                   response_json, response_sha256, meeting_id, created_at
              FROM meeting_create_fact
             WHERE scope = ? AND idempotency_key = ?
            """;
    private static final String FIND_BY_MEETING_SQL = """
            SELECT scope, idempotency_key,
                   request_schema_version, request_digest, request_json,
                   response_schema_version, response_status, response_type,
                   response_json, response_sha256, meeting_id, created_at
              FROM meeting_create_fact
             WHERE meeting_id = ?
             FOR SHARE
            """;
    private static final String PROBE_DIGEST_SQL = """
            SELECT request_schema_version, request_digest, request_json
              FROM meeting_create_fact
             WHERE scope = ? AND idempotency_key = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO meeting_create_fact (
                scope, idempotency_key,
                request_schema_version, request_digest, request_json,
                response_schema_version, response_status, response_type,
                response_json, response_sha256, meeting_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (scope, idempotency_key) DO NOTHING
            """;

    private final Connection connection;

    /** 绑定调用方当前连接；构造过程不访问数据库，也不接管连接生命周期。 */
    JdbcCreateMeetingFactStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 只读取并验证赢家请求侧事实，不读取或解码响应正文。
     *
     * <p>先严格解码规范请求 JSON 并重算摘要，避免合法形状的坏 digest 被误报
     * 为幂等冲突；上层工作单元持有 scope/key 的事务锁后调用：真实异摘要
     * 立即返回冲突，同摘要才进入 {@link #find(String, String)} 的响应校验。
     * 该探针不承诺
     * 单独完成并发串行化，也不修改事实。</p>
     */
    Optional<String> probeRequestDigest(String scope, String key) {
        requireLookup(scope, key);
        requireExplicitTransaction();
        try {
            pinSearchPath();
            try (PreparedStatement statement = connection.prepareStatement(PROBE_DIGEST_SQL)) {
                statement.setString(1, scope);
                statement.setString(2, key);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    int version = requiredInt(rows, "request_schema_version");
                    String digest = requiredText(rows, "request_digest");
                    String requestJson = requiredText(rows, "request_json");
                    requireValidRequestFact(version, digest, requestJson, key);
                    if (rows.next()) {
                        throw corruptData();
                    }
                    return Optional.of(digest);
                }
            }
        } catch (StoredFactException failure) {
            logFailure("PROBE", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("PROBE", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("PROBE");
        }
    }

    @Override
    public Optional<CreateMeetingFactStore.Record> find(String scope, String key) {
        requireLookup(scope, key);
        requireExplicitTransaction();
        try {
            pinSearchPath();
            try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
                statement.setString(1, scope);
                statement.setString(2, key);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        logResult("FIND", "EMPTY");
                        return Optional.empty();
                    }
                    StoredFact stored = readStoredFact(rows, scope, key);
                    if (rows.next()) {
                        throw corruptData();
                    }
                    logResult("FIND", "PRESENT");
                    return Optional.of(stored.record());
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

    /**
     * 在 Start 已锁定 Meeting 的同一事务中，按 V013 唯一会议标识读取首次事实。
     *
     * <p>此方法仍逐列执行与 scope/key 查询完全相同的严格正文、版本和哈希校验；
     * 不因索引唯一而信任 JSON 或跳过二次行数检查。旧 v1 Start 不应调用本方法，
     * 因为旧数据库可能尚未安装 V013。</p>
     */
    Optional<CreateMeetingFactStore.Record> findByMeetingId(String meetingId) {
        requireText("meetingId", meetingId);
        requireExplicitTransaction();
        try {
            pinSearchPath();
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_MEETING_SQL)) {
                statement.setString(1, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        logResult("FIND_BY_MEETING", "EMPTY");
                        return Optional.empty();
                    }
                    String scope = requiredText(rows, "scope");
                    String key = requiredText(rows, "idempotency_key");
                    StoredFact stored = readStoredFact(rows, scope, key);
                    requireStored(meetingId.equals(stored.record().response().meetingId()));
                    if (rows.next()) {
                        throw corruptData();
                    }
                    logResult("FIND_BY_MEETING", "PRESENT");
                    return Optional.of(stored.record());
                }
            }
        } catch (StoredFactException failure) {
            logFailure("FIND_BY_MEETING", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            if ("42P01".equals(failure.getSQLState())) {
                logFailure("FIND_BY_MEETING", FailureKind.CORRUPT_DATA, "42P01");
                throw corruptData();
            }
            throw sqlFailure("FIND_BY_MEETING", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("FIND_BY_MEETING");
        }
    }

    @Override
    public CreateMeetingFactStore.PersistResult persist(
            CreateMeetingFactStore.Record candidate) {
        Objects.requireNonNull(candidate, "candidate");
        requireExplicitTransaction();
        String requestJson = CreateMeetingRequestFactJson.encode(candidate.request());
        String responseJson = CreateMeetingResponseFactJson.encode(candidate.response());
        byte[] responseSha256 = sha256(responseJson);
        try {
            pinSearchPath();
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                statement.setString(1, candidate.scope());
                statement.setString(2, candidate.key());
                statement.setInt(3, candidate.request().schemaVersion());
                statement.setString(4, candidate.request().canonicalRequestDigest());
                statement.setString(5, requestJson);
                statement.setInt(6, CreateMeetingResponseFactJson.SCHEMA_VERSION);
                statement.setInt(7, 201);
                statement.setString(8, "SUCCESS");
                statement.setString(9, responseJson);
                statement.setBytes(10, responseSha256);
                statement.setString(11, candidate.response().meetingId());
                statement.setTimestamp(12, Timestamp.from(candidate.createdAt()));
                inserted = statement.executeUpdate();
            }

            StoredFact winner = selectRequired(candidate.scope(), candidate.key());
            if (inserted == 1) {
                if (!sameCandidate(winner, candidate, requestJson, responseJson,
                        responseSha256)) {
                    throw corruptData();
                }
                logResult("PERSIST", "APPLIED");
                return new CreateMeetingFactStore.PersistResult(
                        CreateMeetingFactStore.PersistStatus.APPLIED, winner.record());
            }
            CreateMeetingFactStore.PersistStatus status = sameCandidate(
                    winner, candidate, requestJson, responseJson, responseSha256)
                    ? CreateMeetingFactStore.PersistStatus.REPLAYED
                    : CreateMeetingFactStore.PersistStatus.CONFLICT;
            logResult("PERSIST", status.name());
            return new CreateMeetingFactStore.PersistResult(status, winner.record());
        } catch (StoredFactException failure) {
            logFailure("PERSIST", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("PERSIST", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("PERSIST");
        }
    }

    private StoredFact selectRequired(String scope, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw corruptData();
                }
                StoredFact result = readStoredFact(rows, scope, key);
                if (rows.next()) {
                    throw corruptData();
                }
                return result;
            }
        }
    }

    private static StoredFact readStoredFact(
            ResultSet rows, String requestedScope, String requestedKey) throws SQLException {
        try {
            String scope = requiredText(rows, "scope");
            String key = requiredText(rows, "idempotency_key");
            requireStored(scope.equals(requestedScope) && key.equals(requestedKey));

            int requestSchemaVersion = requiredInt(rows, "request_schema_version");
            String requestDigest = requiredText(rows, "request_digest");
            String requestJson = requiredText(rows, "request_json");
            int responseSchemaVersion = requiredInt(rows, "response_schema_version");
            int responseStatus = requiredInt(rows, "response_status");
            String responseType = requiredText(rows, "response_type");
            String responseJson = requiredText(rows, "response_json");
            byte[] responseSha256 = rows.getBytes("response_sha256");
            String meetingId = requiredText(rows, "meeting_id");
            Timestamp timestamp = rows.getTimestamp("created_at");
            requireStored(timestamp != null);

            requireStored(requestSchemaVersion == CreateMeetingRequestFactJson.SCHEMA_VERSION);
            requireStored(responseSchemaVersion == CreateMeetingResponseFactJson.SCHEMA_VERSION);
            requireStored(responseStatus == 201 && "SUCCESS".equals(responseType));
            requireStored(responseSha256 != null && responseSha256.length == 32);

            CreateMeetingRequestFact request = requireValidRequestFact(
                    requestSchemaVersion, requestDigest, requestJson, key);

            CreateMeetingResponseFact response = CreateMeetingResponseFactJson.decode(responseJson);
            requireStored(response.meetingId().equals(meetingId));
            requireStored(responseJson.equals(CreateMeetingResponseFactJson.encode(response)));
            requireStored(MessageDigest.isEqual(responseSha256, sha256(responseJson)));
            requireStored(responseStatus == 201);

            CreateMeetingFactStore.Record record = new CreateMeetingFactStore.Record(
                    scope, key, request, response, timestamp.toInstant());
            return new StoredFact(record, requestJson, responseJson, responseSha256);
        } catch (StoredFactException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw corruptData();
        }
    }

    private static boolean sameCandidate(
            StoredFact winner,
            CreateMeetingFactStore.Record candidate,
            String requestJson,
            String responseJson,
            byte[] responseSha256) {
        CreateMeetingFactStore.Record record = winner.record();
        return record.scope().equals(candidate.scope())
                && record.key().equals(candidate.key())
                && record.createdAt().equals(candidate.createdAt())
                && winner.requestJson().equals(requestJson)
                && winner.responseJson().equals(responseJson)
                && MessageDigest.isEqual(winner.responseSha256(), responseSha256)
                && record.request().canonicalRequestDigest()
                .equals(candidate.request().canonicalRequestDigest())
                && record.response().meetingId().equals(candidate.response().meetingId());
    }

    /** 请求侧的完整规范校验供摘要探针和全行读取共用；绝不接触响应正文。 */
    private static CreateMeetingRequestFact requireValidRequestFact(
            int schemaVersion, String digest, String requestJson, String key) {
        try {
            requireStored(schemaVersion == CreateMeetingRequestFactJson.SCHEMA_VERSION);
            requireStored(digest.matches("[0-9a-f]{64}"));
            CreateMeetingRequestFact request = CreateMeetingRequestFactJson.decode(requestJson);
            requireStored(request.schemaVersion() == schemaVersion);
            requireStored(request.canonicalRequestDigest().equals(digest));
            requireStored(requestJson.equals(CreateMeetingRequestFactJson.encode(request)));
            requireStored(request.requestSnapshot().idempotencyKey().equals(key));
            return request;
        } catch (StoredFactException failure) {
            throw failure;
        } catch (RuntimeException failure) {
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

    private static void requireLookup(String scope, String key) {
        requireText("scope", scope);
        requireText("key", key);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String requiredText(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            throw corruptData();
        }
        return value;
    }

    private static int requiredInt(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        if (rows.wasNull()) {
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
        LOGGER.log(Level.FINE, "Create fact store phase={0} result={1}",
                new Object[]{phase, result});
    }

    private static void logFailure(String phase, FailureKind kind, String sqlState) {
        LOGGER.log(Level.WARNING,
                "Create fact store failure phase={0} kind={1} sqlState={2}",
                new Object[]{phase, kind.name(), sqlState});
    }

    private record StoredFact(
            CreateMeetingFactStore.Record record,
            String requestJson,
            String responseJson,
            byte[] responseSha256) {
        private StoredFact {
            responseSha256 = responseSha256.clone();
        }

        @Override
        public byte[] responseSha256() {
            return responseSha256.clone();
        }
    }

    /** 固定故障类别，不携带 SQL detail、scope、key 或 JSON 正文。 */
    enum FailureKind {
        CORRUPT_DATA,
        STORAGE_UNAVAILABLE,
        TRANSACTION_REQUIRED
    }

    /** 供调用方区分事实损坏、数据库不可用和缺少显式事务。 */
    static final class StoredFactException extends IllegalStateException {
        private final FailureKind kind;

        private StoredFactException(FailureKind kind) {
            super(switch (kind) {
                case CORRUPT_DATA -> "Create fact is inconsistent";
                case STORAGE_UNAVAILABLE -> "Create fact storage is unavailable";
                case TRANSACTION_REQUIRED ->
                        "Create fact store requires an explicit transaction";
            });
            this.kind = kind;
        }

        FailureKind kind() {
            return kind;
        }
    }
}
