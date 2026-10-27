package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ClientMeetingResultFinalizer;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingOwnership;
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
 * 收口“已冻结采音用量、却超过窗口没有 Stop”的会议。
 *
 * <p>{@code usage:submit} 冻结 {@code audio_duration_ms} 后，会议仍可能停在
 * STARTING／RUNNING；客户端随后崩溃、没有发送 Stop。原有弃场扫描刻意跳过有
 * 冻结用量的会议，过期预占清理又不会碰已绑定预占，因此这类会议会永久残留。
 * 本扫描器补上第三条恢复链：锁定会议、复核冻结用量与窗口、写入
 * {@code USAGE_TIMEOUT} 待办并把会议推进到 {@code STOPPING}，然后调用与正常
 * Stop 相同的 Finalizer；结算使用冻结用量和同场稳定键，终态按结果能力为
 * READY／READY_WITH_WARNINGS，不是零结算，也不写弃场警告。</p>
 *
 * <p>与弃场扫描互斥：候选查询和行锁内复核都要求没有
 * {@code ai_trans_meeting_abandonment} 决定，而弃场扫描也跳过已有冻结用量的
 * 会议。决定事务先落持久待办，Finalizer 失败时由待办扫描下一轮重试，不会在
 * 同一轮用第二条链重复处理。</p>
 *
 * <p>等待窗口不是独立常量：它必须小于权益预占 TTL，否则扫描注定在预占到期后
 * 才发生。装配层使用 {@link Config#derive(int, Duration)} 把窗口收敛到
 * {@code TTL - 60 秒}；已绑定预占的结算本身允许过期，收敛窗口只是让正常路径
 * 尽量在到期前完成，并避免配置矛盾被拖成后台永久失败。</p>
 */
public final class JdbcFrozenUsageTimeoutSweeper {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcFrozenUsageTimeoutSweeper.class.getName());

    /** 冻结用量后等待 Stop 的默认下限；实际取 max(3 × 心跳间隔, 该值)。 */
    public static final Duration MIN_STOP_WAIT_WINDOW = Duration.ofMinutes(10);
    /**
     * 预占到期前必须留给收口事务的结算余量。
     *
     * <p>扫描周期是 30 秒，Finalizer 还要完成结算、结果发布和待办删除；窗口
     * 即使等于 TTL 也会在上一次扫描与本次扫描之间越过到期时刻。固定留出
     * 60 秒，既能容纳一轮调度抖动，也让正常路径尽量在预占到期前完成结算。
     * 到期后才收口的会议仍由“已绑定预占允许过期结算”兜底，不依赖本余量。</p>
     */
    public static final Duration SETTLEMENT_MARGIN = Duration.ofSeconds(60);
    /** 收敛后的窗口至少覆盖一个调度周期，否则配置没有可用的恢复点。 */
    private static final Duration MIN_EFFECTIVE_WINDOW = Duration.ofSeconds(30);
    public static final int DEFAULT_BATCH_LIMIT = 50;
    private static final long HEARTBEAT_MULTIPLIER = 3;

    private final JdbcDatabaseContext database;
    private final JdbcDatabaseEngine engine;
    private final String deploymentScope;
    private final Clock clock;
    private final ClientMeetingResultFinalizer finalizer;
    private final Config config;

    public JdbcFrozenUsageTimeoutSweeper(JdbcDatabaseContext database, String deploymentScope,
            Clock clock, ClientMeetingResultFinalizer finalizer, Config config) {
        this.database = Objects.requireNonNull(database, "database");
        this.engine = database.engine();
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope must not be blank");
        }
        this.deploymentScope = deploymentScope;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 发现候选、落持久待办并立即尝试收口。
     *
     * @return 本轮已完成收口与暂缓的数量；暂缓的会议已落待办，由待办扫描重试
     */
    public Result runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(config.stopWaitWindow());
        int finalized = 0;
        int deferred = 0;
        for (String meetingId : findCandidates(cutoff)) {
            try {
                if (!writeDecision(meetingId, cutoff, now)) {
                    continue;
                }
                if (finalizer.finalizePendingClient(meetingId)) {
                    finalized++;
                } else {
                    deferred++;
                }
            } catch (RuntimeException failure) {
                deferred++;
                LOGGER.log(Level.WARNING,
                        "冻结用量超时会议收口失败，下个周期继续 reason={0}",
                        failure.getClass().getSimpleName());
            }
        }
        return new Result(finalized, deferred);
    }

    /**
     * 决定事务：锁会议、复核对冻结用量与互斥条件，写待办并推进到 STOPPING。
     *
     * <p>状态推进与待办插入同事务提交；进程在结算前崩溃时，会议仍能被待办
     * 扫描识别并重试，不会留下“STOPPING 但无人收尾”的窗口。</p>
     */
    private boolean writeDecision(String meetingId, Instant cutoff, Instant now) {
        try (Connection connection = database.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcMeetingRepository.VersionedMeeting locked =
                        JdbcMeetingRepository.findVersionedForUpdate(connection, engine, meetingId)
                                .orElse(null);
                if (locked == null) {
                    connection.rollback();
                    return false;
                }
                MeetingAggregate.MeetingSnapshot snapshot = locked.snapshot();
                if (snapshot.state() != MeetingState.STARTING
                        && snapshot.state() != MeetingState.RUNNING) {
                    connection.rollback();
                    return false;
                }
                // 与弃场链互斥：对方已落决定时让位；候选查询已排除一次，这里在
                // 会议行锁内再核对，避免同一会议被两条恢复链同时处理。
                if (JdbcMeetingAbandonmentStore.exists(
                        connection, engine, meetingId, false)) {
                    connection.rollback();
                    return false;
                }
                if (JdbcClientFinalizationPendingStore.find(
                        connection, engine, meetingId, true).isPresent()) {
                    connection.rollback();
                    return false;
                }
                FrozenUsage usage = readFrozenUsage(connection, meetingId);
                if (usage == null || !usage.submittedAt().isBefore(cutoff)) {
                    connection.rollback();
                    return false;
                }
                JdbcClientFinalizationPendingStore.mark(connection, engine, meetingId,
                        snapshot.deploymentScope(),
                        JdbcClientFinalizationPendingStore.Reason.USAGE_TIMEOUT, now);
                Instant stoppedAt = now.isBefore(snapshot.updatedAt())
                        ? snapshot.updatedAt() : now;
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(snapshot);
                aggregate.apply(MeetingEvent.STOP_REQUESTED, stoppedAt);
                if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                        snapshot.subjectScope())) {
                    JdbcMeetingRepository.updateLegacyExisting(connection, engine, locked,
                            aggregate.snapshot());
                } else {
                    JdbcMeetingRepository.updateOwnedExisting(connection, engine, locked,
                            aggregate.snapshot());
                }
                connection.commit();
                LOGGER.log(Level.INFO,
                        "冻结用量超时会议已落待办并推进到 STOPPING durationMs={0}",
                        usage.audioDurationMs());
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("frozen usage decision write failed", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("frozen usage sweep database unavailable", failure);
        }
    }

    private FrozenUsage readFrozenUsage(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT audio_duration_ms, usage_submitted_at FROM "
                        + JdbcDatabaseTable.MEETING_CLIENT_INPUT.physicalName(engine)
                        + " WHERE meeting_id=?"
                        + (engine == JdbcDatabaseEngine.MYSQL ? " FOR UPDATE" : ""))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Long duration = rows.getObject("audio_duration_ms", Long.class);
                Instant submittedAt = JdbcValues.instant(rows, "usage_submitted_at");
                if (duration == null || submittedAt == null) return null;
                return new FrozenUsage(duration, submittedAt);
            }
        }
    }

    /** 候选查询必须与决定事务的复核条件保持一致，且排除已落决定或待办。 */
    private List<String> findCandidates(Instant cutoff) {
        String sql = "SELECT m.meeting_id FROM "
                + database.table(JdbcDatabaseTable.MEETING) + " m"
                + " JOIN " + database.table(JdbcDatabaseTable.MEETING_CLIENT_INPUT) + " ci"
                + " ON ci.meeting_id = m.meeting_id"
                + " WHERE m.deployment_scope = ? AND m.state IN ('STARTING','RUNNING')"
                + " AND ci.audio_duration_ms IS NOT NULL"
                + " AND ci.usage_submitted_at < ?"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_ABANDONMENT) + " a"
                + " WHERE a.meeting_id = m.meeting_id)"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_FINALIZATION_PENDING) + " p"
                + " WHERE p.meeting_id = m.meeting_id)"
                + " ORDER BY ci.usage_submitted_at LIMIT ?";
        List<String> ids = new ArrayList<>();
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            JdbcValues.setInstant(statement, 2, cutoff);
            statement.setInt(3, config.batchLimit());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(JdbcValues.text(rows, "meeting_id"));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("frozen usage sweep failed", failure);
        }
        return ids;
    }

    private record FrozenUsage(long audioDurationMs, Instant submittedAt) { }

    /** 单轮结果；deferred 表示已落待办但本轮收口尚未完成。 */
    public record Result(int finalizedMeetings, int deferredMeetings) { }

    /**
     * 扫描配置。等待窗口由部署心跳间隔推导，与弃场失联窗口一致取
     * {@code max(3 × 心跳间隔, 10 分钟)}；单轮最多处理 {@code batchLimit} 场。
     *
     * <p>窗口不能独立于预占寿命：预占 TTL 小于推导窗口时，扫描必然在预占
     * 到期之后才发生。装配层必须使用
     * {@link #derive(int, Duration)}，让窗口收敛到 {@code TTL - 结算余量}；
     * 本记录不做隐式收敛，保持纯数据语义，便于测试和显式装配。</p>
     */
    public record Config(Duration stopWaitWindow, int batchLimit) {
        public Config {
            Objects.requireNonNull(stopWaitWindow, "stopWaitWindow");
            if (stopWaitWindow.isZero() || stopWaitWindow.isNegative()) {
                throw new IllegalArgumentException("stopWaitWindow must be positive");
            }
            if (batchLimit <= 0) {
                throw new IllegalArgumentException("batchLimit must be positive");
            }
        }

        public static Config defaults(int heartbeatIntervalMs) {
            return new Config(derivedStopWaitWindow(heartbeatIntervalMs), DEFAULT_BATCH_LIMIT);
        }

        /**
         * 按心跳间隔和预占 TTL 推导扫描窗口。
         *
         * <p>取 {@code min(max(3 × 心跳间隔, 10 分钟), TTL - 60 秒)}。窗口被 TTL
         * 压缩时记录 WARNING，保留原始推导值、实际 TTL 和余量，部署方可以据此
         * 判断是否需要延长预占 TTL 而不是让后台在到期后永久失败。TTL 无法留出
         * 一个调度周期时直接拒绝装配：这属于配置矛盾，不应等到运行时才暴露。</p>
         *
         * @param heartbeatIntervalMs 部署心跳间隔，必须为正
         * @param reservationTtl 权益预占 TTL，必须能容纳结算余量和一个调度周期
         * @return 不超过预占寿命的扫描配置
         */
        public static Config derive(int heartbeatIntervalMs, Duration reservationTtl) {
            Objects.requireNonNull(reservationTtl, "reservationTtl");
            Duration derived = derivedStopWaitWindow(heartbeatIntervalMs);
            Duration maxWindow = reservationTtl.minus(SETTLEMENT_MARGIN);
            if (maxWindow.compareTo(MIN_EFFECTIVE_WINDOW) < 0) {
                throw new IllegalArgumentException(
                        "reservationTtl must leave at least " + MIN_EFFECTIVE_WINDOW
                                + " after the " + SETTLEMENT_MARGIN + " settlement margin: "
                                + reservationTtl);
            }
            Duration window = derived.compareTo(maxWindow) > 0 ? maxWindow : derived;
            if (window.equals(derived)) {
                LOGGER.log(Level.INFO,
                        "冻结用量超时窗口已按预占 TTL 校验 windowMs={0} reservationTtlMs={1}",
                        new Object[]{window.toMillis(), reservationTtl.toMillis()});
            } else {
                LOGGER.log(Level.WARNING,
                        "冻结用量超时窗口受预占 TTL 限制 derivedMs={0} effectiveMs={1}"
                                + " reservationTtlMs={2} marginMs={3}",
                        new Object[]{derived.toMillis(), window.toMillis(),
                                reservationTtl.toMillis(), SETTLEMENT_MARGIN.toMillis()});
            }
            return new Config(window, DEFAULT_BATCH_LIMIT);
        }

        private static Duration derivedStopWaitWindow(int heartbeatIntervalMs) {
            if (heartbeatIntervalMs <= 0) {
                throw new IllegalArgumentException("heartbeatIntervalMs must be positive");
            }
            Duration scaled = Duration.ofMillis(
                    Math.multiplyExact(HEARTBEAT_MULTIPLIER, (long) heartbeatIntervalMs));
            return scaled.compareTo(MIN_STOP_WAIT_WINDOW) > 0 ? scaled : MIN_STOP_WAIT_WINDOW;
        }
    }
}
