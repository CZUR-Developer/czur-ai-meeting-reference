package com.czur.reference.application.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V012 Meeting/Artifact 删除物理契约的隔离 PostgreSQL 16 readiness 门禁。
 *
 * <p>每个反例创建自己的随机 schema，顺序执行并登记 V001～V012，
 * 并额外执行/登记当前运行时必需的 V013；
 * 然后只篡改一类 catalog 事实。证明条件是运行时明确返回
 * {@link DatabaseReadiness.Status#SCHEMA_INVALID}，而不是把篡改 SQL 失败或任意
 * {@code SQLException} 当作通过。测试不触碰 public schema，并显式拒绝共享
 * {@code 127.0.0.1:55432} 资源。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcDatabaseRuntimeV012ReadinessIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
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

    @Test
    void currentBaselinePassesAndMissingRegistrationRemainsMigrationMissing()
            throws Exception {
        String readySchema = createMigratedSchema("runtime_v012_ready_", 12);
        try (JdbcDatabaseRuntime runtime = startRuntime(readySchema)) {
            assertTrue(runtime.checkReadiness().ready());
        } finally {
            dropSchema(readySchema);
        }

        String unregisteredSchema = createMigratedSchema("runtime_v012_unregistered_", 12);
        try {
            try (Connection connection = scopedConnection(unregisteredSchema);
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM schema_migrations WHERE version = ?")) {
                statement.setString(1, "V001__initial_schema");
                assertEquals(1, statement.executeUpdate());
            }
            DatabaseStartupException failure = assertThrows(
                    DatabaseStartupException.class,
                    () -> startRuntime(unregisteredSchema));
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    failure.readiness().status());
            assertEquals(Set.of("V001__initial_schema"),
                    failure.readiness().missingMigrations());
        } finally {
            dropSchema(unregisteredSchema);
        }
    }


    @TestFactory
    Stream<DynamicTest> everyV012CatalogDriftFailsClosedAsSchemaInvalid() {
        return Stream.of(
                mutation("meeting_deleted_at_nullable", """
                        ALTER TABLE meeting
                            DROP CONSTRAINT meeting_deleted_at_equivalence_ck;
                        ALTER TABLE meeting ALTER COLUMN deleted_at SET NOT NULL;
                        ALTER TABLE meeting
                            ADD CONSTRAINT meeting_deleted_at_equivalence_ck CHECK (
                                (state = 'DELETED' AND manifest_state = 'DELETED'
                                    AND deleted_at IS NOT NULL AND deleted_at = updated_at)
                                OR (state <> 'DELETED' AND manifest_state <> 'DELETED'
                                    AND deleted_at IS NULL))
                        """),
                mutation("meeting_deleted_at_default", """
                        ALTER TABLE meeting
                            ALTER COLUMN deleted_at SET DEFAULT CURRENT_TIMESTAMP
                        """),
                mutation("meeting_weak_check", """
                        ALTER TABLE meeting
                            DROP CONSTRAINT meeting_deleted_at_equivalence_ck,
                            ADD CONSTRAINT meeting_deleted_at_equivalence_ck CHECK (true)
                        """),
                mutation("plan_wrong_type", """
                        ALTER TABLE meeting_deletion_plan
                            ALTER COLUMN target_count TYPE BIGINT
                        """),
                mutation("plan_nullable", """
                        ALTER TABLE meeting_deletion_plan
                            ALTER COLUMN target_count DROP NOT NULL
                        """),
                mutation("plan_default", """
                        ALTER TABLE meeting_deletion_plan
                            ALTER COLUMN completed_at SET DEFAULT CURRENT_TIMESTAMP
                        """),
                mutation("plan_identity", """
                        ALTER TABLE meeting_deletion_plan
                            ALTER COLUMN target_count ADD GENERATED BY DEFAULT AS IDENTITY
                        """),
                mutation("plan_generated", """
                        ALTER TABLE meeting_deletion_plan
                            DROP CONSTRAINT meeting_deletion_plan_status_ck;
                        ALTER TABLE meeting_deletion_plan DROP COLUMN completed_at;
                        ALTER TABLE meeting_deletion_plan ADD COLUMN completed_at TIMESTAMPTZ
                            GENERATED ALWAYS AS (accepted_at) STORED;
                        ALTER TABLE meeting_deletion_plan
                            ADD CONSTRAINT meeting_deletion_plan_status_ck CHECK (
                                (status = 'PENDING' AND completed_at IS NULL)
                                OR (status = 'COMPLETED' AND completed_at IS NOT NULL
                                    AND completed_at >= accepted_at))
                        """),
                mutation("plan_extra_column", """
                        ALTER TABLE meeting_deletion_plan ADD COLUMN unexpected TEXT
                        """),
                mutation("plan_wrong_column_order", """
                        ALTER TABLE meeting_deletion_plan
                            DROP CONSTRAINT meeting_deletion_plan_status_ck;
                        ALTER TABLE meeting_deletion_plan DROP COLUMN status;
                        ALTER TABLE meeting_deletion_plan DROP COLUMN completed_at;
                        ALTER TABLE meeting_deletion_plan
                            ADD COLUMN completed_at TIMESTAMPTZ;
                        ALTER TABLE meeting_deletion_plan
                            ADD COLUMN status TEXT NOT NULL;
                        ALTER TABLE meeting_deletion_plan
                            ADD CONSTRAINT meeting_deletion_plan_status_ck CHECK (
                                (status = 'PENDING' AND completed_at IS NULL)
                                OR (status = 'COMPLETED' AND completed_at IS NOT NULL
                                    AND completed_at >= accepted_at))
                        """),
                mutation("plan_weak_check", """
                        ALTER TABLE meeting_deletion_plan
                            DROP CONSTRAINT meeting_deletion_plan_target_count_ck,
                            ADD CONSTRAINT meeting_deletion_plan_target_count_ck CHECK (true)
                        """),
                mutation("item_not_valid_check", """
                        ALTER TABLE artifact_deletion_item
                            DROP CONSTRAINT artifact_deletion_item_revision_ck,
                            ADD CONSTRAINT artifact_deletion_item_revision_ck
                                CHECK (revision > 0) NOT VALID
                        """),
                mutation("plan_fk_no_action", """
                        ALTER TABLE meeting_deletion_plan
                            DROP CONSTRAINT meeting_deletion_plan_meeting_fk,
                            ADD CONSTRAINT meeting_deletion_plan_meeting_fk
                                FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)
                        """),
                mutation("item_fk_cascade", """
                        ALTER TABLE artifact_deletion_item
                            DROP CONSTRAINT artifact_deletion_item_plan_fk,
                            ADD CONSTRAINT artifact_deletion_item_plan_fk
                                FOREIGN KEY (meeting_id)
                                REFERENCES meeting_deletion_plan(meeting_id)
                                ON DELETE CASCADE
                        """),
                mutation("item_fk_deferrable", """
                        ALTER TABLE artifact_deletion_item
                            DROP CONSTRAINT artifact_deletion_item_plan_fk,
                            ADD CONSTRAINT artifact_deletion_item_plan_fk
                                FOREIGN KEY (meeting_id)
                                REFERENCES meeting_deletion_plan(meeting_id)
                                ON DELETE RESTRICT DEFERRABLE
                        """),
                mutation("item_fk_not_valid", """
                        ALTER TABLE artifact_deletion_item
                            DROP CONSTRAINT artifact_deletion_item_plan_fk,
                            ADD CONSTRAINT artifact_deletion_item_plan_fk
                                FOREIGN KEY (meeting_id)
                                REFERENCES meeting_deletion_plan(meeting_id)
                                ON DELETE RESTRICT NOT VALID
                        """),
                mutation("item_wrong_unique_key", """
                        ALTER TABLE artifact_deletion_item
                            DROP CONSTRAINT artifact_deletion_item_artifact_id_uq,
                            ADD CONSTRAINT artifact_deletion_item_artifact_id_uq
                                UNIQUE (artifact_type)
                        """),
                mutation("plan_extra_constraint", """
                        ALTER TABLE meeting_deletion_plan
                            ADD CONSTRAINT meeting_deletion_plan_extra_ck CHECK (true)
                        """),
                mutation("plan_extra_index", """
                        CREATE INDEX meeting_deletion_plan_status_idx
                            ON meeting_deletion_plan(status)
                        """),
                mutation("item_expression_index", """
                        CREATE INDEX artifact_deletion_item_expression_idx
                            ON artifact_deletion_item(lower(artifact_type))
                        """),
                mutation("item_partial_index", """
                        CREATE INDEX artifact_deletion_item_partial_idx
                            ON artifact_deletion_item(status) WHERE status = 'PENDING'
                        """),
                mutation("item_unlogged", """
                        ALTER TABLE artifact_deletion_item SET UNLOGGED
                        """),
                mutation("item_partition_leaf", """
                        CREATE TABLE artifact_deletion_item_parent
                            (LIKE artifact_deletion_item INCLUDING DEFAULTS)
                            PARTITION BY HASH (meeting_id);
                        ALTER TABLE artifact_deletion_item_parent
                            ATTACH PARTITION artifact_deletion_item
                            FOR VALUES WITH (MODULUS 1, REMAINDER 0)
                        """),
                mutation("plan_inheritance_child", """
                        CREATE TABLE meeting_deletion_plan_shadow ()
                            INHERITS (meeting_deletion_plan)
                        """))
                .map(mutation -> DynamicTest.dynamicTest(
                        mutation.name(), () -> assertMutationInvalid(mutation)));
    }

    @Test
    void temporaryShadowMakesRuntimeReadinessSchemaInvalid() throws Exception {
        String schema = createMigratedSchema("runtime_v012_temp_", 12);
        DatabaseRuntimeConfig config = runtimeConfig(schema);
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.url());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setConnectionTimeout(config.connectionTimeout().toMillis());
        hikari.setValidationTimeout(config.validationTimeout().toMillis());
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setPoolName("czur-v012-temp-shadow-test");
        HikariDataSource pool = new HikariDataSource(hikari);
        JdbcDatabaseRuntime runtime = null;
        try {
            // 先在唯一物理会话上建立 TEMP shadow，归还连接后 readiness
            // 必须复用该会话并将其分类为 schema 错误，而非连接异常。
            try (Connection connection = pool.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE meeting_deletion_plan (marker INTEGER)");
            }
            Constructor<JdbcDatabaseRuntime> constructor =
                    JdbcDatabaseRuntime.class.getDeclaredConstructor(
                            DatabaseRuntimeConfig.class, HikariDataSource.class);
            constructor.setAccessible(true);
            runtime = constructor.newInstance(config, pool);
            DatabaseReadiness readiness = runtime.checkReadiness();
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID, readiness.status());
            assertTrue(readiness.missingMigrations().isEmpty());
            assertEquals(13, columnCount(schema, "meeting_deletion_plan"),
                    "the permanent table must remain untouched behind the TEMP shadow");

            try (Connection connection = pool.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE pg_temp.meeting_deletion_plan");
                statement.execute("CREATE TEMP TABLE artifact_deletion_item (marker INTEGER)");
            }
            DatabaseReadiness itemShadow = runtime.checkReadiness();
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID, itemShadow.status());
            assertTrue(itemShadow.missingMigrations().isEmpty());
            assertEquals(8, columnCount(schema, "artifact_deletion_item"),
                    "the permanent item table must remain untouched behind the TEMP shadow");
        } finally {
            if (runtime != null) {
                runtime.close();
            } else {
                pool.close();
            }
            dropSchema(schema);
        }
    }

    private static Mutation mutation(String name, String sql) {
        return new Mutation(name, sql);
    }

    private static void assertMutationInvalid(Mutation mutation) throws Exception {
        String schema = createMigratedSchema("runtime_v012_" + mutation.name() + "_", 12);
        try {
            execute(schema, mutation.sql());
            assertSchemaInvalid(schema);
        } finally {
            dropSchema(schema);
        }
    }

    private static void assertSchemaInvalid(String schema) {
        DatabaseStartupException failure = assertThrows(
                DatabaseStartupException.class,
                () -> startRuntime(schema));
        assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID, failure.readiness().status());
        assertTrue(failure.readiness().missingMigrations().isEmpty());
        assertFalse(failure.readiness().detail().contains(schema));
    }

    private static JdbcDatabaseRuntime startRuntime(String schema) {
        return JdbcDatabaseRuntime.start(runtimeConfig(schema));
    }

    private static DatabaseRuntimeConfig runtimeConfig(String schema) {
        return DatabaseRuntimeConfig.of(
                scopedUrl(schema), requiredUser(), requiredPassword(),
                Duration.ofSeconds(5), Duration.ofSeconds(3), 2, 1);
    }

    private static String createMigratedSchema(String prefix, int count) throws Exception {
        String schema = prefix + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                requiredUrl(), requiredUser(), requiredPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        try {
            for (String migration : MIGRATIONS.subList(0, count)) {
                execute(schema, readMigration(migration));
                registerMigration(schema, migration);
            }
            // V012 负例仍要满足最新运行时的 V013 门禁，才能观察 V012
            // 的真实物理漂移，而不是被 MIGRATION_MISSING 提前截断。
            execute(schema, readMigration("V013__meeting_create_fact.sql"));
            registerMigration(schema, "V013__meeting_create_fact.sql");
            return schema;
        } catch (Exception failure) {
            dropSchema(schema);
            throw failure;
        }
    }

    private static void registerMigration(String schema, String filename) throws Exception {
        String version = filename.substring(0, filename.length() - ".sql".length());
        try (Connection connection = scopedConnection(schema);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO schema_migrations(version)
                     VALUES (?) ON CONFLICT (version) DO NOTHING
                     """)) {
            statement.setString(1, version);
            statement.executeUpdate();
        }
    }

    private static void execute(String schema, String sql) throws Exception {
        try (Connection connection = scopedConnection(schema);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int relationCount(String schema, String relation) throws Exception {
        try (Connection connection = scopedConnection(schema);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM pg_class c
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = ? AND c.relname = ?
                     """)) {
            statement.setString(1, schema);
            statement.setString(2, relation);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int columnCount(String schema, String relation) throws Exception {
        try (Connection connection = scopedConnection(schema);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM pg_attribute a
                       JOIN pg_class c ON c.oid = a.attrelid
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = ? AND c.relname = ?
                        AND a.attnum > 0 AND NOT a.attisdropped
                     """)) {
            statement.setString(1, schema);
            statement.setString(2, relation);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static Connection scopedConnection(String schema) throws Exception {
        return DriverManager.getConnection(scopedUrl(schema), requiredUser(), requiredPassword());
    }

    private static String scopedUrl(String schema) {
        String url = requiredUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String readMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return "SELECT 1";
        }
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

    private static String requiredUrl() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException(
                    "V012 readiness tests refuse the shared 127.0.0.1:55432 resource");
        }
        return url;
    }

    private static String requiredUser() {
        return required("REFERENCE_TEST_DATABASE_USER");
    }

    private static String requiredPassword() {
        return required("REFERENCE_TEST_DATABASE_PASSWORD");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private record Mutation(String name, String sql) {
    }
}
