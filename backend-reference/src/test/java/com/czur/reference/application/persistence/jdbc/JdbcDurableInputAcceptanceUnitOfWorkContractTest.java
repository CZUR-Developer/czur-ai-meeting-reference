package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖数据库的 JDBC 协同边界静态契约门。
 *
 * <p>真实锁、数组编码、约束、回滚和跨进程提交门禁必须在显式提供的数据库环境
 * 通过集成测试验证；本测试只防止适配器退回到双连接或泄漏底层事务资源。</p>
 */
class JdbcDurableInputAcceptanceUnitOfWorkContractTest {
    @Test
    void acceptanceSourceFreezesSingleConnectionOrderingAndUnknownCommitBoundary()
            throws Exception {
        String source = readSource("JdbcDurableInputAcceptanceUnitOfWork.java");
        assertTrue(source.contains("JdbcMeetingFinalizationStore.preflightAcceptInput"));
        assertTrue(source.contains("JdbcDurableInputFactStore.persist"));
        assertTrue(source.contains("JdbcMeetingFinalizationStore.acceptInput"));
        assertTrue(source.contains("connection.commit()"));
        assertTrue(source.contains("outcome=UNKNOWN"));
        assertTrue(source.contains("connection.abort(Runnable::run)"));
        assertTrue(source.indexOf("preflightAcceptInput")
                < source.indexOf("JdbcDurableInputFactStore.persist"));
        String transaction = source.substring(source.indexOf("private Result acceptInTransaction"));
        int persistIndex = transaction.indexOf("DurableInputFactStore.PersistResult persisted");
        int finalizationIndex = transaction.indexOf(
                "JdbcMeetingFinalizationStore.acceptInput", persistIndex);
        assertTrue(persistIndex >= 0 && finalizationIndex > persistIndex);
        assertTrue(source.indexOf("Result result = acceptInTransaction")
                < source.indexOf("connection.commit()"));
        assertFalse(source.contains("new JdbcMeetingFinalizationStore"));
    }

    @Test
    void factStoreIsConnectionScopedAndDoesNotManageTransactionLifecycle() throws Exception {
        String source = readSource("JdbcDurableInputFactStore.java");
        assertTrue(source.contains("static Optional<FinalizationInputFact> find"));
        assertTrue(source.contains("static DurableInputFactStore.PersistResult persist"));
        assertTrue(source.contains("createArrayOf(") && source.contains("\"text\""));
        assertFalse(source.contains("javax.sql.DataSource"));
        assertFalse(source.contains("connection.commit()"));
        assertFalse(source.contains("connection.rollback()"));
        assertFalse(source.contains("connection.close()"));
    }

    private static String readSource(String name) throws Exception {
        Path[] candidates = {
                Path.of("..", "backend-core", "src", "main", "java", "com", "czur", "reference",
                        "application", "persistence", "jdbc", name),
                Path.of("backend-core", "src", "main", "java", "com", "czur",
                        "reference", "application", "persistence", "jdbc", name)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("JDBC source file not found: " + name);
    }
}
