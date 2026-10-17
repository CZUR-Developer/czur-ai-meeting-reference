package com.czur.reference.application.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDatabaseContextTest {
    @Test
    void mysqlDriverUsesMillisecondsUtf8mb4AndUtcSession() {
        HikariConfig pool = JdbcDatabaseContext.poolConfiguration(config("jdbc:mysql://localhost/meeting"));

        assertEquals("com.mysql.cj.jdbc.Driver", pool.getDriverClassName());
        assertEquals("3000", pool.getDataSourceProperties().getProperty("socketTimeout"));
        assertEquals("UTF-8", pool.getDataSourceProperties().getProperty("characterEncoding"));
        assertEquals("utf8mb4_bin", pool.getDataSourceProperties().getProperty("connectionCollation"));
        assertEquals("UTC", pool.getDataSourceProperties().getProperty("connectionTimeZone"));
        assertEquals("true", pool.getDataSourceProperties().getProperty("forceConnectionTimeZoneToSession"));
        assertEquals("true", pool.getDataSourceProperties().getProperty("preserveInstants"));
        assertFalse(pool.isReadOnly(), "业务连接不由连接池永久设为只读，基线探针单独只读");
    }

    @Test
    void postgresKeepsOriginalSocketSecondsAndUrlParameters() {
        String url = "jdbc:postgresql://localhost/meeting?currentSchema=isolated&ApplicationName=reference";
        HikariConfig pool = JdbcDatabaseContext.poolConfiguration(config(url));

        assertEquals(url, pool.getJdbcUrl());
        assertEquals("3", pool.getDataSourceProperties().getProperty("socketTimeout"));
        assertEquals(1, pool.getDataSourceProperties().size());
        assertNull(pool.getConnectionInitSql());
    }

    @Test
    void mapsOnlyCurrentBaselineTablesToFixedMysqlPrefix() {
        Set<String> postgres = Arrays.stream(JdbcDatabaseTable.values())
                .filter(table -> table.requiredForBaseline(JdbcDatabaseEngine.POSTGRESQL))
                .map(table -> table.physicalName(JdbcDatabaseEngine.POSTGRESQL)).collect(Collectors.toSet());
        Set<String> mysql = Arrays.stream(JdbcDatabaseTable.values())
                .filter(table -> table.requiredForBaseline(JdbcDatabaseEngine.MYSQL))
                .map(table -> table.physicalName(JdbcDatabaseEngine.MYSQL)).collect(Collectors.toSet());

        assertEquals(25, postgres.size());
        assertEquals(26, mysql.size());
        assertFalse(JdbcDatabaseTable.IDEMPOTENCY_LOCK.requiredFor(JdbcDatabaseEngine.POSTGRESQL));
        assertEquals("ai_trans_idempotency_lock", JdbcDatabaseTable.IDEMPOTENCY_LOCK.physicalName(JdbcDatabaseEngine.MYSQL));
        assertTrue(mysql.stream().allMatch(name -> name.startsWith("ai_trans_")));
        assertEquals("schema_migrations", JdbcDatabaseTable.SCHEMA_MIGRATIONS.physicalName(JdbcDatabaseEngine.POSTGRESQL));
        assertEquals("ai_trans_schema_migrations", JdbcDatabaseTable.SCHEMA_MIGRATIONS.physicalName(JdbcDatabaseEngine.MYSQL));
        assertEquals("ai_trans_meeting_client_event", JdbcDatabaseTable.MEETING_CLIENT_EVENT.physicalName(JdbcDatabaseEngine.MYSQL));
        assertEquals("ai_trans_speaker_identity_person",
                JdbcDatabaseTable.SPEAKER_IDENTITY_PERSON.physicalName(JdbcDatabaseEngine.MYSQL));
        assertFalse(JdbcDatabaseTable.SPEAKER_IDENTITY_PERSON.requiredForBaseline(JdbcDatabaseEngine.MYSQL));
        assertFalse(JdbcDatabaseTable.SPEAKER_IDENTITY_PERSON.requiredForBaseline(JdbcDatabaseEngine.POSTGRESQL));
    }

    private static DatabaseRuntimeConfig config(String url) {
        return DatabaseRuntimeConfig.of(url, "test-user", "test-value",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(3), 2, 0);
    }
}
