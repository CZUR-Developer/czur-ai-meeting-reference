package com.czur.reference.application.bootstrap;

import com.czur.reference.application.artifact.s3.S3ArtifactStore;
import com.czur.reference.application.entitlement.FixedEntitlementPolicy;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.http.ReferenceDevSessionLoginEndpoint;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.model.ModelSessionIssuerRegistry;
import com.czur.reference.application.model.SampleJsonTranslationSessionIssuer;
import com.czur.reference.application.model.TextTranslationModelSessionIssuer;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.speaker.ReferenceSpeakerIdentityConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import com.czur.reference.application.entitlement.ExpiredReservationCleanupWorker;
import com.czur.reference.application.persistence.jdbc.JdbcAbandonedMeetingSweeper;
import com.czur.reference.application.persistence.jdbc.JdbcClientFinalizationPendingSweep;
import com.czur.reference.application.persistence.jdbc.JdbcFrozenUsageTimeoutSweeper;
import com.czur.reference.application.persistence.jdbc.JdbcClientModelWorkers;
import com.czur.reference.application.port.entitlement.LeaseAwareReservationCanceller;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 参考 App 的本机客户端直连模型进程。
 *
 * <p>这个进程只启动 HTTP 业务入口：Android 通过 {@code :modelSession} 取得
 * 临时模型材料后直连模型，实时音频不经本进程转发；会后可上传封口录音，
 * 用于统一结果回放和可选人物处理。不启动旧 WSS 音频链、旧音频 Worker 或
 * 第二套会议状态。身份、权益、模型 Adapter、总结发行器和
 * Artifact Store 仍由同一参考装配注入，因而本机链路与合作伙伴替换边界保持一致。</p>
 *
 * <p>数据库必须先由部署方用唯一 V001 初始化；启动只做同一 JDBC Context 的
 * 只读 readiness 检查。关闭顺序是先停 HTTP，再关装配和其连接池，最后释放
 * 由本进程创建的对象存储适配器。</p>
 */
