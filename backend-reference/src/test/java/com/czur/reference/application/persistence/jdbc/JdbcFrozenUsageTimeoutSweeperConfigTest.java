package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不连数据库的冻结用量超时窗口推导与预占 TTL 边界合同。 */
class JdbcFrozenUsageTimeoutSweeperConfigTest {

    @Test
    void defaultWindowIsThreeHeartbeatIntervalsButNeverBelowTenMinutes() {
        assertEquals(Duration.ofMinutes(10),
                JdbcFrozenUsageTimeoutSweeper.Config.defaults(120_000).stopWaitWindow());
        assertEquals(Duration.ofMinutes(30),
                JdbcFrozenUsageTimeoutSweeper.Config.defaults(600_000).stopWaitWindow());
        assertEquals(JdbcFrozenUsageTimeoutSweeper.DEFAULT_BATCH_LIMIT,
                JdbcFrozenUsageTimeoutSweeper.Config.defaults(120_000).batchLimit());
    }

    @Test
    void windowNeverExceedsReservationTtlMinusSettlementMargin() {
        // 线上配置：5 分钟 TTL 装不下 10 分钟等待窗口，必须收敛到 4 分钟。
        JdbcFrozenUsageTimeoutSweeper.Config clamped = JdbcFrozenUsageTimeoutSweeper.Config
                .derive(120_000, Duration.ofMinutes(5));
        assertEquals(Duration.ofMinutes(4), clamped.stopWaitWindow());

        // TTL 足够长时不改变推导窗口。
        JdbcFrozenUsageTimeoutSweeper.Config unchanged = JdbcFrozenUsageTimeoutSweeper.Config
                .derive(120_000, Duration.ofMinutes(30));
        assertEquals(Duration.ofMinutes(10), unchanged.stopWaitWindow());

        for (Duration ttl : new Duration[]{
                Duration.ofSeconds(90), Duration.ofMinutes(5), Duration.ofMinutes(10),
                Duration.ofMinutes(10).plusSeconds(59), Duration.ofMinutes(11),
                Duration.ofMinutes(11).plusSeconds(60), Duration.ofHours(1)}) {
            Duration window = JdbcFrozenUsageTimeoutSweeper.Config
                    .derive(120_000, ttl).stopWaitWindow();
            assertTrue(window.compareTo(ttl
                            .minus(JdbcFrozenUsageTimeoutSweeper.SETTLEMENT_MARGIN)) <= 0,
                    () -> "window " + window + " exceeded ttl " + ttl);
        }
    }

    @Test
    void clampBoundaryKeepsOneFullSettlementMargin() {
        Duration ttl = Duration.ofMinutes(10)
                .plus(JdbcFrozenUsageTimeoutSweeper.SETTLEMENT_MARGIN);
        assertEquals(Duration.ofMinutes(10), JdbcFrozenUsageTimeoutSweeper.Config
                .derive(120_000, ttl).stopWaitWindow());

        Duration oneSecondBelow = ttl.minusSeconds(1);
        assertEquals(Duration.ofMinutes(9).plusSeconds(59),
                JdbcFrozenUsageTimeoutSweeper.Config
                        .derive(120_000, oneSecondBelow).stopWaitWindow());
    }

    @Test
    void rejectsTtlThatCannotLeaveSettlementMarginAndSchedulingWindow() {
        // 余量 60 秒之外还必须留下至少一个 30 秒调度周期。
        assertThrows(IllegalArgumentException.class,
                () -> JdbcFrozenUsageTimeoutSweeper.Config.derive(120_000, Duration.ofSeconds(89)));
        assertEquals(Duration.ofSeconds(30),
                JdbcFrozenUsageTimeoutSweeper.Config
                        .derive(120_000, Duration.ofSeconds(90)).stopWaitWindow());
        assertThrows(IllegalArgumentException.class,
                () -> JdbcFrozenUsageTimeoutSweeper.Config.derive(120_000, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcFrozenUsageTimeoutSweeper.Config.derive(0, Duration.ofMinutes(5)));
        assertThrows(NullPointerException.class,
                () -> JdbcFrozenUsageTimeoutSweeper.Config.derive(120_000, null));
    }
}
