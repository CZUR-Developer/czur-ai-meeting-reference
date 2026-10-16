package com.czur.reference.application.bootstrap;

import com.czur.reference.application.artifact.s3.S3ArtifactStore;
import com.czur.reference.application.entitlement.FixedEntitlementPolicy;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.http.ReferenceDevSessionLoginEndpoint;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStoreException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 参考 App 的本机客户端直连模型进程。
 *
 * <p>这个进程只启动 HTTP 业务入口：Android 通过 {@code :modelSession} 取得
 * 临时模型材料后直连模型，音频不会经过本进程，也不会启动旧 WSS 音频链、
 * 旧音频 Worker 或第二套会议状态。身份、权益、模型 Adapter、总结发行器和
 * Artifact Store 仍由同一参考装配注入，因而本机链路与合作伙伴替换边界保持一致。</p>
 *
 * <p>数据库必须先由部署方用唯一 V001 初始化；启动只做同一 JDBC Context 的
 * 只读 readiness 检查。关闭顺序是先停 HTTP，再关装配和其连接池，最后释放
 * 由本进程创建的对象存储适配器。</p>
 */
public final class ReferenceClientMeetingProcess implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceClientMeetingProcess.class.getName());

    private final ReferenceMeetingHttpAssembly assembly;
    private final MeetingJdkHttpServer http;
    private final ArtifactStore artifacts;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReferenceClientMeetingProcess(ReferenceMeetingHttpAssembly assembly,
            MeetingJdkHttpServer http, ArtifactStore artifacts) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.http = Objects.requireNonNull(http, "http");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
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
        try {
            artifacts = ReferenceMeetingProcess.createArtifactStore(values);
            var identity = new ReferenceDevIdentityProvider(config.identity(), clock);
            var policy = new FixedEntitlementPolicy(config.entitlement(), clock,
                    new ReferenceMeetingHttpAssembly.RandomEntitlementIds());
            ModelSessionIssuer issuer = ReferenceMeetingHttpAssembly.defaultIssuer(config, clock);
            if (issuer == null) {
                throw new IllegalArgumentException(
                        "客户端直连参考服务必须配置可用的模型 Adapter");
            }
            var login = config.login().map(value -> new ReferenceDevSessionLoginEndpoint(
                    value, new HttpErrorMapper(), clock)).orElse(null);
            assembly = ReferenceMeetingHttpAssembly.startClientModel(config, clock, artifacts,
                    identity, policy, issuer, java.util.function.UnaryOperator.identity(),
                    login, null);
            http = assembly.newJdkHttpServer(httpBind);
            http.start();
            LOGGER.log(Level.INFO, "参考客户端直连后端已启动 httpPort={0} modelAdapter={1}",
                    new Object[]{http.address().getPort(), issuer.getClass().getSimpleName()});
            return new ReferenceClientMeetingProcess(assembly, http, artifacts);
        } catch (IOException | RuntimeException | Error failure) {
            if (http != null) closeSuppressing(http, failure);
            if (assembly != null) closeSuppressing(assembly, failure);
            closeArtifacts(artifacts, failure);
            throw failure;
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
