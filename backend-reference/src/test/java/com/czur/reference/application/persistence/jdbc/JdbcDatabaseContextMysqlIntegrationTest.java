package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 本机 MySQL 当前 V001 的真实只读连接验收。
 *
 * <p>使用独立 {@code REFERENCE_TEST_MYSQL_DATABASE_URL/USER/PASSWORD} 配置，
 * 只接受 loopback 的 {@code ai_trans_m1_*} 隔离库。基线由初始化入口提前执行；
 * 本测试不建库、不建表、不写会议数据，也不把连接通过代签公共会议 Store。</p>
 */
class JdbcDatabaseContextMysqlIntegrationTest {
    @Test
    void readsCurrentBaselineThroughOnePoolAndClosesIdempotently() throws Exception {
        JdbcDatabaseContext context = JdbcDatabaseContext.start(config());
        try {
            assertEquals(JdbcDatabaseEngine.MYSQL, context.engine());
            assertSame(context.dataSource(), context.dataSource());
            assertEquals("ai_trans_meeting", context.table(JdbcDatabaseTable.MEETING));
            assertTrue(context.checkBaselineReadiness().ready());
            try (Connection connection = context.dataSource().getConnection()) {
                assertFalse(connection.isReadOnly(), "只读基线探针归还连接后不污染业务连接状态");
            }
        } finally {
            context.close();
            context.close();
        }
        assertFalse(context.isLive());
        assertEquals(DatabaseReadiness.Status.CLOSED, context.checkBaselineReadiness().status());
        assertThrows(IllegalStateException.class, context::dataSource);
    }

    @Test
    void mysql57ConnectionUsesUtf8mb4UtcAndKeepsCanonicalText() throws Exception {
        try (JdbcDatabaseContext context = JdbcDatabaseContext.start(config());
             Connection connection = context.dataSource().getConnection()) {
            connection.setReadOnly(true);
            try (Statement query = connection.createStatement();
                 ResultSet result = query.executeQuery("SELECT VERSION(), @@session.time_zone, "
                         + "@@session.character_set_connection, @@session.collation_connection")) {
                assertTrue(result.next());
                assertTrue(result.getString(1).startsWith("5.7."), "实际 server 必须为 MySQL 5.7");
                assertEquals("+00:00", result.getString(2));
                assertEquals("utf8mb4", result.getString(3));
                assertEquals("utf8mb4_bin", result.getString(4));
            }
            String canonical = "{\"title\": \"中文😀\", \"values\": [1, 2]}\n";
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT ?, CAST('2026-10-10 01:02:03.123456' AS DATETIME(6))")) {
                query.setString(1, canonical);
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(canonical, result.getString(1));
                    Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                    assertEquals(Instant.parse("2026-10-10T01:02:03.123456Z"),
                            result.getTimestamp(2, utc).toInstant());
                }
            }
        }
    }

    private static DatabaseRuntimeConfig config() {
        String url = System.getenv("REFERENCE_TEST_MYSQL_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "本机 MySQL 隔离配置未提供，真实连接待验");
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\])"
                        + "(?::[0-9]+)?/ai_trans_m1_[a-zA-Z0-9_]+(?:\\?.*)?"),
                "只允许本机 ai_trans_m1_* 隔离库，不接受业务库地址");
        return DatabaseRuntimeConfig.of(url,
                System.getenv("REFERENCE_TEST_MYSQL_DATABASE_USER"),
                System.getenv("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"),
                Duration.ofSeconds(3), Duration.ofSeconds(2), 2, 0);
    }
}
