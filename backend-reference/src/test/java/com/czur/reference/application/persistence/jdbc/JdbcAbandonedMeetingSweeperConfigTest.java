package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.SettleAbandonedRequest;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageUnit;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 不连数据库的窗口推导与弃场幂等键合同。 */
class JdbcAbandonedMeetingSweeperConfigTest {
    private static final long MAX_MEETING_DURATION_MS = Duration.ofHours(5).toMillis();

    @Test
    void idleWindowIsThreeHeartbeatIntervalsButNeverBelowTenMinutes() {
        JdbcAbandonedMeetingSweeper.Config defaultInterval =
                JdbcAbandonedMeetingSweeper.Config.defaults(120_000, MAX_MEETING_DURATION_MS);
        assertEquals(Duration.ofMinutes(15), defaultInterval.noHeartbeatWindow());
        assertEquals(Duration.ofMinutes(10), defaultInterval.heartbeatIdleWindow());
        assertEquals(MAX_MEETING_DURATION_MS, defaultInterval.maxMeetingDurationMs());
        assertEquals(JdbcAbandonedMeetingSweeper.DEFAULT_BATCH_LIMIT,
                defaultInterval.batchLimit());

        // 间隔放大后由三倍间隔接管，不再被十分钟下限截断。
        JdbcAbandonedMeetingSweeper.Config longInterval =
                JdbcAbandonedMeetingSweeper.Config.defaults(600_000, MAX_MEETING_DURATION_MS);
        assertEquals(Duration.ofMinutes(30), longInterval.heartbeatIdleWindow());
    }

    @Test
    void rejectsNonPositiveWindowIntervalOrBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcAbandonedMeetingSweeper.Config.defaults(0, MAX_MEETING_DURATION_MS));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcAbandonedMeetingSweeper.Config.defaults(120_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAbandonedMeetingSweeper.Config(Duration.ZERO,
                        Duration.ofMinutes(10), MAX_MEETING_DURATION_MS, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAbandonedMeetingSweeper.Config(Duration.ofMinutes(15),
                        Duration.ofMinutes(10), MAX_MEETING_DURATION_MS, 0));
    }

    @Test
    void abandonedSettlementKeyIsStablePerMeeting() {
        SettleAbandonedRequest request = new SettleAbandonedRequest(
                "deployment", "subject", "reservation_mtg_key", "mtg_key",
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 0));
        assertEquals("meeting.abandon.v1:mtg_key", request.idempotencyKey());
    }
}
