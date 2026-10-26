package com.czur.reference.application.persistence.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Objects;

/** 两个只读 typed lookup 共用的摘要比较和 SQL 故障分类。 */
final class JdbcReadLookupSupport {
    private JdbcReadLookupSupport() {
    }

    /** 使用 JDK 常量时间字节比较，避免普通 String equals 的提前退出。 */
    static boolean sameDigest(String stored, String requested) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(requested, "requested");
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                requested.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按冻结规则分类 JDBC 读取错误。
     *
     * <p>SQLState 22 数据异常优先认定为已有事实不一致；连接、事务回滚、资源、
     * 限额、对象状态和系统错误，或 JDBC 显式 transient/recoverable 异常，允许
     * 重试。其余 SQL 错误仍是不可用，但不能无依据承诺重试有效。</p>
     */
    static FailureKind classify(SQLException failure) {
        return classify(JdbcDatabaseEngine.MYSQL, failure);
    }

    static FailureKind classify(JdbcDatabaseEngine engine, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        String sqlState = failure.getSQLState();
        if (sqlState != null && sqlState.startsWith("22")) {
            return FailureKind.INCONSISTENT;
        }
        if (JdbcSqlErrors.isTransientTransaction(engine, failure)) {
            return FailureKind.RETRYABLE_UNAVAILABLE;
        }
        if (failure instanceof SQLTransientException
                || failure instanceof SQLRecoverableException) {
            return FailureKind.RETRYABLE_UNAVAILABLE;
        }
        if (sqlState != null && sqlState.length() >= 2) {
            String category = sqlState.substring(0, 2);
            if (switch (category) {
                case "08", "40", "53", "54", "55", "57", "58" -> true;
                default -> false;
            }) {
                return FailureKind.RETRYABLE_UNAVAILABLE;
            }
        }
        return FailureKind.NON_RETRYABLE_UNAVAILABLE;
    }

    /** 日志只允许输出格式正确的 SQLState，不输出异常消息。 */
    static String safeSqlState(SQLException failure) {
        String value = failure.getSQLState();
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.matches("[A-Z0-9]{5}") ? value : "INVALID";
    }

    enum FailureKind {
        INCONSISTENT,
        RETRYABLE_UNAVAILABLE,
        NON_RETRYABLE_UNAVAILABLE
    }
}