public final class ReferenceClientMeetingProcess implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceClientMeetingProcess.class.getName());

    /** 过期预占清理与弃场扫描周期；重复执行只会重新领取同一批记录，结果幂等。 */
    private static final long TICK_SECONDS = 30;

    private final ReferenceMeetingHttpAssembly assembly;
    private final MeetingJdkHttpServer http;
    private final ArtifactStore artifacts;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReferenceClientMeetingProcess(ReferenceMeetingHttpAssembly assembly,
            MeetingJdkHttpServer http, ArtifactStore artifacts,
            ScheduledExecutorService scheduler) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.http = Objects.requireNonNull(http, "http");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * 从同一份环境快照启动 HTTP-only 客户端会议服务。
     *
     * <p>参考身份必须显式启用，模型 Adapter 必须由配置提供；不在这里偷偷
     * 回退到 Mock 或 CZUR 线上服务。Artifact Store 复用既有参考选择器，
     * 因此文件模式可直接用于本机结果回看。</p>
     */
    public static ReferenceClientMeetingProcess start(Map<String, String> environment,
            Clock clock) throws IOException, ArtifactStoreException {
        return start(environment, clock, null);
    }

    /**
     * 允许宿主注入模型发行器注册表：注册自己的 Adapter ID 后即可用配置选择，
     * 不必修改参考实现的配置校验或装配分支。传 null 时只用内置实现。
     */
    public static ReferenceClientMeetingProcess start(Map<String, String> environment,
            Clock clock, ModelSessionIssuerRegistry registry)
            throws IOException, ArtifactStoreException {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(clock, "clock");
        Map<String, String> values = Map.copyOf(environment);
        ReferenceMeetingDeploymentConfig config =
                ReferenceMeetingDeploymentConfig.fromEnvironment(values);
        InetSocketAddress httpBind = ReferenceMeetingProcess.bind(
                values, ReferenceMeetingProcess.HTTP_BIND);
        ArtifactStore artifacts = null;
        ReferenceMeetingHttpAssembly assembly = null;
        MeetingJdkHttpServer http = null;
        ScheduledExecutorService scheduler = null;
        try {
            artifacts = ReferenceMeetingProcess.createArtifactStore(values);
            var identity = new ReferenceDevIdentityProvider(config.identity(), clock);
            var policy = new FixedEntitlementPolicy(config.entitlement(), clock,
                    new ReferenceMeetingHttpAssembly.RandomEntitlementIds());
            ModelSessionIssuerRegistry issuers = registry != null
                    ? registry : ReferenceMeetingHttpAssembly.defaultRegistry(config, clock);
            ModelSessionIssuer issuer = issuers.contains(config.modelAdapterId())
                    ? issuers.create(config.modelAdapterId(),
                            new ModelSessionIssuerRegistry.Context(values, clock))
                    : null;
            if (issuer == null) {
                throw new IllegalArgumentException(
                        "客户端直连参考服务必须配置可用的模型 Adapter: "
                                + config.modelAdapterId() + " 未注册；已注册: " + issuers.adapterIds());
            }
            var textTranslation = SampleJsonTranslationSessionIssuer.fromEnvironment(values, clock);
            if (textTranslation != null) {
                issuer = new TextTranslationModelSessionIssuer(issuer, textTranslation);
            }
            var login = config.login().map(value -> new ReferenceDevSessionLoginEndpoint(
                    value, new HttpErrorMapper(), clock)).orElse(null);
            assembly = ReferenceMeetingHttpAssembly.startClientModel(config, clock, artifacts,
                    identity, policy, issuer, java.util.function.UnaryOperator.identity(),
                    login, null, ReferenceSpeakerIdentityConfig.factory(values, clock));
            http = assembly.newJdkHttpServer(httpBind);
            http.start();
            // 进程内定时执行后台清理：部署方不必额外配 cron 或 sidecar。
            // 同一批过期预占被重复领取时由 lease 与幂等键兜底，不会重复扣减。
            ExpiredReservationCleanupWorker cleanup = null;
            if (assembly.entitlementProvider() instanceof LeaseAwareReservationCanceller canceller) {
                cleanup = JdbcClientModelWorkers.expiredReservationCleanupWorker(
                        assembly.databaseContext(), canceller,
                        new ReferenceMeetingHttpAssembly.RandomEntitlementIds(),
                        ExpiredReservationCleanupWorker.Config.defaults(
                                config.deploymentScope(),
                                "reference-client-cleanup-" + UUID.randomUUID()));
            } else {
                LOGGER.log(Level.INFO, "权益端口不支持 lease-aware cancel，跳过过期预占清理");
            }
            final ExpiredReservationCleanupWorker cleanupWorker = cleanup;
            // 过期预占清理只处理尚未绑定会议的记录；会议室里还留着两类卡住的会议：
            // 从未出现心跳的启动失败（零用量结算后取消）与已开始但心跳中断的弃场
            // （按时长结算后收成 READY_WITH_WARNINGS）。同一扫描器复用本装配的权益
            // Provider 和唯一 Finalizer，窗口由部署心跳间隔推导。
            final JdbcAbandonedMeetingSweeper abandonedSweeper = new JdbcAbandonedMeetingSweeper(
                    assembly.databaseContext(), config.deploymentScope(), clock,
                    assembly.entitlementProvider(), assembly.abandonedMeetingFinalizer(),
                    JdbcAbandonedMeetingSweeper.Config.defaults(config.heartbeatIntervalMs(),
                            config.runtimeLimits().maxMeetingDurationMs()));
            // Stop 受理与结果收口分离：同步 Finalizer 失败时由待办扫描按宽限窗口
            // 重试；同一 Finalizer 也负责“冻结用量后超时未 Stop”的会议。
            final JdbcClientFinalizationPendingSweep pendingSweep =
                    new JdbcClientFinalizationPendingSweep(assembly.databaseContext(),
                            config.deploymentScope(), clock,
                            assembly.clientResultFinalizer(),
                            JdbcClientFinalizationPendingSweep.Config.defaults());
            final JdbcFrozenUsageTimeoutSweeper frozenUsageSweeper =
                    new JdbcFrozenUsageTimeoutSweeper(assembly.databaseContext(),
                            config.deploymentScope(), clock,
                            assembly.clientResultFinalizer(),
                            JdbcFrozenUsageTimeoutSweeper.Config.defaults(
                                    config.heartbeatIntervalMs()));
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "reference-client-meeting-worker");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(() -> tick(cleanupWorker, pendingSweep,
                            abandonedSweeper, frozenUsageSweeper),
                    0, TICK_SECONDS, TimeUnit.SECONDS);
            LOGGER.log(Level.INFO, "参考客户端直连后端已启动 httpPort={0} modelAdapter={1}",
                    new Object[]{http.address().getPort(), issuer.getClass().getSimpleName()});
            return new ReferenceClientMeetingProcess(assembly, http, artifacts, scheduler);
        } catch (IOException | RuntimeException | Error failure) {
            if (scheduler != null) scheduler.shutdownNow();
            if (http != null) closeSuppressing(http, failure);
            if (assembly != null) closeSuppressing(assembly, failure);
            closeArtifacts(artifacts, failure);
            throw failure;
        }
    }

    /** 一次清理；各任务各自兜异常，互不阻塞，失败只记录并等下个周期。 */
    static void tick(ExpiredReservationCleanupWorker cleanup,
            JdbcClientFinalizationPendingSweep pendingSweep,
            JdbcAbandonedMeetingSweeper sweeper,
            JdbcFrozenUsageTimeoutSweeper frozenUsageSweeper) {
        if (cleanup != null) {
            try {
                ExpiredReservationCleanupWorker.RunResult result = cleanup.runOnce();
                if (result.claimedCount() > 0) {
                    LOGGER.log(Level.INFO, "过期预占清理 claimed={0} items={1}",
                            new Object[]{result.claimedCount(), result.items().size()});
                }
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "过期预占清理失败，下个周期继续", failure);
            }
        }
        try {
            JdbcClientFinalizationPendingSweep.Result retried = pendingSweep.runOnce();
            if (retried.finalizedMeetings() > 0 || retried.deferredMeetings() > 0) {
                LOGGER.log(Level.INFO, "待收尾会议重试 finalized={0} deferred={1}",
                        new Object[]{retried.finalizedMeetings(), retried.deferredMeetings()});
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "待收尾会议重试失败，下个周期继续", failure);
        }
        try {
            JdbcAbandonedMeetingSweeper.Result swept = sweeper.runOnce();
            if (swept.cancelledMeetings() > 0 || swept.finalizedMeetings() > 0) {
                LOGGER.log(Level.INFO, "弃场会议收口 cancelled={0} finalized={1}",
                        new Object[]{swept.cancelledMeetings(), swept.finalizedMeetings()});
            }
            if (swept.deferredMeetings() > 0) {
                LOGGER.log(Level.FINE, "弃场会议本轮暂缓 deferred={0}",
                        swept.deferredMeetings());
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "弃场会议收口失败，下个周期继续", failure);
        }
        try {
            JdbcFrozenUsageTimeoutSweeper.Result recovered = frozenUsageSweeper.runOnce();
            if (recovered.finalizedMeetings() > 0 || recovered.deferredMeetings() > 0) {
                LOGGER.log(Level.INFO, "冻结用量超时会议收口 finalized={0} deferred={1}",
                        new Object[]{recovered.finalizedMeetings(),
                                recovered.deferredMeetings()});
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "冻结用量超时会议收口失败，下个周期继续", failure);
        }
    }

    /** 返回本机 HTTP 监听地址，供启动脚本和手工联调使用。 */
    public InetSocketAddress httpAddress() {
        return http.address();
    }

    /** 先关闭网络，再关闭唯一核心装配；重复关闭安全。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        try {
            http.close();
        } finally {
            try {
                assembly.close();
            } finally {
                closeArtifacts(artifacts, null);
            }
        }
        LOGGER.log(Level.INFO, "参考客户端直连后端已关闭");
    }

    private static void closeArtifacts(ArtifactStore artifacts, Throwable original) {
        if (!(artifacts instanceof S3ArtifactStore s3)) return;
        try {
            s3.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (original != null) original.addSuppressed(cleanupFailure);
            else throw cleanupFailure;
        }
    }

    private static void closeSuppressing(AutoCloseable resource, Throwable original) {
        try {
            resource.close();
        } catch (Exception | Error cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
