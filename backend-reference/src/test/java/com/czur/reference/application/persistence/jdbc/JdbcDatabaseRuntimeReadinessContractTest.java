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
 * 验证；这里冻结装配入口必须检查的 V001～V011 登记集合、V011 查询参数、只读
 * catalog 探针和脱敏日志边界，避免以后只更新迁移文件而漏掉运行时门禁。</p>
 */
class JdbcDatabaseRuntimeReadinessContractTest {
    private static final Pattern REQUIRED_MIGRATIONS = Pattern.compile(
            "REQUIRED_MIGRATIONS\\s*=\\s*Set\\.of\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern MIGRATION_QUERY = Pattern.compile(
            "MIGRATION_QUERY\\s*=\\s*\\\"\\\"\\\"(.*?)\\\"\\\"\\\"", Pattern.DOTALL);

    @Test
    void runtimeRequiresV011AndBindsItsMigrationQueryParameter() throws Exception {
        String source = readRuntimeSource();
        String required = match(REQUIRED_MIGRATIONS, source);
        String query = match(MIGRATION_QUERY, source);

        assertEquals(11, occurrences(required, "V0"),
                "runtime must freeze exactly V001 through V011 migrations");
        assertTrue(required.contains("V007__meeting_manifest_revision"));
        assertTrue(required.contains("V008__durable_input_fact"));
        assertTrue(required.contains("V009__final_usage_fact"));
        assertTrue(required.contains("V010__workflow_step_fencing"));
        assertTrue(required.contains("V011__finalization_execution_artifact_ready"));
        assertEquals(13, occurrences(query, "?"),
                "the two legacy aliases plus V001-V011 require thirteen query slots");
        assertTrue(source.contains(
                "migrations.setString(10, \"V008__durable_input_fact\")"));
        assertTrue(source.contains(
                "migrations.setString(11, \"V009__final_usage_fact\")"));
        assertTrue(source.contains(
                "migrations.setString(12, \"V010__workflow_step_fencing\")"));
        assertTrue(source.contains(
                "migrations.setString(13, \"V011__finalization_execution_artifact_ready\")"));
        assertTrue(source.contains("missing.removeAll(present)"));
    }

