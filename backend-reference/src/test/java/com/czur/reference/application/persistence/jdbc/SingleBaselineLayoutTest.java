package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 首次正式发布前的交付布局门禁：每个数据库引擎只允许一份当前初始化基线。
 *
 * <p>本测试不连接数据库，因此默认 Maven 测试始终执行；正式发布后如需增量升级，
 * 应先明确改变本约束和发布流程，不应在开发过程中悄悄累加 V002 等文件。</p>
 */
class SingleBaselineLayoutTest {
    @Test
    void eachEngineDeliversOnlyItsCurrentV001Sql() throws Exception {
        Path directory = Path.of("deploy", "migrations");
        if (!Files.isDirectory(directory)) {
            directory = Path.of("..", "deploy", "migrations");
        }
        assertCurrentBaseline(directory);
        assertCurrentBaseline(directory.resolve("mysql"));
        try (var files = Files.walk(directory)) {
            assertEquals(List.of("V001__initial_schema.sql", "mysql/V001__initial_schema.sql"),
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
        }
    }
}
