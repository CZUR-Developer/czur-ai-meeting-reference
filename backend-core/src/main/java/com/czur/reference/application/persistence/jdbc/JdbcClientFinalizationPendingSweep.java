package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ClientMeetingResultFinalizer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 重试“Stop 已受理或冻结用量超时、但结果尚未收口”的会议。
 *
 * <p>待办由 Stop 事务或冻结用量超时决策事务持久写入。本扫描器只挑选已超过
 * 宽限窗口的待办，调用与同步 Stop 完全相同的 {@link ClientMeetingResultFinalizer}；
 * 后者按待办原因选择普通 READY 或按结果能力选择 READY／READY_WITH_WARNINGS，
 * 并在同一事务内删除待办。结算键与正常 Stop 相同，重复扫描只重放既有结果，
 * 不会二次扣减或重复发布产物。</p>
 *
 * <p>本类不创建线程、不打开第二套连接池；调用方负责串行周期调用，并在装配根
 * 关闭前停止调用。</p>
 */
public final class JdbcClientFinalizationPendingSweep {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcClientFinalizationPendingSweep.class.getName());

    private final JdbcDatabaseContext database;
    private final JdbcDatabaseEngine engine;
    private final String deploymentScope;
    private final Clock clock;
    private final ClientMeetingResultFinalizer finalizer;
    private final Config config;

    public JdbcClientFinalizationPendingSweep(JdbcDatabaseContext database,
            String deploymentScope, Clock clock, ClientMeetingResultFinalizer finalizer,
            Config config) {
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
     * 扫描一轮已到期的待办。
     *
     * <p>成功收口后待办已由 Finalizer 在其事务内删除；这里再删一次只作为防御，
     * 不会影响结果。单场失败只计 deferred，下一周期继续。</p>
     */
    public Result runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<JdbcClientFinalizationPendingStore.Pending> due =
                JdbcClientFinalizationPendingStore.findDue(database.dataSource(), engine,
                        deploymentScope, now.minus(config.retryGrace()), config.batchLimit());
        int finalized = 0;
        int deferred = 0;
        for (var pending : due) {
            try {
                if (finalizer.finalizePendingClient(pending.meetingId())) {
                    finalized++;
                    clearSafely(pending.meetingId());
                } else {
                    deferred++;
                    recordAttemptSafely(pending.meetingId(), now);
                }
            } catch (RuntimeException failure) {
                deferred++;
                LOGGER.log(Level.WARNING,
                        "待收尾会议重试失败，下个周期继续 reason={0}",
                        failure.getClass().getSimpleName());
                recordAttemptSafely(pending.meetingId(), now);
            }
        }
        return new Result(finalized, deferred);
    }

    private void clearSafely(String meetingId) {
        try {
            JdbcClientFinalizationPendingStore.clear(database.dataSource(), engine, meetingId);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "待收尾会议已完成但待办删除暂未完成 reason={0}",
                    failure.getClass().getSimpleName());
        }
    }

    private void recordAttemptSafely(String meetingId, Instant attemptedAt) {
        try {
            JdbcClientFinalizationPendingStore.recordAttempt(database.dataSource(), engine,
                    meetingId, attemptedAt);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "待收尾会议尝试记录暂未完成 reason={0}",
                    failure.getClass().getSimpleName());
        }
    }

    /** 单轮结果；deferred 表示本轮的失败或尚未满足输入事实。 */
    public record Result(int finalizedMeetings, int deferredMeetings) { }

    /**
     * 扫描配置；默认宽限 60 秒，避免同步 Stop 刚失败时立刻重复争用同一会议。
     * 单轮最多处理 {@code batchLimit} 场。
     */
    public record Config(Duration retryGrace, int batchLimit) {
        public Config {
            Objects.requireNonNull(retryGrace, "retryGrace");
            if (retryGrace.isNegative()) {
                throw new IllegalArgumentException("retryGrace must not be negative");
            }
            if (batchLimit <= 0) {
                throw new IllegalArgumentException("batchLimit must be positive");
            }
        }

        public static Config defaults() {
            return new Config(Duration.ofSeconds(60), 50);
        }
    }
}
