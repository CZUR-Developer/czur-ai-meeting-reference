package com.czur.reference.application.persistence.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

/** V015 音频输入所需的只读 PG16 物理门禁；不读取业务行或执行迁移。 */
final class JdbcDurableAudioSchema {
    private static final String INPUT_COLUMNS = "meeting_id:text:true:<NULL>|"
            + "writer_id:uuid:true:<NULL>|writer_epoch:bigint:true:<NULL>|"
            + "accepted_stream_sequence:bigint:true:0|accepted_audio_position_ms:bigint:true:0|"
            + "complete_last_stream_sequence:bigint:false:<NULL>|"
            + "complete_last_audio_position_ms:bigint:false:<NULL>|"
            + "complete_recording_checksum:text:false:<NULL>|"
            + "complete_recording_size_bytes:bigint:false:<NULL>|"
            + "complete_request_id:text:false:<NULL>";
    private static final String FRAME_COLUMNS = "meeting_id:text:true:<NULL>|"
            + "stream_sequence:bigint:true:<NULL>|start_position_ms:bigint:true:<NULL>|"
            + "duration_ms:integer:true:<NULL>|audio:bytea:true:<NULL>|"
            + "source_generation:bigint:true:<NULL>|format_revision:integer:true:<NULL>|"
            + "gap_duration_ms_before:bigint:true:<NULL>|flags:integer:true:<NULL>|"
            + "digest_version:smallint:true:<NULL>|content_digest:bytea:true:<NULL>";

    private JdbcDurableAudioSchema() { }

    /**
     * 核对当前 schema 的两张永久表、运行时读写列及有效 PK/FK/CHECK。
     * CHECK 固定 V015 在 PG16 的 pg_get_constraintdef 摘要，防止同名同列的
     * 弱谓词通过；只涉及三条音频约束，不复制 SQL 谓词解释器。
     */
    static boolean matches(Connection connection) throws SQLException {
        Long meeting = table(connection, "meeting");
        Long input = table(connection, "meeting_audio_input");
        Long frame = table(connection, "meeting_audio_frame");
        return meeting != null && input != null && frame != null
                && INPUT_COLUMNS.equals(columns(connection, input))
                && FRAME_COLUMNS.equals(columns(connection, frame))
                && constraints(connection, input, meeting, true)
                && constraints(connection, frame, input, false);
    }

    private static Long table(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.oid::bigint
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = current_schema() AND c.relname = ?
                   AND c.relkind = 'r' AND c.relpersistence = 'p'
                   AND NOT c.relispartition
                   AND NOT EXISTS (SELECT 1 FROM pg_inherits i
                                    WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)
                   AND NOT EXISTS (SELECT 1 FROM pg_class t
                                    WHERE t.relnamespace = pg_my_temp_schema()
                                      AND t.relname = c.relname)
                """)) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private static String columns(Connection connection, long oid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT string_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod)
                           || ':' || a.attnotnull::text || ':'
                           || coalesce(pg_get_expr(d.adbin, d.adrelid), '<NULL>'),
                           '|' ORDER BY a.attnum)
                  FROM pg_attribute a LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                """)) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static boolean constraints(Connection connection, long oid, long parent,
                                       boolean input) throws SQLException {
        Map<String, Constraint> found = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT x.conname, x.contype, x.convalidated, x.condeferrable,
                       x.condeferred, coalesce(array_to_string(x.conkey, ','), '') AS keys,
                       x.confrelid::bigint AS parent,
                       coalesce(array_to_string(x.confkey, ','), '') AS parent_keys,
                       x.confdeltype, x.confupdtype, x.confmatchtype,
                       md5(pg_get_constraintdef(x.oid, TRUE)) AS definition_md5,
                       coalesce(i.indisvalid AND i.indisready AND i.indislive, FALSE) AS index_valid
                  FROM pg_constraint x LEFT JOIN pg_index i ON i.indexrelid = x.conindid
                 WHERE x.conrelid = ?
                """)) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Constraint value = new Constraint(rows.getString("contype"),
                            rows.getBoolean("convalidated"), rows.getBoolean("condeferrable"),
                            rows.getBoolean("condeferred"), rows.getString("keys"),
                            rows.getLong("parent"), rows.getString("parent_keys"),
                            rows.getString("confdeltype"), rows.getString("confupdtype"),
                            rows.getString("confmatchtype"),
                            rows.getString("definition_md5"),
                            rows.getBoolean("index_valid"));
                    if (found.put(rows.getString("conname"), value) != null) return false;
                }
            }
        }
        String prefix = input ? "meeting_audio_input" : "meeting_audio_frame";
        if (found.size() != (input ? 4 : 3)
                || !primary(found.get(prefix + "_pkey"), input ? "1" : "1,2")) {
            return false;
        }
        Constraint foreign = found.get(prefix + (input ? "_meeting_fk" : "_input_fk"));
        if (!valid(foreign, "f") || !"1".equals(foreign.keys())
                || foreign.parent() != parent || !"1".equals(foreign.parentKeys())
                || !"r".equals(foreign.deleteAction())
                || !"a".equals(foreign.updateAction())
                || !"s".equals(foreign.matchType())) {
            return false;
        }
        return input
                ? check(found.get(prefix + "_cursor_ck"), "3,4,5",
                        "eee3fced06d1ae74cf09122126c3e4cf")
                    && check(found.get(prefix + "_complete_ck"), "6,7,8,9,10,4,5",
                        "e6f6c2043f0ba1c8748622cbb554701a")
                : check(found.get(prefix + "_shape_ck"), "2,3,4,5,6,7,8,9,10,11",
                        "77f6ecd2579954b85dfcac76cfa8deda");
    }

    private static boolean valid(Constraint value, String type) {
        return value != null && type.equals(value.type()) && value.validated()
                && !value.deferrable() && !value.deferred();
    }

    private static boolean primary(Constraint value, String keys) {
        return valid(value, "p") && keys.equals(value.keys()) && value.indexValid();
    }

    private static boolean check(Constraint value, String keys, String definitionMd5) {
        return valid(value, "c") && keys.equals(value.keys())
                && definitionMd5.equals(value.definitionMd5());
    }

    private record Constraint(String type, boolean validated, boolean deferrable,
                              boolean deferred, String keys, long parent, String parentKeys,
                              String deleteAction, String updateAction, String matchType,
                              String definitionMd5, boolean indexValid) { }
}
