package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 首次正式发布前的交付布局门禁：数据库只允许一份当前建库基线。
 *
 * <p>本测试不连接数据库，因此默认 Maven 测试始终执行；正式发布后如需增量升级，
 * 应先明确改变本约束和发布流程，不应在开发过程中悄悄累加 V002 等文件。</p>
 */
class SingleBaselineLayoutTest {
    @Test
    void deliveryContainsOnlyTheCurrentV001Sql() throws Exception {
        Path directory = Path.of("deploy", "migrations");
        if (!Files.isDirectory(directory)) {
            directory = Path.of("..", "deploy", "migrations");
        }
        try (var files = Files.list(directory)) {
            assertEquals(List.of("V001__initial_schema.sql"), files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.getFileName().toString()).sorted().toList());
        }
    }
}
