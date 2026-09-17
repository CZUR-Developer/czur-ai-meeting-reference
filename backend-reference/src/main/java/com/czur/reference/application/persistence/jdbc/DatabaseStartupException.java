package com.czur.reference.application.persistence.jdbc;

/**
 * 数据库运行时无法安全启动时抛出的失败关闭异常。
 *
 * <p>异常消息只引用 readiness 分类，不包含底层 JDBC 异常消息；为避免驱动异常的
 * cause 链携带 URL 或连接参数，本异常不保留底层 cause。</p>
 */
public final class DatabaseStartupException extends IllegalStateException {
    private final DatabaseReadiness readiness;

    public DatabaseStartupException(DatabaseReadiness readiness) {
        // 不保留底层 SQLException 作为 cause：驱动异常可能把完整 JDBC URL 或
        // 连接参数带入 getMessage()，而启动异常会被上层诊断工具直接打印。
        super("数据库运行时启动失败：" + readiness.status() + "，" + readiness.detail());
        this.readiness = readiness;
    }

    public DatabaseReadiness readiness() {
        return readiness;
    }
}
