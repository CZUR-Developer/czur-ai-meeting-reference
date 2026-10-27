package com.czur.reference.application.persistence.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

/** 当前会议链的窄 JDBC 值映射；不改写 SQL，也不猜测连接所属引擎。 */
final class JdbcValues {
    private JdbcValues() { }

    /** 数据库时间列只持久化 UTC 微秒，避免 JVM 默认时区参与解释。 */
    static void setInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)), utc());
        }
    }

    static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column, utc());
        return value == null ? null : value.toInstant();
    }

    /**
     * 仅用于固定基线中 VARBINARY 标识/范围/幂等键的 UTF-8 精确存储。
     * 不用于 canonical 原文、JSON 或其它正文；这些列沿用各原语的 setString。
     * 非 MySQL 分支的相应文本标识仍使用原 setString，不能把所有字段一律二进制化。
     */
    static void setText(PreparedStatement statement, int index,
                        JdbcDatabaseEngine engine, String value) throws SQLException {
        Objects.requireNonNull(engine, "engine");
        if (engine == JdbcDatabaseEngine.MYSQL) {
            statement.setBytes(index, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
        } else {
            statement.setString(index, value);
        }
    }

    static String text(ResultSet rows, String column) throws SQLException {
        byte[] value = rows.getBytes(column);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /** MySQL 5.7 的 JSON 参数占位符；JSON 列不接受裸字符串类型推断。 */
    static String jsonParameter() {
        return "CAST(? AS JSON)";
    }

    /** column 仅由编译期 SQL 列名提供，不接收协议或用户输入。 */
    static String jsonText(String column) {
        return column;
    }

    /** LONGTEXT 的 PAD SPACE 比较不能证明规范原文相等，明确保留 Java 精确比较。 */
    static boolean sameCanonicalText(String stored, String requested) {
        return Objects.equals(stored, requested);
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
