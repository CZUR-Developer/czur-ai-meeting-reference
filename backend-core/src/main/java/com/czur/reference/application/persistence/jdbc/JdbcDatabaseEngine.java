package com.czur.reference.application.persistence.jdbc;

/**
 * 公共持久层支持的数据库引擎。
 *
 * <p>参考交付只支持 MySQL 5.7 兼容基线；引擎由已经校验的 JDBC URL 决定，
 * 不按主机名、部署方或产品 Profile 切换。</p>
 */
public enum JdbcDatabaseEngine {
    MYSQL("jdbc:mysql://", "com.mysql.cj.jdbc.Driver");

    private final String urlPrefix;
    private final String driverClassName;

    JdbcDatabaseEngine(String urlPrefix, String driverClassName) {
        this.urlPrefix = urlPrefix;
        this.driverClassName = driverClassName;
    }

    String urlPrefix() {
        return urlPrefix;
    }

    String driverClassName() {
        return driverClassName;
    }

    static JdbcDatabaseEngine fromUrl(String url) {
        for (JdbcDatabaseEngine engine : values()) {
            if (url.startsWith(engine.urlPrefix)) {
                return engine;
            }
        }
        throw new IllegalArgumentException(
                "数据库配置 DATABASE_URL 非法：必须使用 jdbc:mysql:// 地址");
    }
}
