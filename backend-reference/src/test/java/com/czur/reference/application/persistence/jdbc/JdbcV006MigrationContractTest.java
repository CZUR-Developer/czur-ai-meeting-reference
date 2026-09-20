package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖 PostgreSQL 的 V006 源码契约门。
 *
 * <p>真实约束行为由 {@link JdbcV006MigrationIntegrationTest} 验证；本测试保证在
 * 默认 Maven 单元测试环境中，领域枚举发生变化时不会静默遗漏数据库迁移。它只
 * 解析两个具名 CHECK 约束，不把注释或另一个状态集合中的同名值误判为已覆盖。</p>
 */
class JdbcV006MigrationContractTest {
    private static final Pattern QUOTED_VALUE = Pattern.compile("'([A-Z_]+)'");

    @Test
    void databaseStateConstraintsMatchCurrentDomainEnums() throws Exception {
        String sql = readMigration();

        Set<String> persistentMeetingStates = Arrays.stream(MeetingState.values())
                .filter(state -> state != MeetingState.UNKNOWN)
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(persistentMeetingStates, constraintValues(sql, "meeting_state_ck"));
        assertEquals(
                Arrays.stream(ManifestStatus.values())
                        .map(Enum::name)
                        .collect(Collectors.toUnmodifiableSet()),
                constraintValues(sql, "meeting_manifest_state_ck"));
    }

    @Test
    void migrationDefinesDefaultAndStableOwnerListIndex() throws Exception {
        String normalized = readMigration().replaceAll("\\s+", " ");

        assertTrue(normalized.contains(
                "ADD COLUMN IF NOT EXISTS manifest_state TEXT NOT NULL DEFAULT 'NOT_STARTED'"));
        assertTrue(normalized.contains(
                "ON meeting ( deployment_scope, subject_scope, created_at DESC, meeting_id DESC)"));
    }

    private static Set<String> constraintValues(String sql, String constraintName) {
        Pattern block = Pattern.compile(
                "ADD CONSTRAINT\\s+" + Pattern.quote(constraintName)
                        + "\\s+CHECK\\s*\\([^;]+?\\)\\);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher constraint = block.matcher(sql);
        assertTrue(constraint.find(), "missing constraint " + constraintName);
        Matcher values = QUOTED_VALUE.matcher(constraint.group());
        return values.results()
                .map(result -> result.group(1))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String readMigration() throws IOException {
        Path[] candidates = {
                Path.of("deploy", "migrations", "V006__meeting_read_model.sql"),
                Path.of("..", "deploy", "migrations", "V006__meeting_read_model.sql")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("V006 migration file not found");
    }
}
