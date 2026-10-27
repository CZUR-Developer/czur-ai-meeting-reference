package com.czur.reference.application.artifact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只验证预算公式，不为五小时用例分配正文或磁盘。 */
class MeetingBodyArtifactBudgetTest {
    @Test
    void budgetFollowsDeclaredDurationAndKeepsLegacyFloor() {
        assertEquals(MeetingBodyArtifactBudget.LEGACY_MAX_BYTES,
                MeetingBodyArtifactBudget.forMaxDurationMs(1));
        // 短会（含旧参考门禁的 30 分钟）不因公式低于 2 MiB 兼容下限。
        assertEquals(2L * 1024 * 1024,
                MeetingBodyArtifactBudget.forMaxDurationMs(1_800_000));
        long fiveHours = MeetingBodyArtifactBudget.forMaxDurationMs(18_000_000);
        assertEquals(300L * 16 * 1024 + 64 * 1024, fiveHours);
        assertTrue(fiveHours > 1_200_000, "五小时会议至少能承载评审中的约 1.08 MB 正文");
    }

    @Test
    void invalidDurationIsRejectedInsteadOfSilentlyUsingZero() {
        assertThrows(IllegalArgumentException.class,
                () -> MeetingBodyArtifactBudget.forMaxDurationMs(0));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingBodyArtifactBudget.forMaxDurationMs(-1));
    }
}
