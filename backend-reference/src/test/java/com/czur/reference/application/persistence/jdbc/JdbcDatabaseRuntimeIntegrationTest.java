package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingCommand;
import com.czur.reference.application.command.MeetingCommandService;
import com.czur.reference.application.command.StopMeetingCommand;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式 PostgreSQL 运行时门禁。
 *
 * <p>默认跳过，只有调用方显式提供测试数据库 URL、账号和密码时才启动真实
 * HikariCP。目标数据库必须预先执行并登记 V001～V012；常规业务用例不自动迁移、不清理
 * 共享数据库，也不在运行失败时静默切换到内存实现。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcDatabaseRuntimeIntegrationTest {
    @Test
    void refusesSchemaThatHasNotRegisteredV007ThroughV012() throws Exception {
        String url = requiredUrl();
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "显式 PostgreSQL 集成测试必须同时设置 REFERENCE_TEST_DATABASE_USER/PASSWORD");
        }
        String schema = "runtime_missing_v006_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(url, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
            statement.execute("CREATE TABLE " + quoteIdentifier(schema)
                    + ".schema_migrations (version TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO " + quoteIdentifier(schema)
                    + ".schema_migrations(version) VALUES "
                    + "('V001__initial_schema'), ('V002__outbox_lease_fencing'), "
                    + "('V003__identity_entitlement'), ('V004__meeting_event_envelope'), "
                    + "('V005__meeting_finalization_fact'), "
                    + "('V006__meeting_read_model')");
        }
        try {
            String scopedUrl = url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=" + schema;
            DatabaseStartupException failure = assertThrows(
                    DatabaseStartupException.class,
                    () -> JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                            scopedUrl, user, password, Duration.ofSeconds(5),
                            Duration.ofSeconds(3), 2, 1)));
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    failure.readiness().status());
            assertEquals(java.util.Set.of(
                            "V007__meeting_manifest_revision",
                            "V008__durable_input_fact",
                            "V009__final_usage_fact",
                            "V010__workflow_step_fencing",
                            "V011__finalization_execution_artifact_ready",
                            "V012__artifact_deletion_plan"),
                    failure.readiness().missingMigrations());
        } finally {
            try (Connection admin = DriverManager.getConnection(url, user, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS "
                        + quoteIdentifier(schema) + " CASCADE");
            }
        }
    }

    @Test
    void registeredV007WithoutPhysicalTableIsSchemaInvalid() throws Exception {
        String schema = createMigrationRegistryOnlySchema("runtime_v007_no_table_");
        try {
            registerMigrations(schema, 12);
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV007WithResidualHalfTableIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v007_half_", 6);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE meeting_manifest_revision (meeting_id TEXT)");
            }
            registerMigrations(schema, 12);
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV007WithSameNameWeakCheckIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v007_weak_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        ALTER TABLE meeting_manifest_revision
                            DROP CONSTRAINT meeting_manifest_revision_hash_ck,
                            ADD CONSTRAINT meeting_manifest_revision_hash_ck CHECK (true)
                        """);
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV009WithoutPhysicalTableIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v009_no_table_", 8);
        try {
            registerMigrations(schema, 12);
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV009WithSameNameWeakCheckIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v009_weak_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        ALTER TABLE final_usage_fact
                            DROP CONSTRAINT final_usage_fact_actual_amount_ck,
                            ADD CONSTRAINT final_usage_fact_actual_amount_ck CHECK (true)
                        """);
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void exactV011PhysicalTablesWithoutRegistrationStayMigrationMissing() throws Exception {
        String schema = createMigratedSchema("runtime_v011_unregistered_", 12);
        try (Connection connection = scopedConnection(schema);
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM schema_migrations WHERE version = ?")) {
            statement.setString(1, "V011__finalization_execution_artifact_ready");
            assertEquals(1, statement.executeUpdate());
        }
        try {
            DatabaseStartupException failure = assertThrows(
                    DatabaseStartupException.class,
                    () -> JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                            scopedUrl(schema), requiredUser(), requiredPassword(),
                            Duration.ofSeconds(5), Duration.ofSeconds(3), 2, 1)));
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    failure.readiness().status());
            assertEquals(java.util.Set.of(
                            "V011__finalization_execution_artifact_ready"),
                    failure.readiness().missingMigrations());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011WithoutPhysicalTablesIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_no_tables_", 10);
        try {
            registerMigrations(schema, 12);
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011MissingArtifactIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_no_artifact_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE artifact");
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011WithWrongRecordedAtDefaultIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_default_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE artifact ALTER COLUMN recorded_at DROP DEFAULT");
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011WithNotValidCheckIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_not_valid_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        ALTER TABLE artifact
                            DROP CONSTRAINT artifact_language_ck,
                            ADD CONSTRAINT artifact_language_ck CHECK (
                                language IS NULL OR (
                                    length(btrim(language)) > 0
                                    AND octet_length(convert_to(language, 'UTF8')) <= 4096))
                                NOT VALID
                        """);
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011WithNullableUniqueMeetingIdentityIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_parent_identity_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        ALTER TABLE meeting DROP CONSTRAINT meeting_pkey CASCADE;
                        ALTER TABLE meeting ALTER COLUMN meeting_id DROP NOT NULL;
                        ALTER TABLE meeting ADD CONSTRAINT meeting_id_uq UNIQUE (meeting_id);
                        ALTER TABLE meeting_manifest_revision
                            ADD CONSTRAINT meeting_manifest_revision_meeting_fk
                            FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                            ON DELETE RESTRICT;
                        ALTER TABLE durable_input_fact
                            ADD CONSTRAINT durable_input_fact_meeting_fk
                            FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                            ON DELETE RESTRICT;
                        ALTER TABLE final_usage_fact
                            ADD CONSTRAINT final_usage_fact_meeting_fk
                            FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                            ON DELETE RESTRICT;
                        ALTER TABLE workflow_step
                            ADD CONSTRAINT workflow_step_meeting_id_fkey
                            FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id);
                        ALTER TABLE finalization_execution_fact
                            ADD CONSTRAINT finalization_execution_fact_meeting_fk
                            FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                            ON DELETE RESTRICT;
                        ALTER TABLE artifact
                            ADD CONSTRAINT artifact_meeting_fk
                            FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                            ON DELETE RESTRICT
                        """);
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011WithUnloggedArtifactIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_unlogged_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE artifact SET UNLOGGED");
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011WithArtifactPartitionLeafIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_partition_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE artifact_partition_parent
                            (LIKE artifact INCLUDING DEFAULTS)
                            PARTITION BY HASH (artifact_id);
                        ALTER TABLE artifact_partition_parent
                            ATTACH PARTITION artifact
                            FOR VALUES WITH (MODULUS 1, REMAINDER 0)
                        """);
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registeredV011WithLegacyArtifactChildIsSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v011_inheritance_", 12);
        try {
            try (Connection connection = scopedConnection(schema);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE artifact_shadow () INHERITS (artifact)");
            }
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void startsWithReadinessAndExposesSharedJdbcComponents() {
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "显式 PostgreSQL 集成测试必须同时设置 REFERENCE_TEST_DATABASE_USER/PASSWORD");
        }

        DatabaseRuntimeConfig config = DatabaseRuntimeConfig.of(
                requiredUrl(),
                user,
                password,
                Duration.ofSeconds(5),
                Duration.ofSeconds(3),
                4,
                1);
        try (JdbcDatabaseRuntime runtime = JdbcDatabaseRuntime.start(config)) {
            assertTrue(runtime.isLive());
            assertTrue(runtime.checkReadiness().ready());
            assertNotNull(runtime.dataSource());
            assertSame(runtime.meetingUnitOfWork(), runtime.meetingUnitOfWork());
            assertSame(runtime.outboxStore(), runtime.outboxStore());
            assertSame(runtime.dataSource(), runtime.dataSource());

            String meetingId = "jdbc-runtime-test-" + UUID.randomUUID();
            try {
                MeetingCommandService service = new MeetingCommandService(runtime.meetingUnitOfWork());
                Instant now = Instant.parse("2026-09-17T03:00:00Z");
                var created = service.create(new CreateMeetingCommand(
                        meetingId, "jdbc-runtime-test", "session-" + meetingId,
                        "profile", 1, now, "create-" + meetingId));
                var running = MeetingAggregate.fromSnapshot(created.meeting());
                running.apply(MeetingEvent.AUTHORIZE, now);
                running.apply(MeetingEvent.START, now);
                running.apply(MeetingEvent.START, now);
                new JdbcMeetingRepository(runtime.dataSource()).save(running.snapshot());
                var stopped = service.stop(new StopMeetingCommand(
                        meetingId, now.plusSeconds(1), "stop-" + meetingId));
                assertEquals("STOPPING", stopped.meeting().state().name());
            } finally {
                deleteFixture(runtime.dataSource(), meetingId);
            }
        }
    }

    @Test
    void closeIsIdempotentAndDisablesComponentAccess() {
        // 该生命周期断言需要真实数据库，避免构造绕过启动 readiness 的伪运行时。
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "显式 PostgreSQL 集成测试必须同时设置 REFERENCE_TEST_DATABASE_USER/PASSWORD");
        }
        JdbcDatabaseRuntime runtime = JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                requiredUrl(), user, password,
                Duration.ofSeconds(5), Duration.ofSeconds(3), 4, 1));
        runtime.close();
        runtime.close();
        assertFalse(runtime.isLive());
        assertFalse(runtime.checkReadiness().ready());
        assertThrows(IllegalStateException.class, runtime::dataSource);
        assertThrows(IllegalStateException.class, runtime::meetingUnitOfWork);
        assertThrows(IllegalStateException.class, runtime::outboxStore);
    }

    private static void deleteFixture(javax.sql.DataSource dataSource, String meetingId)
            throws RuntimeException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM outbox_event WHERE aggregate_id = ?")) {
                statement.setString(1, meetingId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM idempotency_record WHERE idempotency_key LIKE ?")) {
                statement.setString(1, "%" + meetingId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM meeting WHERE meeting_id = ?")) {
                statement.setString(1, meetingId);
                statement.executeUpdate();
            }
        } catch (java.sql.SQLException failure) {
            throw new RuntimeException("显式集成测试夹具清理失败", failure);
        }
    }

    private static void assertSchemaInvalid(String schema) {
        DatabaseStartupException failure = assertThrows(
                DatabaseStartupException.class,
                () -> JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                        scopedUrl(schema), requiredUser(), requiredPassword(),
                        Duration.ofSeconds(5), Duration.ofSeconds(3), 2, 1)));
        assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID, failure.readiness().status());
        assertTrue(failure.readiness().missingMigrations().isEmpty());
        assertFalse(failure.readiness().detail().contains(schema));
    }

    private static String createMigrationRegistryOnlySchema(String prefix) throws Exception {
        String schema = prefix + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                requiredUrl(), requiredUser(), requiredPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
            statement.execute("CREATE TABLE " + quoteIdentifier(schema)
                    + ".schema_migrations (version TEXT PRIMARY KEY)");
        }
        return schema;
    }

    /**
     * 创建只供 readiness 破坏性用例使用的随机 schema，并顺序执行指定数量的迁移。
     * 每个版本只在 SQL 成功后登记，模拟 deploy/migrate.sh 的提交边界；不会触碰
     * 调用方传入数据库的 public schema 或任何既有业务行。
     */
    private static String createMigratedSchema(String prefix, int count) throws Exception {
        String schema = prefix + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                requiredUrl(), requiredUser(), requiredPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        List<String> migrations = List.of(
                "V001__initial_schema.sql",
                "V002__outbox_lease_fencing.sql",
                "V003__identity_entitlement.sql",
                "V004__meeting_event_envelope.sql",
                "V005__meeting_finalization_fact.sql",
                "V006__meeting_read_model.sql",
                "V007__meeting_manifest_revision.sql",
                "V008__durable_input_fact.sql",
                "V009__final_usage_fact.sql",
                "V010__workflow_step_fencing.sql",
                "V011__finalization_execution_artifact_ready.sql",
                "V012__artifact_deletion_plan.sql");
        try {
            for (String migration : migrations.subList(0, count)) {
                try (Connection connection = scopedConnection(schema);
                     Statement statement = connection.createStatement()) {
                    statement.execute(readMigration(migration));
                }
                registerMigrations(schema, migrations.indexOf(migration) + 1);
            }
            return schema;
        } catch (Exception failure) {
            dropSchema(schema);
            throw failure;
        }
    }

    private static void registerMigrations(String schema, int count) throws Exception {
        List<String> versions = List.of(
                "V001__initial_schema",
                "V002__outbox_lease_fencing",
                "V003__identity_entitlement",
                "V004__meeting_event_envelope",
                "V005__meeting_finalization_fact",
                "V006__meeting_read_model",
                "V007__meeting_manifest_revision",
                "V008__durable_input_fact",
                "V009__final_usage_fact",
                "V010__workflow_step_fencing",
                "V011__finalization_execution_artifact_ready",
                "V012__artifact_deletion_plan");
        try (Connection connection = scopedConnection(schema);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO schema_migrations(version)
                     VALUES (?) ON CONFLICT (version) DO NOTHING
                     """)) {
            for (String version : versions.subList(0, count)) {
                statement.setString(1, version);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Connection scopedConnection(String schema) throws Exception {
        return DriverManager.getConnection(
                scopedUrl(schema), requiredUser(), requiredPassword());
    }

    private static String scopedUrl(String schema) {
        String url = requiredUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String readMigration(String filename) throws Exception {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found: " + filename);
    }

    private static void dropSchema(String schema) throws Exception {
        try (Connection admin = DriverManager.getConnection(
                requiredUrl(), requiredUser(), requiredPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quoteIdentifier(schema) + " CASCADE");
        }
    }

    private static String requiredUser() {
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("REFERENCE_TEST_DATABASE_USER is required");
        }
        return user;
    }

    private static String requiredUrl() {
        String url = System.getenv("REFERENCE_TEST_DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("REFERENCE_TEST_DATABASE_URL is required");
        }
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException(
                    "runtime tests refuse the shared 127.0.0.1:55432 PostgreSQL resource");
        }
        return url;
    }

    private static String requiredPassword() {
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("REFERENCE_TEST_DATABASE_PASSWORD is required");
        }
        return password;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
