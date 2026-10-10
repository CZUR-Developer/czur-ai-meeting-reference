package com.czur.reference.application.persistence.jdbc;

/**
 * 公共持久层明确支持的数据库引擎。
 *
 * <p>引擎仅由已经校验的 JDBC URL 决定，不按主机名、部署方或产品 Profile
 * 切换。驱动和固定表映射属于连接装配边界；本枚举不表示会议 Store 已适配。</p>
 */
public enum JdbcDatabaseEngine {
    POSTGRESQL("jdbc:postgresql://", "org.postgresql.Driver"),
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
                "数据库配置 DATABASE_URL 非法：必须使用 jdbc:postgresql:// 或 jdbc:mysql:// 地址");
    }
}
