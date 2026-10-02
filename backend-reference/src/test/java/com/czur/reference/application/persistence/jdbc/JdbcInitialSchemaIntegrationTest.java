package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首次交付的单份数据库基线门禁。
 *
 * <p>仅在显式提供测试 PostgreSQL 时运行。每个用例只在随机 schema 中建表，
 * 不读取或修改调用方的 public schema；结束时仅删除本用例创建的 schema。
 * 这里验证的是空库直接初始化及当前运行时，不再模拟未发布的中间数据库版本。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcInitialSchemaIntegrationTest {
    private String schema;

    @BeforeEach
    void createSchema() throws Exception {
        schema = "initial_schema_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (schema == null) {
            return;
        }
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    @Test
    void onlyOneBaselineSqlCreatesTheCurrentRuntimeSchema() throws Exception {
        Path directory = migrationDirectory();
        try (var files = Files.list(directory)) {
            assertEquals(List.of("V001__initial_schema.sql"), files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.getFileName().toString()).sorted().toList());
        }
        applyBaseline();

        try (Connection connection = scopedConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM schema_migrations")) {
            assertTrue(rows.next());
            assertEquals("V001__initial_schema", rows.getString(1));
            assertFalse(rows.next());
        }

        try (JdbcDatabaseRuntime runtime = JdbcDatabaseRuntime.start(config())) {
            assertTrue(runtime.checkReadiness().ready());
            assertTrue(runtime.checkRealtimeCredentialReadiness().ready());
            assertTrue(runtime.checkDurableAudioReadiness().ready());
            runtime.close();
            assertFalse(runtime.isLive());
        }
    }

    @Test
    void missingBaselineAndFalseRegistrationAreRejected() throws Exception {
        DatabaseStartupException missing = assertThrows(DatabaseStartupException.class,
                () -> JdbcDatabaseRuntime.start(config()));
        assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                missing.readiness().status());
        assertEquals(Set.of("V001__initial_schema"),
                missing.readiness().missingMigrations());

        try (Connection connection = scopedConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations (version TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES ('V001__initial_schema')");
        }
        DatabaseStartupException falseRegistration = assertThrows(
                DatabaseStartupException.class,
                () -> JdbcDatabaseRuntime.start(config()));
        assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                falseRegistration.readiness().status());
    }

    private void applyBaseline() throws Exception {
        String sql = Files.readString(migrationDirectory().resolve("V001__initial_schema.sql"),
                StandardCharsets.UTF_8);
        try (Connection connection = scopedConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            statement.execute("INSERT INTO schema_migrations(version) VALUES ('V001__initial_schema')");
        }
    }

    private DatabaseRuntimeConfig config() {
        return DatabaseRuntimeConfig.of(scopedUrl(), required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"), Duration.ofSeconds(5),
                Duration.ofSeconds(3), 2, 1);
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(required("REFERENCE_TEST_DATABASE_URL"),
                required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"));
    }

    private Connection scopedConnection() throws Exception {
        return DriverManager.getConnection(scopedUrl(),
                required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"));
    }

    private String scopedUrl() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static Path migrationDirectory() {
        for (Path candidate : List.of(Path.of("deploy", "migrations"),
                Path.of("..", "deploy", "migrations"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到参考数据库迁移目录");
    }
}
