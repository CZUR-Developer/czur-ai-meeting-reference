package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingCommand;
import com.czur.reference.application.command.MeetingCommandService;
import com.czur.reference.application.command.StopMeetingCommand;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V003 真实 PostgreSQL 旧库升级门禁。
 *
 * <p>测试在随机独立 schema 中先执行原始 V001/V002，再构造历史会议、旧版 JSONB
 * 幂等结果和同会议多条 usage。它不会修改调用方已有 public schema，也不会调用
 * Docker 或迁移容器。只有显式提供 {@code REFERENCE_TEST_DATABASE_URL} 才运行。</p>
 *
 * <p>本类专门验证迁移边界，不替代后续权益 JDBC 契约测试：V003 可以安全重跑、
 * 未知历史状态整事务回滚、历史事实不丢不合并、新 V3 终态约束可执行，以及当前
 * Java 兼容入口在迁移后仍能创建和停止会议。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV003MigrationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource schemaDataSource;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        adminDataSource = dataSource(null);
        schema = "v003_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quotedIdentifier(schema));
        }
        schemaDataSource = dataSource(schema);
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quotedIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void upgradesOldDatabaseWithoutLosingHistoryAndCanRerunBeforeRegistration() throws Exception {
        applyMigration("V001__initial_schema.sql");
        applyMigration("V002__outbox_lease_fencing.sql");

        // 部署方可能重命名过约束；V003 必须按表和列语义识别，不能依赖默认名。
        executeUpdate("""
                ALTER TABLE usage_reservation
                    RENAME CONSTRAINT usage_reservation_meeting_id_fkey
                    TO partner_named_usage_meeting_fk
                """);

        // 先由旧命令链生成真实旧版幂等 JSONB 和 Outbox，再还原 V001/V002 使用的
        // subject_scope='meeting'，避免测试用手写 JSON 替代真实兼容数据。
        MeetingCommandService legacyService = new MeetingCommandService(
                new JdbcMeetingUnitOfWork(schemaDataSource));
        legacyService.create(new CreateMeetingCommand(
                "legacy-meeting", "legacy-deployment", "legacy-session", "profile", 1,
                NOW, "legacy-create"));
        executeUpdate("UPDATE meeting SET subject_scope = 'meeting' WHERE meeting_id = 'legacy-meeting'");
        executeUpdate("""
                INSERT INTO usage_reservation (
                    reservation_id, meeting_id, usage_type, status, quantity, provider_ref,
                    created_at, updated_at)
                VALUES
                    ('legacy-usage-duration', 'legacy-meeting', 'MEETING_DURATION_MS',
                     'RESERVED', 60000, 'provider-a',
                     TIMESTAMPTZ '2026-09-17 06:00:00Z', TIMESTAMPTZ '2026-09-17 06:01:00Z'),
                    ('legacy-usage-tokens', 'legacy-meeting', 'PROVIDER_TOKENS',
                     'COMMITTED', 123.5, 'provider-b',
                     TIMESTAMPTZ '2026-09-17 06:00:00Z', TIMESTAMPTZ '2026-09-17 06:02:00Z')
                """);
        String oldJson = queryText("""
                SELECT response_body::text
                  FROM idempotency_record
                 WHERE subject_scope = 'meeting.create' AND idempotency_key = 'legacy-create'
                """);
        assertNotNull(oldJson);

        applyMigration("V003__identity_entitlement.sql");
        assertFalse(isMigrationRegistered("V003__identity_entitlement"),
                "模拟 schema 已提交但版本登记失败的窗口");
        applyMigration("V003__identity_entitlement.sql");

        assertEquals("LEGACY_UNOWNED", queryText(
                "SELECT subject_scope FROM meeting WHERE meeting_id = 'legacy-meeting'"));
        assertNull(queryNullableText(
                "SELECT grant_id FROM meeting WHERE meeting_id = 'legacy-meeting'"));
        assertNull(queryNullableText(
                "SELECT usage_reservation_id FROM meeting WHERE meeting_id = 'legacy-meeting'"));
        assertEquals(2L, queryLong(
                "SELECT count(*) FROM usage_reservation WHERE meeting_id = 'legacy-meeting'"));
        assertEquals(2L, queryLong("""
                SELECT count(*) FROM usage_reservation
                 WHERE meeting_id = 'legacy-meeting' AND record_format = 'LEGACY_V1'
                """));
        assertEquals(0L, queryLong("""
                SELECT count(*)
                  FROM pg_constraint
                 WHERE conrelid = 'usage_reservation'::regclass
                   AND confrelid = 'meeting'::regclass
                   AND contype = 'f'
                   AND conname = 'partner_named_usage_meeting_fk'
                """));
        assertEquals("123.500000", queryText("""
                SELECT quantity::text FROM usage_reservation
                 WHERE reservation_id = 'legacy-usage-tokens'
                """));
        assertEquals(oldJson, queryText("""
                SELECT response_body::text
                  FROM idempotency_record
                 WHERE subject_scope = 'meeting.create' AND idempotency_key = 'legacy-create'
                """));
        assertTrue(new JdbcIdempotencyStore(schemaDataSource)
                .find("meeting.create", "legacy-create").isPresent());
        assertEquals(MeetingState.CREATED, new JdbcMeetingRepository(schemaDataSource)
                .find("legacy-meeting").orElseThrow().state());
        SQLException damagedLegacyFact = assertThrows(SQLException.class, () -> executeUpdate("""
                UPDATE usage_reservation
                   SET actual_amount = 1
                 WHERE reservation_id = 'legacy-usage-duration'
                """));
        assertEquals("23514", damagedLegacyFact.getSQLState());

        // 当前 Java 尚未接入认证编排，但迁移提交本身必须是独立可部署回滚点。
        // 兼容入口明确写 LEGACY_UNOWNED，且原有创建/停止事务链仍可工作。
        MeetingCommandService compatibleService = new MeetingCommandService(
                new JdbcMeetingUnitOfWork(schemaDataSource));
        var created = compatibleService.create(new CreateMeetingCommand(
                "post-v003-meeting", "post-v003-deployment", "post-v003-session",
                "profile", 1, NOW.plusSeconds(10), "post-v003-create"));
        MeetingAggregate running = MeetingAggregate.fromSnapshot(created.meeting());
        running.apply(MeetingEvent.AUTHORIZE, NOW.plusSeconds(11));
        running.apply(MeetingEvent.START, NOW.plusSeconds(12));
        running.apply(MeetingEvent.START, NOW.plusSeconds(13));
        new JdbcMeetingRepository(schemaDataSource).save(running.snapshot());
        assertEquals(MeetingState.STOPPING, compatibleService.stop(new StopMeetingCommand(
                "post-v003-meeting", NOW.plusSeconds(14), "post-v003-stop")).meeting().state());
        assertEquals("LEGACY_UNOWNED", queryText("""
                SELECT subject_scope FROM meeting WHERE meeting_id = 'post-v003-meeting'
                """));
    }

    @Test
    void unknownHistoricalStateFailsBeforeSchemaOrDataChanges() throws Exception {
        applyMigration("V001__initial_schema.sql");
        applyMigration("V002__outbox_lease_fencing.sql");
        executeUpdate("""
                INSERT INTO meeting (
                    meeting_id, deployment_scope, client_session_id, subject_scope,
                    profile_id, profile_revision, state, aggregate_version, created_at,
                    updated_at, metadata)
                VALUES ('unknown-state-meeting', 'deployment', 'session', 'meeting',
                        'profile', 1, 'CREATED', 0,
                        TIMESTAMPTZ '2026-09-17 06:00:00Z',
                        TIMESTAMPTZ '2026-09-17 06:00:00Z', '{}'::jsonb)
                """);
        executeUpdate("""
                INSERT INTO usage_reservation (
                    reservation_id, meeting_id, usage_type, status, quantity,
                    created_at, updated_at)
                VALUES ('unknown-state-usage', 'unknown-state-meeting', 'MEETING_DURATION_MS',
                        'PROVIDER_PENDING', 1000,
                        TIMESTAMPTZ '2026-09-17 06:00:00Z',
                        TIMESTAMPTZ '2026-09-17 06:00:00Z')
                """);

        SQLException failure = assertThrows(SQLException.class,
                () -> applyMigration("V003__identity_entitlement.sql"));
        assertEquals("P0001", failure.getSQLState());
        assertFalse(columnExists("meeting", "grant_id"));
        assertEquals("meeting", queryText("""
                SELECT subject_scope FROM meeting WHERE meeting_id = 'unknown-state-meeting'
                """));
        assertEquals(1L, queryLong("""
                SELECT count(*) FROM usage_reservation
                 WHERE reservation_id = 'unknown-state-usage'
                   AND status = 'PROVIDER_PENDING'
                """));
        assertFalse(isMigrationRegistered("V003__identity_entitlement"));
    }

    @Test
    void v3SchemaSupportsUnboundReservationAndEnforcesTerminalAndOperationScopes()
            throws Exception {
        applyMigration("V001__initial_schema.sql");
        applyMigration("V002__outbox_lease_fencing.sql");
        applyMigration("V003__identity_entitlement.sql");

        executeUpdate("""
                INSERT INTO usage_reservation (
                    reservation_id, status, created_at, updated_at, record_format,
                    deployment_scope, subject_scope, grant_id, expected_meeting_id,
                    usage_unit, reserved_amount, expires_at, version, lease_version)
                VALUES ('v3-unbound', 'RESERVED',
                        TIMESTAMPTZ '2026-09-17 06:00:00Z',
                        TIMESTAMPTZ '2026-09-17 06:00:00Z', 'V3',
                        'deployment-a', 'subject-a', 'grant-a', 'future-meeting',
                        'MEETING_DURATION_MS', 60000,
                        TIMESTAMPTZ '2026-09-17 06:10:00Z', 0, 0)
                """);
        assertEquals(1L, queryLong("""
                SELECT count(*) FROM usage_reservation
                 WHERE reservation_id = 'v3-unbound' AND meeting_id IS NULL
                   AND bound_meeting_id IS NULL
                """));

        executeUpdate("""
                INSERT INTO usage_reservation (
                    reservation_id, status, created_at, updated_at, record_format,
                    deployment_scope, subject_scope, grant_id, expected_meeting_id,
                    usage_unit, reserved_amount, expires_at, version, lease_version)
                VALUES ('v3-unbound-b', 'RESERVED',
                        TIMESTAMPTZ '2026-09-17 06:00:00Z',
                        TIMESTAMPTZ '2026-09-17 06:00:00Z', 'V3',
                        'deployment-b', 'subject-a', 'grant-b', 'future-meeting-b',
                        'MEETING_DURATION_MS', 60000,
                        TIMESTAMPTZ '2026-09-17 06:10:00Z', 0, 0)
                """);

        SQLException invalidTerminal = assertThrows(SQLException.class, () -> executeUpdate("""
                INSERT INTO usage_reservation (
                    reservation_id, status, created_at, updated_at, record_format,
                    deployment_scope, subject_scope, grant_id, expected_meeting_id,
                    bound_meeting_id, bound_at, usage_unit, reserved_amount, expires_at,
                    version, lease_version, committed_at)
                VALUES ('v3-invalid-committed', 'COMMITTED',
                        TIMESTAMPTZ '2026-09-17 06:00:00Z',
                        TIMESTAMPTZ '2026-09-17 06:01:00Z', 'V3',
                        'deployment-a', 'subject-a', 'grant-a', 'future-meeting',
                        NULL, NULL, 'MEETING_DURATION_MS', 60000,
                        TIMESTAMPTZ '2026-09-17 06:10:00Z', 2, 0,
                        TIMESTAMPTZ '2026-09-17 06:01:00Z')
                """));
        assertEquals("23514", invalidTerminal.getSQLState());

        executeUpdate(operationInsert("deployment-a", "v3-unbound"));
        executeUpdate(operationInsert("deployment-b", "v3-unbound-b"));
        SQLException duplicate = assertThrows(SQLException.class,
                () -> executeUpdate(operationInsert("deployment-a", "v3-unbound")));
        assertEquals("23505", duplicate.getSQLState());
        assertEquals(2L, queryLong("SELECT count(*) FROM usage_operation"));

        SQLException crossScopeReservation = assertThrows(SQLException.class,
                () -> executeUpdate(operationInsert("deployment-c", "v3-unbound")));
        assertEquals("23503", crossScopeReservation.getSQLState());
    }

    private String operationInsert(String deploymentScope, String reservationId) {
        return """
                INSERT INTO usage_operation (
                    deployment_scope, subject_scope, operation_scope, idempotency_key,
                    request_digest, reservation_id, result_body, created_at)
                VALUES ('%s', 'subject-a', 'usage.reserve.v1', 'same-key',
                        'digest', '%s', '{"ok":true}'::jsonb,
                        TIMESTAMPTZ '2026-09-17 06:00:00Z')
                """.formatted(deploymentScope, reservationId);
    }

    private void applyMigration(String filename) throws Exception {
        String sql = Files.readString(findMigration(filename), StandardCharsets.UTF_8);
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeUpdate(String sql) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String queryText(String sql) throws SQLException {
        String value = queryNullableText(sql);
        assertNotNull(value);
        return value;
    }

    private String queryNullableText(String sql) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1
                       FROM information_schema.columns
                      WHERE table_schema = ? AND table_name = ? AND column_name = ?
                     """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean isMigrationRegistered(String version) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Path findMigration(String filename) {
        Path[] candidates = {
                Path.of("..", "deploy", "migrations", filename),
                Path.of("deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到迁移文件：" + filename);
    }

    private static PGSimpleDataSource dataSource(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) {
            postgres.setUser(user);
        }
        if (password != null) {
            postgres.setPassword(password);
        }
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String quotedIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
