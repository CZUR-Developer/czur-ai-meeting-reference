package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.AbandonedMeetingFinalizer;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.SettleAbandonedRequest;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageUnit;
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
 * 把"卡住的会议"分成从未开始与已开始但失联两类收口。
 *
 * <p><b>决定先行</b>：扫描命中后先在一个短事务里锁定会议、复核候选条件并写入
 * {@code ai_trans_meeting_abandonment}，事务提交后才在连接外调用
 * {@link EntitlementProvider#settleAbandoned}。决定行保存冻结的结算时长、
 * 目标终态与决定时间；落库后该会议不可再活跃（心跳与客户端输入均被拒绝）。
 * 结算失败保留决定，下一轮只重试结算；结算成功后另开事务写结果/终态并删除
 * 决定。重复执行按决定行幂等收敛，不会重复结算。</p>
 *
 * <p><b>A 类</b>：{@code ai_trans_meeting_heartbeat} 无行，状态停在
 * {@code CREATED}／{@code STARTING}，且创建时间已超过无心跳窗口。决定为
 * {@code NEVER_STARTED → CANCELLED}，结算时长固定 0；没有预占的历史会议在
 * 落决定时即视为已结算。客户端已提交冻结采音用量（{@code audio_duration_ms}
 * 非空）时说明本场正在走正常 Stop，候选查询与行锁内复核都跳过，不按“从未开始”
 * 取消或零结算。</p>
 *
 * <p><b>B 类</b>：存在心跳行，状态 {@code STARTING}／{@code RUNNING}，且
 * {@code now - last_active_at} 已超过 {@code max(3 × 心跳间隔, 10 分钟)}。
 * 决定为 {@code ABANDONED → READY_WITH_WARNINGS}，结算时长取
 * {@code min(last_active_at - started_at, 部署最大会议时长)}；落决定时同时把
 * 会议冻结到 {@code STOPPING}。Stop 已冻结的 {@code audio_duration_ms} 优先，
 * 这类会议不落决定、继续走正常 Stop 收尾。</p>
 *
 * <p>任何路径都不在持有池连接时再借第二个连接：决定、结算标记、取消、结果写
 * 各用独立的短事务，{@code DATABASE_MAX_POOL_SIZE=1} 也能完成。日志只记录
 * 数量与固定原因码。</p>
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
     * 一次扫描：先恢复上次已落决定的会议，再发现新候选。
     *
     * <p>已落决定的会议优先处理，避免先发现的新候选把本轮批次占满而让旧决定
     * 长期得不到重试。两类失败各自计为 deferred，下一轮继续。</p>
     *
     * @return 本次实际取消、收口、暂缓的数量
     */
    public Result runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Counts counts = new Counts();
        for (String meetingId : findPendingDecisions()) {
            counts.count(processSafely(meetingId, now));
        }
        for (String meetingId : findNeverStarted(now.minus(config.noHeartbeatWindow()))) {
            counts.count(decideThenProcess(meetingId, now, true));
        }
        for (String meetingId : findIdle(now.minus(config.heartbeatIdleWindow()))) {
            counts.count(decideThenProcess(meetingId, now, false));
        }
        return new Result(counts.cancelled, counts.finalized, counts.deferred);
    }

    /**
     * 已落决定的重试入口。
     *
     * <p>先确认结算标记：{@code DECIDED} 时在连接外调用 Provider，成功后再用
     * 独立事务标记 {@code SETTLED}。已结算的决定只重试结果/终态写入。</p>
     */
    private Outcome processSafely(String meetingId, Instant now) {
        try {
            return processDecision(meetingId, now);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "弃场决定重试失败，下个周期继续 type={0}",
                    failure.getClass().getSimpleName());
            return Outcome.DEFERRED;
        }
    }

    private Outcome processDecision(String meetingId, Instant now) {
        JdbcMeetingAbandonmentStore.Decision decision = readDecision(meetingId);
        if (decision == null) {
            return Outcome.SKIPPED;
        }
        if (decision.needsSettlement()) {
            if (!settle(decision)) {
                return Outcome.DEFERRED;
            }
            if (!markSettled(decision, now)) {
                return Outcome.DEFERRED;
            }
        }
        return switch (decision.kind()) {
            case NEVER_STARTED -> cancelDecided(meetingId, now);
            case ABANDONED -> finalizeDecided(meetingId, decision);
        };
    }

    /** 在连接外调用权益端口；连接池大小为 1 时也不会阻塞在自己身上。 */
    private boolean settle(JdbcMeetingAbandonmentStore.Decision decision) {
        if (decision.usageReservationId() == null) {
            // 没有预占就没有可结算事实；落决定时已按 SETTLED 处理。
            return true;
        }
        if (entitlement == null) {
            throw new IllegalStateException("abandoned settlement is not configured");
        }
        var settlement = entitlement.settleAbandoned(new SettleAbandonedRequest(
                decision.deploymentScope(), decision.subjectScope(),
                decision.usageReservationId(), decision.meetingId(),
                new UsageActual(UsageUnit.MEETING_DURATION_MS, decision.settleDurationMs())));
        if (!settlement.isSuccess()) {
            LOGGER.log(Level.FINE, "弃场会议结算未完成 reason={0}",
                    settlement.errorOrThrow().code());
            return false;
        }
        return true;
    }

    /** 结算成功后单独提交标记；失败时保留 DECIDED，下一轮按 Provider 幂等键重放。 */
    private boolean markSettled(JdbcMeetingAbandonmentStore.Decision decision, Instant now) {
        try (Connection connection = database.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean advanced = JdbcMeetingAbandonmentStore.markSettled(
                        connection, engine, decision.meetingId(), now);
                // 另一实例已推进或已收尾删除都算完成；仍停在 DECIDED 才需要下轮重试。
                var current = JdbcMeetingAbandonmentStore.find(
                        connection, engine, decision.meetingId(), false);
                connection.commit();
                return advanced || current.isEmpty()
                        || current.orElseThrow().settlement()
                                == JdbcMeetingAbandonmentStore.Settlement.SETTLED;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                LOGGER.log(Level.WARNING,
                        "弃场结算标记未完成，下个周期继续 type={0}",
                        failure.getClass().getSimpleName());
                return false;
            }
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING,
                    "弃场结算标记数据库不可用 type={0}",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    private Outcome decideThenProcess(String meetingId, Instant now, boolean neverStarted) {
        try {
            boolean written = neverStarted
                    ? writeNeverStartedDecision(meetingId, now)
                    : writeAbandonedDecision(meetingId, now);
            if (!written) {
                return Outcome.SKIPPED;
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "弃场决定落库失败，下个周期继续 type={0}",
                    failure.getClass().getSimpleName());
            return Outcome.DEFERRED;
        }
        // 决定事务的连接已关闭，才允许结算或另开收尾事务。
        return processSafely(meetingId, now);
    }

    /**
     * A 类决定事务：锁读、复核、插入决定；不结算、不改会议状态。
     *
     * <p>状态保持原样是刻意的：结算失败时会议仍停在开始前，但决定行已让心跳、
     * 客户端输入和 Start 拒绝该会议；取消只发生在零用量结算成功之后。</p>
     */
    private boolean writeNeverStartedDecision(String meetingId, Instant now) {
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
                if (snapshot.state() != MeetingState.CREATED
                        && snapshot.state() != MeetingState.STARTING) {
                    connection.rollback();
                    return false;
                }
                if (!snapshot.createdAt().isBefore(now.minus(config.noHeartbeatWindow()))) {
                    connection.rollback();
                    return false;
                }
                if (heartbeatExists(connection, meetingId)
                        || stopUsageSubmitted(connection, meetingId)
                        || JdbcMeetingAbandonmentStore.exists(
                                connection, engine, meetingId, false)) {
                    // 首跳之后又提交了冻结用量：本场已进入正常 Stop 路径，不能按
                    // “从未开始”取消并结算 0。候选查询已排除一次，这里做行锁内复核。
                    connection.rollback();
                    return false;
                }
                boolean noReservation = snapshot.usageReservationId() == null;
                JdbcMeetingAbandonmentStore.Decision decision =
                        new JdbcMeetingAbandonmentStore.Decision(
                                meetingId,
                                JdbcMeetingAbandonmentStore.Kind.NEVER_STARTED,
                                snapshot.deploymentScope(), snapshot.subjectScope(),
                                JdbcMeetingAbandonmentStore.targetState(
                                        JdbcMeetingAbandonmentStore.Kind.NEVER_STARTED),
                                now, snapshot.createdAt(), snapshot.createdAt(), 0,
                                snapshot.usageReservationId(),
                                noReservation
                                        ? JdbcMeetingAbandonmentStore.Settlement.SETTLED
                                        : JdbcMeetingAbandonmentStore.Settlement.DECIDED,
                                noReservation ? now : null);
                JdbcMeetingAbandonmentStore.insert(connection, engine, decision);
                connection.commit();
                LOGGER.log(Level.FINE, "无心跳会议弃场决定已落 kind=NEVER_STARTED");
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("abandoned decision write failed", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("abandoned decision database unavailable", failure);
        }
    }

    /**
     * B 类决定事务：锁读、复核心跳陈旧与 Stop 用量，冻结结算时长，插入决定，
     * 并把会议推进到 {@code STOPPING}（终态仍要等结算成功后的收尾事务写入）。
     */
    private boolean writeAbandonedDecision(String meetingId, Instant now) {
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
                if (JdbcMeetingAbandonmentStore.exists(
                        connection, engine, meetingId, false)) {
                    connection.rollback();
                    return false;
                }
                HeartbeatFacts heartbeat = lockHeartbeat(connection, meetingId);
                if (heartbeat == null) {
                    connection.rollback();
                    return false;
                }
                if (!now.isAfter(heartbeat.lastActiveAt().plus(config.heartbeatIdleWindow()))) {
                    connection.rollback();
                    return false;
                }
                if (stopUsageSubmitted(connection, meetingId)) {
                    // 真实 Stop 已冻结采音用量，本场不是弃场；交给正常收尾链。
                    connection.rollback();
                    return false;
                }
                long usageMs = abandonedDurationMs(
                        heartbeat.startedAt(), heartbeat.lastActiveAt(),
                        config.maxMeetingDurationMs());
                boolean noReservation = snapshot.usageReservationId() == null;
                JdbcMeetingAbandonmentStore.Decision decision =
                        new JdbcMeetingAbandonmentStore.Decision(
                                meetingId,
                                JdbcMeetingAbandonmentStore.Kind.ABANDONED,
                                snapshot.deploymentScope(), snapshot.subjectScope(),
                                JdbcMeetingAbandonmentStore.targetState(
                                        JdbcMeetingAbandonmentStore.Kind.ABANDONED),
                                now, heartbeat.startedAt(), heartbeat.lastActiveAt(), usageMs,
                                snapshot.usageReservationId(),
                                noReservation
                                        ? JdbcMeetingAbandonmentStore.Settlement.SETTLED
                                        : JdbcMeetingAbandonmentStore.Settlement.DECIDED,
                                noReservation ? now : null);
                JdbcMeetingAbandonmentStore.insert(connection, engine, decision);
                // STOPPING 是持久的"不可再活跃"标记；正常 Stop 的收尾入口会因决定行让位。
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(snapshot);
                aggregate.apply(MeetingEvent.STOP_REQUESTED,
                        now.isBefore(snapshot.updatedAt()) ? snapshot.updatedAt() : now);
                JdbcMeetingRepository.updateOwnedExisting(connection, engine, locked,
                        aggregate.snapshot());
                connection.commit();
                LOGGER.log(Level.FINE, "弃场会议决定已落 kind=ABANDONED durationMs={0}",
                        usageMs);
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("abandoned decision write failed", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("abandoned decision database unavailable", failure);
        }
    }

    /** A 类收口：结算成功后按领域状态机取消，并在同一事务删除决定。 */
    private Outcome cancelDecided(String meetingId, Instant now) {
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
                JdbcMeetingAbandonmentStore.Decision decision =
                        JdbcMeetingAbandonmentStore.find(connection, engine, meetingId, true)
                                .orElse(null);
                if (decision == null
                        || decision.kind() != JdbcMeetingAbandonmentStore.Kind.NEVER_STARTED) {
                    connection.rollback();
                    return Outcome.SKIPPED;
                }
                MeetingAggregate.MeetingSnapshot snapshot = locked.snapshot();
                if (snapshot.state() == MeetingState.CANCELLED) {
                    JdbcMeetingAbandonmentStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return Outcome.CANCELLED;
                }
                if (!isCancellable(snapshot.state())) {
                    // 防御分支：其它路径已把会议推进到不能取消的状态时清掉决定，避免永久重扫。
                    LOGGER.log(Level.WARNING,
                            "已落决定会议状态不可取消，清除决定 state={0}",
                            snapshot.state());
                    JdbcMeetingAbandonmentStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return Outcome.SKIPPED;
                }
                Instant cancelledAt = now.isBefore(snapshot.updatedAt())
                        ? snapshot.updatedAt() : now;
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(snapshot);
                aggregate.apply(MeetingEvent.CANCEL, cancelledAt);
                if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                        snapshot.subjectScope())) {
                    // 历史兼容会议没有 Grant/预占，走 legacy CAS；owned 更新会拒绝它。
                    JdbcMeetingRepository.updateLegacyExisting(connection, engine, locked,
                            aggregate.snapshot());
                } else {
                    JdbcMeetingRepository.updateOwnedExisting(connection, engine, locked,
                            aggregate.snapshot());
                }
                JdbcMeetingAbandonmentStore.delete(connection, engine, meetingId);
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

    /** B 类收口：决定已结算后委托唯一 Finalizer 写结果与终态（含删除决定）。 */
    private Outcome finalizeDecided(String meetingId,
            JdbcMeetingAbandonmentStore.Decision decision) {
        if (decision.targetState() != MeetingState.READY_WITH_WARNINGS) {
            throw new IllegalStateException("abandoned decision target is inconsistent");
        }
        try {
            return finalizer.finalizeAbandoned(meetingId)
                    ? Outcome.FINALIZED : Outcome.SKIPPED;
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "弃场会议收尾失败，下个周期继续 type={0}",
                    failure.getClass().getSimpleName());
            return Outcome.DEFERRED;
        }
    }

    private JdbcMeetingAbandonmentStore.Decision readDecision(String meetingId) {
        try (Connection connection = database.dataSource().getConnection()) {
            return JdbcMeetingAbandonmentStore.find(connection, engine, meetingId, false)
                    .orElse(null);
        } catch (SQLException failure) {
            throw new IllegalStateException("abandoned decision read unavailable", failure);
        }
    }

    /** 未完成决定：待结算，或已结算但目标终态/结果尚未写出。 */
    private List<String> findPendingDecisions() {
        String sql = "SELECT a.meeting_id FROM "
                + database.table(JdbcDatabaseTable.MEETING_ABANDONMENT) + " a"
                + " JOIN " + database.table(JdbcDatabaseTable.MEETING) + " m"
                + " ON m.meeting_id = a.meeting_id"
                + " WHERE a.deployment_scope = ? AND ("
                + " a.settlement_state = '" + JdbcMeetingAbandonmentStore.Settlement.DECIDED.name() + "'"
                + " OR (a.target_state = '" + MeetingState.CANCELLED.name()
                + "' AND m.state <> '" + MeetingState.CANCELLED.name() + "')"
                + " OR (a.target_state = '" + MeetingState.READY_WITH_WARNINGS.name()
                + "' AND NOT EXISTS (SELECT 1 FROM " + database.table(
                        JdbcDatabaseTable.MEETING_CLIENT_RESULT) + " r"
                + " WHERE r.meeting_id = a.meeting_id)))"
                + " ORDER BY a.decided_at LIMIT ?";
        List<String> ids = new ArrayList<>();
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            statement.setInt(2, config.batchLimit());
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

    /** A 类候选：无心跳行、停在开始前、创建时间已超过窗口，且没有已落决定或冻结用量。 */
    private List<String> findNeverStarted(Instant threshold) {
        String sql = "SELECT m.meeting_id FROM "
                + database.table(JdbcDatabaseTable.MEETING) + " m"
                + " WHERE m.deployment_scope = ? AND m.state IN ('CREATED','STARTING')"
                + " AND m.created_at < ?"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_HEARTBEAT) + " h"
                + " WHERE h.meeting_id = m.meeting_id)"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_CLIENT_INPUT) + " ci"
                + " WHERE ci.meeting_id = m.meeting_id AND ci.audio_duration_ms IS NOT NULL)"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_ABANDONMENT) + " a"
                + " WHERE a.meeting_id = m.meeting_id)"
                + " ORDER BY m.created_at LIMIT ?";
        return queryMeetingIds(sql, threshold, config.batchLimit());
    }

    /**
     * B 类候选：有心跳、最后活跃已严格早于窗口，且没有 Stop 冻结的采音用量
     * 或已落决定。Stop 用量存在时那场不是弃场，交给正常收尾链或人工处理。
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
                + " AND NOT EXISTS (SELECT 1 FROM "
                + database.table(JdbcDatabaseTable.MEETING_ABANDONMENT) + " a"
                + " WHERE a.meeting_id = m.meeting_id)"
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

    /** 读取并锁定本场心跳真实开始与最后活跃；无心跳行返回 null。 */
    private HeartbeatFacts lockHeartbeat(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT started_at, last_active_at FROM "
                        + database.table(JdbcDatabaseTable.MEETING_HEARTBEAT)
                        + " WHERE meeting_id=? FOR UPDATE")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Instant startedAt = JdbcValues.instant(rows, "started_at");
                Instant lastActiveAt = JdbcValues.instant(rows, "last_active_at");
                // 旧版非单调更新可能留下 last_active_at < started_at。决定构造拒绝
                // 逆序，若直接使用坏数据会让本场每轮 deferred、会议与预占永久卡死；
                // 这里按 max 归一，历史坏行也能在本轮正常收口。
                if (lastActiveAt.isBefore(startedAt)) {
                    lastActiveAt = startedAt;
                }
                return new HeartbeatFacts(startedAt, lastActiveAt);
            }
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

    /** 已提交 Stop 采音用量时优先级高于心跳推导值；此查询在会议行锁内执行。 */
    private boolean stopUsageSubmitted(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + database.table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)
                        + " WHERE meeting_id=? AND audio_duration_ms IS NOT NULL")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 只有领域状态机允许 CANCEL 的活跃态才取消；终态/收尾中交由防御分支清理。 */
    private static boolean isCancellable(MeetingState state) {
        return state == MeetingState.CREATED
                || state == MeetingState.AUTHORIZED
                || state == MeetingState.STARTING
                || state == MeetingState.RUNNING
                || state == MeetingState.STOPPING;
    }

    /** 弃场时长取最后一次活跃减真实开始，并按部署上限截断；不得为负。 */
    static long abandonedDurationMs(Instant startedAt, Instant lastActiveAt,
            long maxMeetingDurationMs) {
        long elapsed = Duration.between(startedAt, lastActiveAt).toMillis();
        return Math.max(0, Math.min(elapsed, maxMeetingDurationMs));
    }

    /** 心跳行的真实开始与最后活跃；两列都由服务端时钟写入。 */
    private record HeartbeatFacts(Instant startedAt, Instant lastActiveAt) {
        HeartbeatFacts {
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(lastActiveAt, "lastActiveAt");
        }
    }

    private enum Outcome {
        CANCELLED,
        FINALIZED,
        DEFERRED,
        SKIPPED
    }

    private static final class Counts {
        private int cancelled;
        private int finalized;
        private int deferred;

        private void count(Outcome outcome) {
            switch (outcome) {
                case CANCELLED -> cancelled++;
                case FINALIZED -> finalized++;
                case DEFERRED -> deferred++;
                case SKIPPED -> { }
            }
        }
    }

    /** 单次扫描结果；deferred 表示决定/结算/收尾失败、留待下一轮。 */
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
