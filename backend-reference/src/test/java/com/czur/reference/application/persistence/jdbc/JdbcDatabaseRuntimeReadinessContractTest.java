package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖 PostgreSQL 的 JdbcDatabaseRuntime readiness 源码契约门。
 *
 * <p>真实 PostgreSQL catalog 的列类型、约束和 schema 反复启动由显式环境集成测试
 * 验证；这里冻结装配入口必须检查的 V001～V008 登记集合、V008 查询参数、只读
 * catalog 探针和脱敏日志边界，避免以后只更新迁移文件而漏掉运行时门禁。</p>
 */
class JdbcDatabaseRuntimeReadinessContractTest {
    private static final Pattern REQUIRED_MIGRATIONS = Pattern.compile(
            "REQUIRED_MIGRATIONS\\s*=\\s*Set\\.of\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern MIGRATION_QUERY = Pattern.compile(
            "MIGRATION_QUERY\\s*=\\s*\\\"\\\"\\\"(.*?)\\\"\\\"\\\"", Pattern.DOTALL);

    @Test
    void runtimeRequiresV008AndBindsItsMigrationQueryParameter() throws Exception {
        String source = readRuntimeSource();
        String required = match(REQUIRED_MIGRATIONS, source);
        String query = match(MIGRATION_QUERY, source);

        assertEquals(8, occurrences(required, "V00"),
                "runtime must freeze exactly V001 through V008 migrations");
        assertTrue(required.contains("V007__meeting_manifest_revision"));
        assertTrue(required.contains("V008__durable_input_fact"));
        assertEquals(10, occurrences(query, "?"),
                "the two legacy aliases plus V001-V008 require ten query slots");
        assertTrue(source.contains(
                "migrations.setString(10, \"V008__durable_input_fact\")"));
        assertTrue(source.contains("missing.removeAll(present)"));
    }

    @Test
    void readinessUsesOnlyReadOnlyCatalogChecksForV008PhysicalContract() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("hasExactDurableInputFactSchema(connection)"));
        String helper = source.substring(source.indexOf(
                "private static boolean hasExactDurableInputFactSchema"));
        assertTrue(helper.contains("relationOid(connection, \"durable_input_fact\")"));
        assertTrue(helper.contains("isOrdinaryTable(connection, factTable)"));
        assertTrue(helper.contains("FROM pg_attribute"));
        assertTrue(helper.contains("FROM pg_constraint"));
        for (String column : new String[]{
                "meeting_id", "input_binding", "last_accepted_sequence", "audio_duration_ms",
                "source_language", "target_languages", "accepted_at", "content_digest"}) {
            assertTrue(source.contains("new ColumnContract(\"" + column + "\""), column);
        }
        for (String constraint : new String[]{
                "durable_input_fact_pkey", "durable_input_fact_meeting_fk",
                "durable_input_fact_sequence_ck", "durable_input_fact_duration_ck",
                "durable_input_fact_input_binding_ck", "durable_input_fact_source_language_ck",
                "durable_input_fact_target_languages_ck", "durable_input_fact_content_digest_ck"}) {
            assertTrue(helper.contains(constraint), constraint);
        }
        assertFalse(helper.contains("CREATE TABLE"));
        assertFalse(helper.matches("(?is).*\\b(?:INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM)\\b.*"));
    }

    @Test
    void startupDocumentationAndLogsStayOnStableRedactedClassification() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("{@code schema_migrations} 中的 V001～V008"));
        assertFalse(source.contains("{@code schema_migrations} 中的 V001～V007"));
        assertTrue(source.contains("status={0}"));
        assertTrue(source.contains("exception={1}"));
        assertTrue(source.contains("LOGGER.log(Level.INFO, \"数据库运行时启动成功 pool={0}\", config)"));
        assertFalse(source.contains("LOGGER.log(Level.INFO, \"数据库运行时启动成功 url={0}"));
        assertFalse(source.contains("failure.getMessage()"));
    }

    private static String match(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        assertTrue(matcher.find(), "runtime source contract block is missing: " + pattern);
        return matcher.group(1);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String readRuntimeSource() throws IOException {
        Path[] candidates = {
                Path.of("src", "main", "java", "com", "czur", "reference",
                        "application", "persistence", "jdbc", "JdbcDatabaseRuntime.java"),
                Path.of("backend-reference", "src", "main", "java", "com", "czur",
                        "reference", "application", "persistence", "jdbc",
                        "JdbcDatabaseRuntime.java")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("JdbcDatabaseRuntime source file not found");
    }
}
