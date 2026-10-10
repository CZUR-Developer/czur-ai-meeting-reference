package com.czur.reference.application.persistence.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * V014 实时续期事实的只读 PostgreSQL 16 物理契约探针。
 *
 * <p>此探针只在显式启用续期/WSS 时由可选 readiness 调用；默认关闭的旧 HTTP、
 * V013 Create 和 T14 Worker 不因尚未迁移 V014 而停止启动。探针不读业务行、
 * 不执行 DDL/DML，也不把 schema、SQL 或凭据写入日志。列、完整约束定义、
 * 外键动作和三枚索引全部与 V014 迁移的精确重跑门禁一致，不能只凭同名表放行。</p>
 */
final class JdbcRealtimeCredentialSchema {
    private static final String TABLE = "meeting_realtime_token_grant";
    private static final String EXPECTED_COLUMNS =
            "meeting_id:text:true:<NULL>::|idempotency_key:text:true:<NULL>::|"
                    + "grant_json:text:true:<NULL>::|grant_sha256:bytea:true:<NULL>::|"
                    + "token_sha256:bytea:true:<NULL>::|"
                    + "issued_at:timestamp with time zone:true:<NULL>::|"
                    + "expires_at:timestamp with time zone:true:<NULL>::";
    private static final Map<String, String> EXPECTED_CHECKS = Map.of(
            "meeting_realtime_token_grant_key_ck",
            "CHECK ((length(btrim(meeting_id)) > 0 AND length(idempotency_key) >= 16 "
                    + "AND length(idempotency_key) <= 128) IS TRUE)",
            "meeting_realtime_token_grant_digest_ck",
            "CHECK ((jsonb_typeof(grant_json::jsonb) = 'object'::text AND "
                    + "(grant_json::jsonb ->> 'meetingId'::text) = meeting_id AND "
                    + "(grant_json::jsonb ->> 'streamId'::text) = meeting_id AND "
                    + "length(grant_json::jsonb ->> 'sessionToken'::text) > 0 AND "
                    + "((grant_json::jsonb ->> 'expiresAt'::text)::timestamp with time zone) "
                    + "= expires_at AND octet_length(grant_sha256) = 32 AND "
                    + "grant_sha256 = sha256(convert_to(grant_json, 'UTF8'::name)) AND "
                    + "octet_length(token_sha256) = 32 AND "
                    + "token_sha256 = sha256(convert_to(grant_json::jsonb ->> "
                    + "'sessionToken'::text, 'UTF8'::name))) IS TRUE)",
            "meeting_realtime_token_grant_time_ck",
            "CHECK ((expires_at > issued_at) IS TRUE)");
    private static final Map<String, IndexContract> EXPECTED_INDEXES = Map.of(
            "meeting_realtime_token_grant_pkey", new IndexContract("1 2", 2, true, true),
            "meeting_realtime_token_grant_token_uq", new IndexContract("5", 1, true, false),
            "meeting_realtime_token_grant_active_idx", new IndexContract("1 7", 2, false, false));

    private JdbcRealtimeCredentialSchema() {
    }