    @Test
    void readinessUsesReadOnlyCatalogChecksForV010WorkflowStepContract() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("hasExactWorkflowStepSchema(connection)"));
        String helper = source.substring(source.indexOf("hasExactWorkflowStepSchema"));
        for (String column : new String[]{"checkpoint_type", "canonical_payload",
                "content_digest", "initial_available_at", "next_attempt_at",
                "lease_token", "lease_version"}) {
            assertTrue(source.contains("\"" + column + "\""), column);
        }
        for (String constraint : new String[]{"workflow_step_status_ck",
                "workflow_step_definition_ck", "workflow_step_lease_token_ck",
                "workflow_step_attempt_count_check", "workflow_step_facts_ck",
                "workflow_step_key_uq", "workflow_step_meeting_id_fkey"}) {
            assertTrue(helper.contains(constraint), constraint);
        }
        assertTrue(helper.contains("WORKFLOW_STEP_STATUS_CHECK"));
        assertTrue(helper.contains("WORKFLOW_STEP_DEFINITION_CHECK"));
        assertTrue(helper.contains("WORKFLOW_STEP_LEASE_TOKEN_CHECK"));
        assertTrue(helper.contains("WORKFLOW_STEP_ATTEMPT_CHECK"));
        assertTrue(helper.contains("WORKFLOW_STEP_FACTS_CHECK"));
        assertTrue(helper.contains("stableKeyUnique"));
        assertTrue(helper.contains("meetingForeignKey"));
        assertTrue(helper.contains("hasExactWorkflowStepIdentity(connection, table)"));
        assertTrue(helper.contains("a.attname = 'step_id'"));
        assertTrue(helper.contains("\"bigint\".equals(rows.getString(\"data_type\"))"));
        assertTrue(helper.contains("\"d\".equals(rows.getString(\"attidentity\"))"));
        assertTrue(helper.contains("workflow_step_claim_v010_idx"));
        assertTrue(helper.contains("只是性能索引"));
        assertFalse(helper.contains("definition.contains("));
        assertFalse(helper.contains("CREATE TABLE"));
        assertFalse(helper.matches("(?is).*\\b(?:INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM)\\b.*"));
    }

    @Test
    void readinessUsesOnlyReadOnlyCatalogChecksForV009PhysicalContract() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("hasExactFinalUsageFactSchema(connection)"));
        String helper = source.substring(source.indexOf(
                "private static boolean hasExactFinalUsageFactSchema"));
        assertTrue(helper.contains("relationOid(connection, \"final_usage_fact\")"));
        assertTrue(helper.contains("isStandalonePermanentTable(connection, factTable)"));
        assertTrue(helper.contains("FROM pg_attribute"));
        assertTrue(helper.contains("FROM pg_constraint"));
        for (String column : new String[]{
                "meeting_id", "actual_unit", "actual_amount", "source_input_binding",
                "source_input_digest", "source_accepted_at"}) {
            assertTrue(source.contains("\"" + column + "\""), column);
        }
        for (String constraint : new String[]{
                "final_usage_fact_pkey", "final_usage_fact_meeting_fk",
                "final_usage_fact_actual_unit_ck", "final_usage_fact_actual_amount_ck",
                "final_usage_fact_source_input_binding_ck",
                "final_usage_fact_source_input_digest_ck"}) {
            assertTrue(helper.contains(constraint), constraint);
        }
        assertFalse(helper.contains("CREATE TABLE"));
        assertFalse(helper.matches("(?is).*\\b(?:INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM)\\b.*"));
    }

    @Test
    void readinessUsesOnlyReadOnlyCatalogChecksForV008PhysicalContract() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("hasExactDurableInputFactSchema(connection)"));
        String helper = source.substring(source.indexOf(
                "private static boolean hasExactDurableInputFactSchema"));
        assertTrue(helper.contains("relationOid(connection, \"durable_input_fact\")"));
        assertTrue(helper.contains("isStandalonePermanentTable(connection, factTable)"));
        assertTrue(helper.contains("FROM pg_attribute"));
        assertTrue(helper.contains("FROM pg_constraint"));
        for (String column : new String[]{
                "meeting_id", "input_binding", "last_accepted_sequence", "audio_duration_ms",
                "source_language", "target_languages", "accepted_at", "content_digest"}) {
            assertTrue(source.contains("\"" + column + "\""), column);
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
    void readinessUsesExactReadOnlyCatalogChecksForBothV011Tables() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("hasExactFinalizationExecutionFactSchema(connection)"));
        assertTrue(source.contains("hasExactReadyArtifactSchema(connection)"));
        String helper = source.substring(source.indexOf(
                "private static boolean hasExactFinalizationExecutionFactSchema"));

        for (String column : new String[]{
                "execution_fact_json", "content_sha256", "artifact_id", "media_type",
                "language", "intent_id", "storage_binding", "content_size",
                "content_checksum", "recorded_at"}) {
            assertTrue(source.contains("\"" + column + "\""), column);
        }
        for (String constraint : new String[]{
                "finalization_execution_fact_pkey",
                "finalization_execution_fact_meeting_fk",
                "finalization_execution_fact_document_ck",
                "finalization_execution_fact_hash_ck",
                "artifact_pkey", "artifact_meeting_fk", "artifact_revision_uq",
                "artifact_intent_id_uq", "artifact_storage_binding_uq",
                "artifact_text_ck", "artifact_language_ck", "artifact_revision_ck",
                "artifact_content_size_ck", "artifact_content_checksum_ck"}) {
            assertTrue(helper.contains(constraint), constraint);
        }
        assertTrue(helper.contains("constraints.size() == 4"));
        assertTrue(helper.contains("constraints.size() == 10"));
        assertTrue(helper.contains("isStandalonePermanentTable(connection, factTable)"));
        assertTrue(helper.contains("isStandalonePermanentTable(connection, artifactTable)"));
        assertTrue(helper.contains("containsExactCheck("));
        assertTrue(helper.contains("convalidated"));
        assertTrue(helper.contains("confdeltype"));
        assertFalse(helper.contains("definition.contains("));
        assertFalse(helper.contains("CREATE TABLE"));
        assertFalse(helper.matches("(?is).*\\b(?:INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM)\\b.*"));
    }

    @Test
    void readinessFreezesMeetingParentAndStandaloneDurableRelations() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("hasExactMeetingIdentitySchema(connection)"));
        assertTrue(source.contains("a.attnum = 1"));
        assertTrue(source.contains("a.attname = 'meeting_id'"));
        assertTrue(source.contains("\"text\".equals(rows.getString(\"data_type\"))"));
        assertTrue(source.contains("rows.getBoolean(\"attnotnull\")"));
        assertTrue(source.contains("conname = 'meeting_pkey'"));
        assertTrue(source.contains("!rows.getBoolean(\"condeferrable\")"));
        assertTrue(source.contains("PRIMARY KEY (meeting_id)"));

        assertTrue(source.contains("c.relpersistence = 'p'"));
        assertTrue(source.contains("NOT c.relispartition"));
        assertTrue(source.contains("FROM pg_inherits i"));
        assertTrue(source.contains("i.inhrelid = c.oid OR i.inhparent = c.oid"));
        assertTrue(source.contains("JAVA_21_BLANK_SQL"));
        for (String codePoint : new String[]{
                "chr(9)", "chr(13)", "chr(5760)", "chr(8192)",
                "chr(8202)", "chr(8232)", "chr(8287)", "chr(12288)"}) {
            assertTrue(source.contains(codePoint), codePoint);
        }
        assertFalse(source.contains("CHECK (length(btrim(artifact_id))"));
    }

    @Test
    void startupDocumentationAndLogsStayOnStableRedactedClassification() throws Exception {
        String source = readRuntimeSource();
        assertTrue(source.contains("{@code schema_migrations} 中的 V001～V011"));
        assertFalse(source.contains("{@code schema_migrations} 中的 V001～V010"));
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
