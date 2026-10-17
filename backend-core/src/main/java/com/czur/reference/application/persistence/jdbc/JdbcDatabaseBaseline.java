package com.czur.reference.application.persistence.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 当前 V001 的最小只读基线检查，不承担会议业务或 PG 约束指纹验收。
 * MySQL 只核对独立登记与固定表的存在／InnoDB，不复制第二套 catalog 契约体系。
 */
final class JdbcDatabaseBaseline {
    private static final Logger LOGGER = Logger.getLogger(JdbcDatabaseBaseline.class.getName());
    private static final Set<String> REQUIRED_MIGRATIONS = Set.of("V001__initial_schema");

    private JdbcDatabaseBaseline() {
    }

    static DatabaseReadiness check(DataSource dataSource, JdbcDatabaseEngine engine) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement probe = connection.prepareStatement("SELECT 1");
                 ResultSet result = probe.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    return DatabaseReadiness.unavailable("只读连接探针异常");
                }
            }
            String migrationTable = JdbcDatabaseTable.SCHEMA_MIGRATIONS.physicalName(engine);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT version FROM " + migrationTable + " WHERE version = ?")) {
                query.setString(1, "V001__initial_schema");
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return DatabaseReadiness.migrationMissing(REQUIRED_MIGRATIONS);
                    }
                }
            } catch (SQLException failure) {
                if ((engine == JdbcDatabaseEngine.MYSQL && failure.getErrorCode() == 1146)
                        || (engine == JdbcDatabaseEngine.POSTGRESQL && "42P01".equals(failure.getSQLState()))) {
                    return DatabaseReadiness.migrationMissing(REQUIRED_MIGRATIONS);
                }
                throw failure;
            }
            if (!hasRequiredTables(connection, engine)) {
                return DatabaseReadiness.schemaInvalid("当前基线必要表缺失或引擎不符");
            }
            LOGGER.log(Level.FINE, "数据库最小基线只读检查通过 engine={0}", engine);
            return DatabaseReadiness.success();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "数据库最小基线检查不可用 engine={0} exception={1}",
                    new Object[]{engine, failure.getClass().getSimpleName()});
            return DatabaseReadiness.unavailable("数据库最小基线探针不可用");
        }
    }

    private static boolean hasRequiredTables(Connection connection, JdbcDatabaseEngine engine)
            throws SQLException {
        List<String> tables = Arrays.stream(JdbcDatabaseTable.values())
                .filter(table -> table.requiredForBaseline(engine))
                .map(table -> table.physicalName(engine)).toList();
        Set<String> missing = new HashSet<>(tables);
        String placeholders = String.join(",", java.util.Collections.nCopies(tables.size(), "?"));
        String schema = engine == JdbcDatabaseEngine.MYSQL ? "DATABASE()" : "current_schema()";
        String engineColumn = engine == JdbcDatabaseEngine.MYSQL ? "ENGINE" : "NULL AS ENGINE";
        String sql = "SELECT TABLE_NAME, TABLE_TYPE, " + engineColumn
                + " FROM information_schema.tables WHERE TABLE_SCHEMA = " + schema
                + " AND TABLE_NAME IN (" + placeholders + ")";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            for (int index = 0; index < tables.size(); index++) {
                query.setString(index + 1, tables.get(index));
            }
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (!"BASE TABLE".equals(rows.getString("TABLE_TYPE"))
                            || (engine == JdbcDatabaseEngine.MYSQL
                            && !"InnoDB".equalsIgnoreCase(rows.getString("ENGINE")))) {
                        return false;
                    }
                    missing.remove(rows.getString("TABLE_NAME"));
                }
            }
        }
        return missing.isEmpty();
    }
}
