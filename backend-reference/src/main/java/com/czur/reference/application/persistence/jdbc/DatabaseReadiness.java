package com.czur.reference.application.persistence.jdbc;

import java.util.Set;

/**
 * 数据库 readiness 的脱敏结果。
 *
 * <p>该对象只表达数据库是否可供业务使用，不是 HTTP 响应模型。失败详情使用
 * 稳定的分类文本，禁止携带 JDBC URL、账号、密码、SQL 参数或数据库返回正文。</p>
 */
public final class DatabaseReadiness {
    public enum Status {
        READY,
        DATABASE_UNAVAILABLE,
        MIGRATION_MISSING,
        INVALID_CONFIGURATION,
        CLOSED
    }

    private final Status status;
    private final Set<String> missingMigrations;
    private final String detail;

    private DatabaseReadiness(Status status, Set<String> missingMigrations, String detail) {
        this.status = status;
        this.missingMigrations = Set.copyOf(missingMigrations);
        this.detail = detail;
    }

    /** 创建所有只读探针和必需迁移均通过的结果。 */
    public static DatabaseReadiness success() {
        return new DatabaseReadiness(Status.READY, Set.of(), "数据库连接和必需迁移检查通过");
    }

    /** 创建数据库连接不可用结果；detail 只能用于脱敏分类诊断。 */
    static DatabaseReadiness unavailable(String ignoredDetail) {
        return new DatabaseReadiness(
                Status.DATABASE_UNAVAILABLE, Set.of(), "数据库连接或只读探针不可用");
    }

    /** 创建缺少当前运行时必需迁移登记的结果。 */
    static DatabaseReadiness migrationMissing(Set<String> missing) {
        return new DatabaseReadiness(
                Status.MIGRATION_MISSING,
                missing,
                missing.isEmpty() ? "数据库迁移未登记" : "缺少数据库迁移登记");
    }

    /** 创建配置未通过结果，供装配层统一表达失败分类。 */
    static DatabaseReadiness invalidConfiguration(String ignoredDetail) {
        return new DatabaseReadiness(Status.INVALID_CONFIGURATION, Set.of(), "数据库配置非法");
    }

    /** 创建运行时已关闭结果。 */
    public static DatabaseReadiness closed() {
        return new DatabaseReadiness(Status.CLOSED, Set.of(), "数据库运行时已关闭");
    }

    /** 返回是否满足业务数据库 readiness。 */
    public boolean ready() {
        return status == Status.READY;
    }

    /** 返回稳定的 readiness 分类。 */
    public Status status() {
        return status;
    }

    /** 返回缺少的迁移版本集合；结果不可变。 */
    public Set<String> missingMigrations() {
        return missingMigrations;
    }

    /** 仅返回已脱敏的诊断分类，不保证是面向终端用户的文案。 */
    public String detail() {
        return detail;
    }

    @Override
    public String toString() {
        return "DatabaseReadiness{" +
                "status=" + status +
                ", missingMigrations=" + missingMigrations +
                ", detail='" + detail + "'}";
    }

}
