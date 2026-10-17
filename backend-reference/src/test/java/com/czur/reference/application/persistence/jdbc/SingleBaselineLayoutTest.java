package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首次正式发布前的交付布局门禁：参考方案只交付一份 MySQL 5.7 当前初始化基线。
 *
 * <p>本测试不连接数据库，因此默认 Maven 测试始终执行；正式发布后如需增量升级，
 * 应先明确改变本约束和发布流程，不应在开发过程中悄悄累加 V002 等文件。</p>
 */
class SingleBaselineLayoutTest {
    @Test
    void referenceDeliversOnlyOneMysqlV001Sql() throws Exception {
        Path directory = Path.of("deploy", "migrations");
        if (!Files.isDirectory(directory)) {
            directory = Path.of("..", "deploy", "migrations");
        }
        assertCurrentBaseline(directory);
        try (var files = Files.walk(directory)) {
            assertEquals(List.of("V001__initial_schema.sql"),
                    files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                            .map(directory::relativize)
                            .map(path -> path.toString().replace('\\', '/')).sorted().toList());
        }
    }

    private static void assertCurrentBaseline(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(List.of("V001__initial_schema.sql"), files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.getFileName().toString()).sorted().toList());
            Path baseline = directory.resolve("V001__initial_schema.sql");
            String sql = Files.readString(baseline);
            assertTrue(sql.contains("ai_trans_schema_migrations"));
            assertTrue(sql.contains("ENGINE=InnoDB"));
        }
    }
}
