package com.czur.reference.application.persistence.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** 缺失同键事实的事务内串行化。连接/事务生命周期始终由原工作单元拥有。 */
final class JdbcIdempotencyLock {
    private JdbcIdempotencyLock() { }

    static void acquire(Connection connection, JdbcDatabaseEngine engine, String scope, String key)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("idempotency lock requires explicit transaction");
        }
        String table = JdbcDatabaseTable.IDEMPOTENCY_LOCK.physicalName(engine);
        // 无 INSERT IGNORE；只有这个辅助表的二元主键可发生 no-op，影响行数不作事实依据。
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (scope, idempotency_key) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE idempotency_key = idempotency_key")) {
            JdbcValues.setText(statement, 1, engine, scope);
            JdbcValues.setText(statement, 2, engine, key);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT scope FROM " + table + " WHERE scope = ? AND idempotency_key = ? FOR UPDATE")) {
            JdbcValues.setText(statement, 1, engine, scope);
            JdbcValues.setText(statement, 2, engine, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !scope.equals(JdbcValues.text(rows, "scope")) || rows.next()) {
                    throw new SQLException("idempotency lock row is inconsistent");
                }
            }
        }
    }
}
