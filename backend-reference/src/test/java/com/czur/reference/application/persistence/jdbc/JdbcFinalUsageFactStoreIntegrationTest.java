package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalUsageFactStore;
import com.czur.reference.application.port.FinalizationInputFact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V009 最终用量表及连接级 JDBC 原语的真实 PostgreSQL 门禁。
 *
 * <p>默认跳过；显式配置测试数据库后，每个用例只在随机 schema 中执行 V001 和
 * V009，不连接或清理既有业务 schema。测试事务均由用例拥有，Store 不负责
 * commit、rollback 或 close。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcFinalUsageFactStoreIntegrationTest {
    private static final String MEETING_ID = "final-usage-integration";
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-22T03:04:05.123456Z");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "final_usage_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigration("V001__initial_schema.sql");
        applyMigration("V008__durable_input_fact.sql");
        applyMigration("V009__final_usage_fact.sql");
        insertMeeting();
        persistDurableSource();
    }

    @AfterEach
    void dropFreshSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void appliedCommitAndFindRoundTripAllFieldsAtMicrosecondPrecision() throws Exception {
        FinalUsageFact fact = fact(4_200, "binding-a", "digest-a", ACCEPTED_AT);

        try (Connection connection = transactionalConnection()) {
            FinalUsageFactStore.PersistResult result =
                    JdbcFinalUsageFactStore.persist(connection, fact);
            assertEquals(FinalUsageFactStore.PersistStatus.APPLIED, result.status());
            assertEquals(fact, result.fact());
            connection.commit();
        }

        try (Connection connection = transactionalConnection()) {
            assertEquals(Optional.of(fact),
                    JdbcFinalUsageFactStore.find(connection, MEETING_ID));
            connection.rollback();
        }
    }

    @Test
    void equalRetryReplaysAndDifferentCandidateConflictsWithoutOverwrite() throws Exception {
        FinalUsageFact winner = fact(4_200, "binding-a", "digest-a", ACCEPTED_AT);
        persistAndCommit(winner);

        try (Connection connection = transactionalConnection()) {
            assertEquals(FinalUsageFactStore.PersistStatus.REPLAYED,
                    JdbcFinalUsageFactStore.persist(connection, winner).status());
            FinalUsageFactStore.PersistResult conflict = JdbcFinalUsageFactStore.persist(
                    connection,
                    fact(8_002, "binding-conflict", "digest-conflict", ACCEPTED_AT));
            assertEquals(FinalUsageFactStore.PersistStatus.CONFLICT, conflict.status());
            assertEquals(winner, conflict.fact());
            connection.commit();
        }

        try (Connection connection = transactionalConnection()) {
            assertEquals(Optional.of(winner),
                    JdbcFinalUsageFactStore.find(connection, MEETING_ID));
            connection.rollback();
        }
    }

    @Test
    void callerRollbackRemovesAppliedCandidate() throws Exception {
        try (Connection connection = transactionalConnection()) {
            assertEquals(FinalUsageFactStore.PersistStatus.APPLIED,
                    JdbcFinalUsageFactStore.persist(
                            connection,
                            fact(4_200, "binding-a", "digest-a", ACCEPTED_AT))
                            .status());
            connection.rollback();
        }

        try (Connection connection = transactionalConnection()) {
            assertTrue(JdbcFinalUsageFactStore.find(connection, MEETING_ID).isEmpty());
            connection.rollback();
        }
    }

    @Test
    void rejectsAutoCommitAndSubMicrosecondSourceBeforeWriting() throws Exception {
        try (Connection autoCommit = dataSource.getConnection()) {
            assertTrue(autoCommit.getAutoCommit());
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcFinalUsageFactStore.find(autoCommit, MEETING_ID));
        }

        Instant nanosecondTime = Instant.parse("2026-09-22T03:04:05.123456789Z");
        try (Connection connection = transactionalConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> JdbcFinalUsageFactStore.persist(
                            connection,
                            fact(4_200, "binding-a", "digest-a", nanosecondTime)));
            assertEquals(
                    "sourceAcceptedAt must use PostgreSQL microsecond precision",
                    failure.getMessage());
            assertTrue(JdbcFinalUsageFactStore.find(connection, MEETING_ID).isEmpty());
            connection.rollback();
        }
    }

    @Test
    void migrationCanRerunExactlyAndRestrictsMeetingDelete() throws Exception {
        applyMigration("V009__final_usage_fact.sql");
        persistAndCommit(fact(4_200, "binding-a", "digest-a", ACCEPTED_AT));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM meeting WHERE meeting_id = ?")) {
            statement.setString(1, MEETING_ID);
            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }
        assertFalse(tableIsEmpty());
    }

    @Test
    void v009DriftFromDurableSourceFailsClosedInsteadOfFabricatingInputFields()
            throws Exception {
        persistAndCommit(fact(4_200, "binding-a", "digest-a", ACCEPTED_AT));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE final_usage_fact SET actual_amount = ? WHERE meeting_id = ?")) {
            statement.setLong(1, 4_201);
            statement.setString(2, MEETING_ID);
            assertEquals(1, statement.executeUpdate());
        }

        try (Connection connection = transactionalConnection()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> JdbcFinalUsageFactStore.find(connection, MEETING_ID));
            assertEquals("final usage fact is inconsistent", failure.getMessage());
            assertFalse(failure.getMessage().contains(MEETING_ID));
            connection.rollback();
        }
    }

    @Test
    void missingDurableInputSourceFailsClosedWithoutCreatingFinalFact() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM durable_input_fact WHERE meeting_id = ?")) {
            statement.setString(1, MEETING_ID);
            assertEquals(1, statement.executeUpdate());
        }

        try (Connection connection = transactionalConnection()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> JdbcFinalUsageFactStore.persist(
                            connection,
                            fact(4_200, "binding-a", "digest-a", ACCEPTED_AT)));
            assertEquals("final usage fact is inconsistent", failure.getMessage());
            assertTrue(JdbcFinalUsageFactStore.find(connection, MEETING_ID).isEmpty());
            connection.rollback();
        }
    }

    @Test
    void migrationRejectsSameNameWeakCheckInsteadOfAcceptingConstraintFragment()
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE final_usage_fact
                        DROP CONSTRAINT final_usage_fact_actual_amount_ck,
                        ADD CONSTRAINT final_usage_fact_actual_amount_ck
                            CHECK (actual_amount >= 0 OR true)
                    """);
        }

        assertThrows(java.sql.SQLException.class,
                () -> applyMigration("V009__final_usage_fact.sql"));
    }

    private void persistAndCommit(FinalUsageFact fact) throws Exception {
        try (Connection connection = transactionalConnection()) {
            JdbcFinalUsageFactStore.persist(connection, fact);
            connection.commit();
        }
    }

    private boolean tableIsEmpty() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM final_usage_fact")) {
            rows.next();
            return rows.getLong(1) == 0;
        }
    }

    private Connection transactionalConnection() throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void insertMeeting() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         profile_id, profile_revision, state, aggregate_version,
                         created_at, updated_at)
                     VALUES (?, 'deployment-test', 'session-test', 'LEGACY_UNOWNED',
                             'profile-test', 1, 'CREATED', 0, ?, ?)
                     """)) {
            statement.setString(1, MEETING_ID);
            statement.setTimestamp(2, java.sql.Timestamp.from(ACCEPTED_AT.minusSeconds(1)));
            statement.setTimestamp(3, java.sql.Timestamp.from(ACCEPTED_AT.minusSeconds(1)));
            statement.executeUpdate();
        }
    }

    private void persistDurableSource() throws Exception {
        FinalizationInputFact source = new FinalizationInputFact(
                MEETING_ID, "binding-a", 7, 4_200, "zh-CN", List.of("en-US"),
                ACCEPTED_AT, "digest-a");
        try (Connection connection = transactionalConnection()) {
            JdbcDurableInputFactStore.persist(connection, source);
            connection.commit();
        }
    }

    private void applyMigration(String filename) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
        }
    }

    private static FinalUsageFact fact(
            long amount,
            String binding,
            String digest,
            Instant acceptedAt) {
        return FinalUsageFact.fromInput(new FinalizationInputFact(
                MEETING_ID, binding, 7, amount, "zh-CN", List.of("en-US"),
                acceptedAt, digest));
    }

    private static PGSimpleDataSource postgres(String schema) {
        String user = requiredEnvironment("REFERENCE_TEST_DATABASE_USER");
        String password = requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD");
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(requiredEnvironment("REFERENCE_TEST_DATABASE_URL"));
        source.setUser(user);
        source.setPassword(password);
        if (schema != null) {
            source.setCurrentSchema(schema);
        }
        return source;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String readMigration(String filename) throws Exception {
        for (Path candidate : List.of(
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename))) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found: " + filename);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
