package com.czur.reference.application.bootstrap;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.artifact.s3.S3ArtifactStore;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.finalization.MeetingInputReconciliationScan;
import com.czur.reference.application.finalization.MockFinalizationWorker;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.persistence.jdbc.JdbcFinalizationCompletionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcReferenceDevEntitlementProvider;
import com.czur.reference.application.persistence.jdbc.JdbcWorkflowStepStore;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.wss.MeetingRealtimeWssServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * P2 参考后端的单进程资源 owner：一份装配、一份数据库池、两个网络入口和一个调度线程。
 *
 * <p>HTTP/WSS 只负责协议输入；扫描器和 Mock Worker 在同一线程按固定间隔顺序运行。
 * 每轮异常只记录类别，下一轮继续，不创建第二套会议状态。关闭时先停止调度，再关闭
 * HTTP、WSS，最后关闭装配根及其 JDBC 池。文件 Artifact Store 的根目录由部署方挂载；
 * 此类不会自动执行数据库迁移，也不会启动后台管理、真实模型或独立 Worker 进程。</p>
 */
public final class ReferenceMeetingProcess implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ReferenceMeetingProcess.class.getName());
    private static final int SCAN_PAGE_SIZE = 32;
    private static final long MAX_ARTIFACT_BYTES = 1024 * 1024;
    private static final long TICK_SECONDS = 1;

    public static final String HTTP_BIND = "AI_MEETING_HTTP_BIND";
    public static final String WSS_BIND = "AI_MEETING_WSS_BIND";
    public static final String TLS_KEYSTORE_PATH = "AI_MEETING_TLS_KEYSTORE_PATH";
    public static final String TLS_KEYSTORE_PASSWORD = "AI_MEETING_TLS_KEYSTORE_PASSWORD";
    public static final String ARTIFACT_ROOT = "AI_MEETING_ARTIFACT_ROOT";
    public static final String ARTIFACT_STORE = "AI_MEETING_ARTIFACT_STORE";
    public static final String S3_ENDPOINT = "AI_MEETING_S3_ENDPOINT";
    public static final String S3_REGION = "AI_MEETING_S3_REGION";
    public static final String S3_BUCKET = "AI_MEETING_S3_BUCKET";
    public static final String S3_PREFIX = "AI_MEETING_S3_PREFIX";
    public static final String S3_ACCESS_KEY_ID = "AI_MEETING_S3_ACCESS_KEY_ID";
    public static final String S3_SECRET_ACCESS_KEY = "AI_MEETING_S3_SECRET_ACCESS_KEY";

    private final ReferenceMeetingHttpAssembly assembly;
    private final MeetingJdkHttpServer http;
    private final MeetingRealtimeWssServer wss;
    private final ScheduledExecutorService scheduler;
    private final ArtifactStore artifacts;
    private boolean closed;

    private ReferenceMeetingProcess(ReferenceMeetingHttpAssembly assembly,
            MeetingJdkHttpServer http, MeetingRealtimeWssServer wss,
            ScheduledExecutorService scheduler, ArtifactStore artifacts) {
        this.assembly = assembly;
        this.http = http;
        this.wss = wss;
        this.scheduler = scheduler;
        this.artifacts = artifacts;
    }

    /**
     * 从一份环境快照启动完整参考后端。所有配置在连接数据库前校验；缺失的数据库
     * 迁移仍由现有 readiness 明确拒绝。失败时关闭已经创建的监听器和数据库池。
     *
     * @param environment 部署配置，调用期间不可修改
     * @param clock HTTP、WSS、Worker 共用的服务端时钟
     * @return 已开始监听并启动固定周期调度的进程资源 owner
     */
    public static ReferenceMeetingProcess start(Map<String, String> environment, Clock clock)
            throws IOException, GeneralSecurityException, ArtifactStoreException,
            InterruptedException {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(clock, "clock");
        Map<String, String> values = Map.copyOf(environment);
        ReferenceMeetingDeploymentConfig config =
                ReferenceMeetingDeploymentConfig.fromEnvironment(values);
        InetSocketAddress httpBind = bind(values, HTTP_BIND);
        InetSocketAddress wssBind = bind(values, WSS_BIND);
        SSLContext tls = tls(values);
        ArtifactStore artifacts = createArtifactStore(values);

        ReferenceMeetingHttpAssembly assembly = null;
        MeetingJdkHttpServer http = null;
        MeetingRealtimeWssServer wss = null;
        ScheduledExecutorService scheduler = null;
        try {
            assembly = ReferenceMeetingHttpAssembly.start(config, clock, artifacts);
            var source = assembly.databaseRuntime().dataSource();
            MeetingInputReconciliationScan scan =
                    assembly.newInputReconciliationScan(SCAN_PAGE_SIZE);
            EntitlementIdGenerator ids = new EntitlementIdGenerator() {
                @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                @Override public String newReservationId() {
                    return "reservation_" + UUID.randomUUID();
                }
                @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
            };
            var entitlement = new JdbcReferenceDevEntitlementProvider(source,
                    new ReferenceDevEntitlementPolicy(config.entitlement(), clock, ids), clock);
            var worker = new MockFinalizationWorker(new JdbcWorkflowStepStore(source),
                    artifacts, entitlement, new JdbcFinalizationCompletionUnitOfWork(source),
                    new MockFinalizationWorker.Config("reference-" + UUID.randomUUID(), clock,
                            Duration.ofSeconds(30), Duration.ofSeconds(5),
                            Duration.ofSeconds(5), 10, 3));
            http = assembly.newJdkHttpServer(httpBind);
            wss = assembly.newRealtimeWssServer(wssBind, tls);
            wss.start();
            wss.awaitStarted(Duration.ofSeconds(5));
            // WSS 已实际绑定后才开放 Create，避免把尚不可连接的 URL 发给客户端。
            http.start();
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "reference-meeting-worker");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(() -> tick(scan, worker), 0,
                    TICK_SECONDS, TimeUnit.SECONDS);
            LOGGER.log(Level.INFO, "参考后端单进程已启动 httpPort="
                    + http.address().getPort() + " wssPort=" + wss.getPort());
            return new ReferenceMeetingProcess(assembly, http, wss, scheduler, artifacts);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            if (scheduler != null) scheduler.shutdownNow();
            if (wss != null) closeSuppressing(wss, failure);
            if (http != null) closeSuppressing(http, failure);
            if (assembly != null) closeSuppressing(assembly, failure);
            if (artifacts instanceof S3ArtifactStore s3) closeSuppressing(s3, failure);
            throw failure;
        }
    }

    /** 已启动 HTTP 监听地址，供部署探针和定向测试使用；不转移监听器所有权。 */
    public InetSocketAddress httpAddress() { return http.address(); }

    /** 已启动 WSS 端口，供部署探针和定向测试使用。 */
    public int wssPort() { return wss.getPort(); }

    /** 停止新周期任务，关闭网络入口，再释放唯一数据库池；重复调用安全。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                LOGGER.log(Level.WARNING, "参考后端 Worker 关闭超时");
            }
        } catch (InterruptedException interrupted) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            http.close();
        } finally {
            try {
                wss.close();
            } finally {
                try {
                    assembly.close();
                } finally {
                    if (artifacts instanceof S3ArtifactStore s3) s3.close();
                }
            }
        }
        LOGGER.log(Level.INFO, "参考后端单进程已关闭");
    }

    /**
     * 从部署配置选用唯一 Artifact Store。文件模式保持已有参考文件数据布局；
     * S3 模式把对象连接材料限制在基础设施适配器，不改变 HTTP/Worker 协议。
     */
    static ArtifactStore createArtifactStore(Map<String, String> values)
            throws ArtifactStoreException {
        String mode = values.getOrDefault(ARTIFACT_STORE, "file").trim();
        return switch (mode) {
            case "file" -> new PersistentFileSystemMockArtifactStore(
                    Path.of(required(values, ARTIFACT_ROOT)), MAX_ARTIFACT_BYTES);
            case "s3" -> {
                String rawEndpoint = values.get(S3_ENDPOINT);
                URI endpoint = rawEndpoint == null || rawEndpoint.isBlank()
                        ? null : URI.create(rawEndpoint.trim());
                yield new S3ArtifactStore(endpoint, required(values, S3_REGION),
                        required(values, S3_BUCKET), values.getOrDefault(S3_PREFIX, "artifacts/"),
                        required(values, S3_ACCESS_KEY_ID),
                        required(values, S3_SECRET_ACCESS_KEY), MAX_ARTIFACT_BYTES);
            }
            default -> throw new IllegalArgumentException(
                    ARTIFACT_STORE + " must be file or s3");
        };
    }

    private static void tick(MeetingInputReconciliationScan scan,
            MockFinalizationWorker worker) {
        try {
            scan.runOnce();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "待收口会议扫描失败 reason={0}",
                    failure.getClass().getSimpleName());
        }
        try {
            worker.runOnce();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Mock 最终收口 Worker 本轮失败 reason={0}",
                    failure.getClass().getSimpleName());
        }
    }

    private static InetSocketAddress bind(Map<String, String> values, String key) {
        String value = required(values, key).trim();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException(key + " must be host:port");
        }
        String host = value.substring(0, separator);
        try {
            int port = Integer.parseInt(value.substring(separator + 1));
            if (port < 0 || port > 65535) throw new NumberFormatException();
            return new InetSocketAddress(host, port);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be host:port");
        }
    }

    private static SSLContext tls(Map<String, String> values)
            throws IOException, GeneralSecurityException {
        Path path = Path.of(required(values, TLS_KEYSTORE_PATH));
        char[] password = required(values, TLS_KEYSTORE_PASSWORD).toCharArray();
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(path)) {
                keys.load(input, password);
            }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            return context;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static void closeSuppressing(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
