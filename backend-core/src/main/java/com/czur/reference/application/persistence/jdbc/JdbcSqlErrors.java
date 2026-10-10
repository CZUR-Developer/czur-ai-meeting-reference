package com.czur.reference.application.persistence.jdbc;

import java.sql.SQLException;
import java.util.Objects;

/** 必要的引擎错误码判断；调用点仍负责限定哪一个唯一事实允许重放。 */
final class JdbcSqlErrors {
    private JdbcSqlErrors() { }

    /** MySQL 23000 同时包含外键错误，因此只接受确切的重复键 1062。 */
    static boolean isDuplicate(JdbcDatabaseEngine engine, SQLException failure) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(failure, "failure");
        return engine == JdbcDatabaseEngine.MYSQL
                ? failure.getErrorCode() == 1062
                : "23505".equals(failure.getSQLState());
    }

    /** 只分类为可重新发起的事务故障，本助手不重试、不恢复或提交事务。 */
    static boolean isTransientTransaction(JdbcDatabaseEngine engine, SQLException failure) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(failure, "failure");
        if (engine == JdbcDatabaseEngine.MYSQL) {
            return failure.getErrorCode() == 1205 || failure.getErrorCode() == 1213;
        }
        String state = failure.getSQLState();
        return state != null && state.startsWith("40");
    }
}