    /**
     * 在调用方的只读连接上验证完整 V014 catalog；异常由 readiness 映射为不可用。
     */
    static boolean matches(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        long tableOid;
        long meetingOid;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT to_regclass(format('%I.%I', current_schema(), ?))::oid::bigint,
                       to_regclass(format('%I.%I', current_schema(), 'meeting'))::oid::bigint,
                       to_regclass(format('%I.%I', 'pg_temp', ?)) IS NOT NULL
                """)) {
            statement.setString(1, TABLE);
            statement.setString(2, TABLE);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                tableOid = rows.getLong(1);
                if (rows.wasNull()) {
                    return false;
                }
                meetingOid = rows.getLong(2);
                if (rows.wasNull() || rows.getBoolean(3) || rows.next()) {
                    return false;
                }
            }
        }
        return standaloneTable(connection, tableOid)
                && standaloneTable(connection, meetingOid)
                && EXPECTED_COLUMNS.equals(columnSignature(connection, tableOid))
                && exactConstraints(connection, tableOid, meetingOid)
                && exactIndexes(connection, tableOid);
    }

    private static boolean standaloneTable(Connection connection, long oid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.relkind = 'r' AND c.relpersistence = 'p'
                       AND NOT c.relispartition
                       AND NOT EXISTS (
                           SELECT 1 FROM pg_inherits i
                            WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)
                  FROM pg_class c WHERE c.oid = ?
                """)) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1) && !rows.next();
            }
        }
    }

    private static String columnSignature(Connection connection, long oid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT string_agg(
                           a.attname || ':' || format_type(a.atttypid, a.atttypmod)
                           || ':' || a.attnotnull::text || ':'
                           || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>')
                           || ':' || a.attidentity::text || ':' || a.attgenerated::text,
                           '|' ORDER BY a.attnum)
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                """)) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static boolean exactConstraints(Connection connection, long oid, long meetingOid)
            throws SQLException {
        Map<String, Constraint> found = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname, contype, convalidated, condeferrable, condeferred,
                       coalesce(array_to_string(conkey, ','), '') AS source_keys,
                       confrelid::bigint AS referenced_relation,
                       coalesce(array_to_string(confkey, ','), '') AS target_keys,
                       confdeltype, confupdtype, confmatchtype,
                       pg_get_constraintdef(oid, TRUE) AS definition
                  FROM pg_constraint WHERE conrelid = ?
                """)) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Constraint value = new Constraint(
                            rows.getString("contype"), rows.getBoolean("convalidated"),
                            rows.getBoolean("condeferrable"), rows.getBoolean("condeferred"),
                            rows.getString("source_keys"), rows.getLong("referenced_relation"),
                            rows.getString("target_keys"), rows.getString("confdeltype"),
                            rows.getString("confupdtype"), rows.getString("confmatchtype"),
                            rows.getString("definition"));
                    if (found.put(rows.getString("conname"), value) != null) {
                        return false;
                    }
                }
            }
        }
        if (found.size() != 6 || !found.keySet().containsAll(EXPECTED_CHECKS.keySet())) {
            return false;
        }
        for (Map.Entry<String, String> check : EXPECTED_CHECKS.entrySet()) {
            Constraint actual = found.get(check.getKey());
            if (!actual.validated() || actual.deferrable() || actual.deferred()
                    || !"c".equals(actual.type())
                    || !check.getValue().equals(actual.definition())) {
                return false;
            }
        }
        Constraint primary = found.get("meeting_realtime_token_grant_pkey");
        Constraint unique = found.get("meeting_realtime_token_grant_token_uq");
        Constraint foreign = found.get("meeting_realtime_token_grant_meeting_fk");
        return key(primary, "p", "1,2", "PRIMARY KEY (meeting_id, idempotency_key)")
                && key(unique, "u", "5", "UNIQUE (token_sha256)")
                && key(foreign, "f", "1", "FOREIGN KEY (meeting_id) "
                        + "REFERENCES meeting(meeting_id) ON DELETE RESTRICT")
                && foreign.referencedRelation() == meetingOid
                && "1".equals(foreign.targetKeys())
                && "r".equals(foreign.deleteAction())
                && "a".equals(foreign.updateAction())
                && "s".equals(foreign.matchType());
    }

    private static boolean key(Constraint value, String type, String source, String definition) {
        return value != null && value.validated() && !value.deferrable() && !value.deferred()
                && type.equals(value.type()) && source.equals(value.sourceKeys())
                && definition.equals(value.definition());
    }

    private static boolean exactIndexes(Connection connection, long oid) throws SQLException {
        Map<String, IndexContract> found = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.relname, i.relkind, i.relpersistence,
                       i.relnamespace = t.relnamespace AS same_namespace,
                       am.amname, x.indkey::text AS keys, x.indnkeyatts, x.indnatts,
                       x.indisunique, x.indisprimary, x.indimmediate,
                       x.indnullsnotdistinct, x.indisvalid, x.indisready, x.indislive,
                       x.indexprs IS NULL AS no_expression,
                       x.indpred IS NULL AS no_predicate
                  FROM pg_index x
                  JOIN pg_class i ON i.oid = x.indexrelid
                  JOIN pg_class t ON t.oid = x.indrelid
                  JOIN pg_am am ON am.oid = i.relam
                 WHERE x.indrelid = ?
                """)) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!"i".equals(rows.getString("relkind"))
                            || !"p".equals(rows.getString("relpersistence"))
                            || !rows.getBoolean("same_namespace")
                            || !"btree".equals(rows.getString("amname"))
                            || rows.getInt("indnkeyatts") != rows.getInt("indnatts")
                            || !rows.getBoolean("indimmediate")
                            || rows.getBoolean("indnullsnotdistinct")
                            || !rows.getBoolean("indisvalid")
                            || !rows.getBoolean("indisready")
                            || !rows.getBoolean("indislive")
                            || !rows.getBoolean("no_expression")
                            || !rows.getBoolean("no_predicate")) {
                        return false;
                    }
                    IndexContract value = new IndexContract(rows.getString("keys"),
                            rows.getInt("indnkeyatts"), rows.getBoolean("indisunique"),
                            rows.getBoolean("indisprimary"));
                    if (found.put(rows.getString("relname"), value) != null) {
                        return false;
                    }
                }
            }
        }
        return found.equals(EXPECTED_INDEXES);
    }

    private record Constraint(String type, boolean validated, boolean deferrable,
                              boolean deferred, String sourceKeys, long referencedRelation,
                              String targetKeys, String deleteAction, String updateAction,
                              String matchType, String definition) {
    }

    private record IndexContract(String keys, int attributes, boolean unique, boolean primary) {
    }
}
