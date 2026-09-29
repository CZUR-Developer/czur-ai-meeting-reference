package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T14-11 first gate: a fresh deploy migration chain survives one real PostgreSQL
 * stop/start in two independent Maven/JVM phases.  Business workers are deliberately
 * outside this slice; later slices consume the same lifecycle harness.
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T14_TOTAL_STATE_FILE", matches = ".+")
class T14TotalGateIntegrationTest {
    private static final String STATE_ENV = "T14_TOTAL_STATE_FILE";
    private static final Set<String> MIGRATIONS = Set.of(
            "V001__initial_schema", "V002__outbox_lease_fencing",
            "V003__identity_entitlement", "V004__meeting_event_envelope",
            "V005__meeting_finalization_fact", "V006__meeting_read_model",
            "V007__meeting_manifest_revision", "V008__durable_input_fact",
            "V009__final_usage_fact", "V010__workflow_step_fencing",
            "V011__finalization_execution_artifact_ready",
            "V012__artifact_deletion_plan");
    private static final Set<String> STATE_KEYS = Set.of(
            "phase", "postmasterStart", "migrationCount");
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<String> EMPTY_FACT_TABLES = Set.of(
            "meeting", "outbox_event", "usage_reservation", "workflow_step",
            "meeting_manifest_revision", "finalization_execution_fact", "artifact",
            "meeting_deletion_plan", "artifact_deletion_item");

    @Test
    void prepareBeforeRestart() throws Exception {
        rejectReservedEndpoint();
        Path state = stateFile();
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("state file already exists");
        }
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "fresh V001-V012 database must pass readiness");
            assertEquals(MIGRATIONS, migrationVersions(runtime.dataSource()));
            assertAllFactTablesExist(runtime.dataSource());
            assertEmptyBusinessFacts(runtime.dataSource());
            writeState(state, Map.of(
                    "phase", "PREPARED",
                    "postmasterStart", postmasterStart(runtime.dataSource()).toString(),
                    "migrationCount", Integer.toString(MIGRATIONS.size())));
        }
    }

    @Test
    void verifyAfterRestart() throws Exception {
        rejectReservedEndpoint();
        Map<String, String> expected = readState(stateFile());
        assertEquals("PREPARED", expected.get("phase"));
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "V001-V012 readiness must survive PostgreSQL restart");
            DataSource dataSource = runtime.dataSource();
            Instant before = Instant.parse(expected.get("postmasterStart"));
            Instant after = postmasterStart(dataSource);
            assertTrue(after.isAfter(before), "postmaster start time must advance after restart");
            assertEquals(MIGRATIONS, migrationVersions(dataSource));
            assertEquals(Integer.toString(MIGRATIONS.size()), expected.get("migrationCount"));
            assertAllFactTablesExist(dataSource);
            assertEmptyBusinessFacts(dataSource);
            writeState(stateFile(), Map.of(
                    "phase", "VERIFIED",
                    "postmasterStart", after.toString(),
                    "migrationCount", Integer.toString(MIGRATIONS.size())));
        }
    }

    private static JdbcDatabaseRuntime startRuntime() {
        return JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                required("REFERENCE_TEST_DATABASE_URL"),
                required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), 4, 1));
    }

    private static Set<String> migrationVersions(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT version FROM schema_migrations ORDER BY version");
             ResultSet rows = statement.executeQuery()) {
            Set<String> actual = new HashSet<>();
            while (rows.next()) {
                if (!actual.add(rows.getString(1))) {
                    throw new IllegalStateException("duplicate migration registration");
                }
            }
            return Set.copyOf(actual);
        }
    }

    private static void assertAllFactTablesExist(DataSource dataSource) throws Exception {
        String placeholders = String.join(",", EMPTY_FACT_TABLES.stream().map(value -> "?").toList());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema=current_schema() AND table_name IN ("
                             + placeholders + ")")) {
            int index = 1;
            for (String table : EMPTY_FACT_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> actual = new HashSet<>();
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
                assertEquals(EMPTY_FACT_TABLES, actual);
            }
        }
    }

    private static void assertEmptyBusinessFacts(DataSource dataSource) throws Exception {
        for (String table : EMPTY_FACT_TABLES) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT count(*) FROM " + quote(table));
                 ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0L, rows.getLong(1), "fresh table must be empty: " + table);
            }
        }
    }

    private static Instant postmasterStart(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pg_postmaster_start_time()");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getTimestamp(1).toInstant();
        }
    }

    private static Path stateFile() {
        Path path = Path.of(required(STATE_ENV));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("state file must be absolute");
        }
        return path.normalize();
    }

    private static void writeState(Path path, Map<String, String> values) throws Exception {
        if (!values.keySet().equals(STATE_KEYS)) {
            throw new IllegalArgumentException("state keys are not exact");
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IllegalStateException("state parent is unsafe");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw new IllegalStateException("state file is unsafe");
            }
        } else {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
        }
        String content = "phase=" + fixedValue(values.get("phase")) + '\n'
                + "postmasterStart=" + fixedValue(values.get("postmasterStart")) + '\n'
                + "migrationCount=" + fixedValue(values.get("migrationCount")) + '\n';
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Linux acceptance scripts perform the exact mode check.
        }
    }

    private static Map<String, String> readState(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path) || Files.size(path) > 4096) {
            throw new IllegalStateException("state file is unsafe");
        }
        try {
            assertEquals(PRIVATE_PERMISSIONS, Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX development filesystems still receive structural checks.
        }
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw new IllegalStateException("state line is malformed");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!STATE_KEYS.contains(key) || value.isBlank() || values.put(key, value) != null) {
                throw new IllegalStateException("state keys are not exact");
            }
        }
        if (!values.keySet().equals(STATE_KEYS)) {
            throw new IllegalStateException("state keys are incomplete");
        }
        return Map.copyOf(values);
    }

    private static String fixedValue(String value) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("state value is invalid");
        }
        return value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void rejectReservedEndpoint() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("reserved database endpoint is forbidden");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
