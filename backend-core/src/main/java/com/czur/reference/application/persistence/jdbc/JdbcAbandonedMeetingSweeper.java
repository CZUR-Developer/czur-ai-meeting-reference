package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.AbandonedMeetingFinalizer;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.SettleAbandonedRequest;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把"卡住的会议"分成从未开始与已开始但失联两类收口。
 *
 * <p><b>A 类</b>：{@code ai_trans_meeting_heartbeat} 无行，状态停在
 * {@code CREATED}／{@code STARTING}，且创建时间已超过无心跳窗口。处理顺序
 * 固定为"先零用量结算，再置 CANCELLED"：结算走
 * {@link EntitlementProvider#settleAbandoned}，失败时整轮跳过并保持会议原状态，
 * 下一轮自然重试；不能先取消，否则结算失败后再也没有扫描会处理它。</p>
 *
 * <p><b>B 类</b>：存在心跳行，状态 {@code STARTING}／{@code RUNNING}，且
 * {@code now - last_active_at} 已超过 {@code max(3 × 心跳间隔, 10 分钟)}。收尾
 * 委托 {@link AbandonedMeetingFinalizer}：结算时长取
 * {@code min(last_active_at - started_at, 部署最大会议时长)}，终态
 * {@code READY_WITH_WARNINGS}。Stop 已冻结的 {@code audio_duration_ms} 优先级更高，
 * 这类会议不按心跳结算。</p>
 *
 * <p>扫描本身不持有跨类事务，也不创建线程。A 类在会议行锁内完成"复核条件 →
 * 结算 → 取消"，锁住父行同时阻止心跳子行插入，避免结算后会议又恢复活跃；
 * B 类在收尾入口内重新校验，扫描结果只是候选。日志只记录数量与固定原因码。</p>
 */
public final class JdbcAbandonedMeetingSweeper {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcAbandonedMeetingSweeper.class.getName());

    /** 无心跳窗口：创建后从未出现心跳，超过该时长视为启动失败。 */
    public static final Duration DEFAULT_NO_HEARTBEAT_WINDOW = Duration.ofMinutes(15);
    /** 心跳失联窗口下限；实际值取 max(3 × 心跳间隔, 该值)。 */
    public static final Duration MIN_IDLE_WINDOW = Duration.ofMinutes(10);
    public static final int DEFAULT_BATCH_LIMIT = 50;
    private static final long IDLE_INTERVAL_MULTIPLIER = 3;

    private final JdbcDatabaseContext database;
    private final JdbcDatabaseEngine engine;
    private final String deploymentScope;
    private final Clock clock;
    private final EntitlementProvider entitlement;
    private final AbandonedMeetingFinalizer finalizer;
    private final Config config;

    public JdbcAbandonedMeetingSweeper(JdbcDatabaseContext database, String deploymentScope,
            Clock clock, EntitlementProvider entitlement,
            AbandonedMeetingFinalizer finalizer, Config config) {
        this.database = Objects.requireNonNull(database, "database");
        this.engine = database.engine();
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope");
        }
        this.deploymentScope = deploymentScope;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entitlement = entitlement;
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 一次扫描；A 类先处理，B 类后处理，两类各自独立失败重试。
     *
     * @return 本次实际取消、收口与暂缓的数量
     */
    public Result runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int cancelled = 0;
        int deferred = 0;
        for (String meetingId : findNeverStarted(now.minus(config.noHeartbeatWindow()))) {
            switch (cancelNeverStarted(meetingId, now)) {
                case CANCELLED -> cancelled++;
                case DEFERRED -> deferred++;
                case SKIPPED -> { }
            }
        }
        int finalized = 0;
        for (String meetingId : findIdle(now.minus(config.heartbeatIdleWindow()))) {
            try {
                if (finalizer.finalizeAbandoned(meetingId, config.heartbeatIdleWindow(),
                        config.maxMeetingDurationMs())) {
                    finalized++;
                }
            } catch (RuntimeException failure) {
                deferred++;
                LOGGER.log(Level.WARNING,
                        "弃场会议收尾失败，下个周期继续 type={0}",
                        failure.getClass().getSimpleName());
            }
        }
        return new Result(cancelled, finalized, deferred);
    }

    /** A 类候选：无心跳行、停在开始前、创建时间已超过窗口。 */
    private List<String> findNeverStarted(Instant threshold) {
        String sql = "SELECT m.meeting_id FROM "
                + database.table(JdbcDatabaseTable.MEETING) + " m"
                + " WHERE m.deployment_scope = ? AND m.state IN ('CREATED','STARTING')"
                + " AND m.created_at < ?"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_HEARTBEAT) + " h"
                + " WHERE h.meeting_id = m.meeting_id)"
                + " ORDER BY m.created_at LIMIT ?";
        return queryMeetingIds(sql, threshold, config.batchLimit());
    }

    /**
     * B 类候选：有心跳、最后活跃已严格早于窗口，且没有 Stop 冻结的采音用量。
     * Stop 用量存在时那场不是弃场，交给正常收尾链或人工处理。
     */
    private List<String> findIdle(Instant threshold) {
        String sql = "SELECT m.meeting_id FROM "
                + database.table(JdbcDatabaseTable.MEETING) + " m"
                + " JOIN " + database.table(JdbcDatabaseTable.MEETING_HEARTBEAT) + " h"
                + " ON h.meeting_id = m.meeting_id"
                + " WHERE m.deployment_scope = ? AND m.state IN ('STARTING','RUNNING')"
                + " AND h.last_active_at < ?"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_CLIENT_INPUT) + " ci"
                + " WHERE ci.meeting_id = m.meeting_id AND ci.audio_duration_ms IS NOT NULL)"
                + " ORDER BY h.last_active_at LIMIT ?";
        return queryMeetingIds(sql, threshold, config.batchLimit());
    }

    private List<String> queryMeetingIds(String sql, Instant threshold, int limit) {
        List<String> ids = new ArrayList<>();
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            JdbcValues.setInstant(statement, 2, threshold);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(JdbcValues.text(rows, "meeting_id"));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("abandoned meeting sweep failed", failure);
        }
        return ids;
    }

    /**
     * A 类收口：会议行锁内复核条件，零用量结算成功后才按领域状态机取消。
     *
     * <p>父会议行的排它锁会阻塞心跳子行插入，因此结算与取消之间不会出现
     * "会议又活了"的窗口；另一个 Stop 事务也必须先拿同一父行锁。</p>
     */
    private Outcome cancelNeverStarted(String meetingId, Instant now) {
        try (Connection connection = database.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcMeetingRepository.VersionedMeeting locked =
                        JdbcMeetingRepository.findVersionedForUpdate(connection, engine, meetingId)
                                .orElse(null);
                if (locked == null) {
                    connection.rollback();
                    return Outcome.SKIPPED;
                }
                MeetingAggregate.MeetingSnapshot snapshot = locked.snapshot();
                if (snapshot.state() != MeetingState.CREATED
                        && snapshot.state() != MeetingState.STARTING) {
                    connection.rollback();
                    return Outcome.SKIPPED;
                }
                if (!snapshot.createdAt().isBefore(now.minus(config.noHeartbeatWindow()))) {
                    connection.rollback();
                    return Outcome.SKIPPED;
                }
                if (heartbeatExists(connection, meetingId)) {
                    connection.rollback();
                    return Outcome.SKIPPED;
                }
                String reservationId = snapshot.usageReservationId();
                if (reservationId != null) {
                    if (entitlement == null) {
                        throw new IllegalStateException(
                                "abandoned settlement is not configured");
                    }
                    var settlement = entitlement.settleAbandoned(new SettleAbandonedRequest(
                            snapshot.deploymentScope(), snapshot.subjectScope(),
                            reservationId, meetingId,
                            new UsageActual(UsageUnit.MEETING_DURATION_MS, 0)));
                    if (!settlement.isSuccess()) {
                        connection.rollback();
                        LOGGER.log(Level.FINE,
                                "无心跳会议零用量结算未完成 reason={0}",
                                settlement.errorOrThrow().code());
                        return Outcome.DEFERRED;
                    }
                }
                // 没有预占就没有可结算事实；不因此把会议永久留在开始前。
                Instant cancelledAt = now.isBefore(snapshot.updatedAt())
                        ? snapshot.updatedAt() : now;
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(snapshot);
                aggregate.apply(MeetingEvent.CANCEL, cancelledAt);
                JdbcMeetingRepository.updateOwnedExisting(connection, engine, locked,
                        aggregate.snapshot());
                connection.commit();
                return Outcome.CANCELLED;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                LOGGER.log(Level.WARNING,
                        "无心跳会议收口暂未完成，下个周期继续 type={0}",
                        failure.getClass().getSimpleName());
                return Outcome.DEFERRED;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("abandoned meeting sweep database unavailable",
                    failure);
        }
    }

    private boolean heartbeatExists(Connection connection, String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + database.table(JdbcDatabaseTable.MEETING_HEARTBEAT)
                        + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private enum Outcome {
        CANCELLED,
        DEFERRED,
        SKIPPED
    }

    /** 单次扫描结果；deferred 表示结算或收尾失败、留待下一轮。 */
    public record Result(int cancelledMeetings, int finalizedMeetings, int deferredMeetings) {
    }

    /**
     * 扫描配置；生产默认值：无心跳窗口 15 分钟，失联窗口
     * {@code max(3 × 心跳间隔, 10 分钟)}，单轮每类最多 50 场。
     */
    public record Config(Duration noHeartbeatWindow, Duration heartbeatIdleWindow,
            long maxMeetingDurationMs, int batchLimit) {
        public Config {
            requirePositive("noHeartbeatWindow", noHeartbeatWindow);
            requirePositive("heartbeatIdleWindow", heartbeatIdleWindow);
            if (maxMeetingDurationMs <= 0) {
                throw new IllegalArgumentException("maxMeetingDurationMs must be positive");
            }
            if (batchLimit <= 0) {
                throw new IllegalArgumentException("batchLimit must be positive");
            }
        }

        /** 由部署心跳间隔和最大会议时长推导默认窗口。 */
        public static Config defaults(int heartbeatIntervalMs, long maxMeetingDurationMs) {
            if (heartbeatIntervalMs <= 0) {
                throw new IllegalArgumentException("heartbeatIntervalMs must be positive");
            }
            Duration scaled = Duration.ofMillis(
                    IDLE_INTERVAL_MULTIPLIER * heartbeatIntervalMs);
            Duration idleWindow = scaled.compareTo(MIN_IDLE_WINDOW) > 0
                    ? scaled : MIN_IDLE_WINDOW;
            return new Config(DEFAULT_NO_HEARTBEAT_WINDOW, idleWindow,
                    maxMeetingDurationMs, DEFAULT_BATCH_LIMIT);
        }

        private static void requirePositive(String name, Duration value) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
