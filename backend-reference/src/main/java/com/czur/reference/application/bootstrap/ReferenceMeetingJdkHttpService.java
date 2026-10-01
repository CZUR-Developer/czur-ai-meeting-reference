package com.czur.reference.application.bootstrap;

import com.czur.reference.application.http.MeetingJdkHttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单一参考 HTTP 部署实例的外层资源 owner。
 *
 * <p>先用同一装配根创建监听器与只读健康探针，再启动监听。关闭时先让网络入口
 * 拒绝新业务并排空执行器已受理任务，停止监听后才关闭 HTTP runtime 与唯一
 * JDBC 池。超时返回非洁净结果；若资源关闭仍在进行，部署方必须终止进程并按
 * 持久事实恢复，不能把后台关闭线程视为已回收。本类不创建进程 main、Worker
 * 或新的数据库连接池。</p>
 */
public final class ReferenceMeetingJdkHttpService implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceMeetingJdkHttpService.class.getName());
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final MeetingJdkHttpServer server;
    private final AutoCloseable resources;
    private CloseResult closeResult;

    ReferenceMeetingJdkHttpService(
            MeetingJdkHttpServer server, AutoCloseable resources) {
        this.server = Objects.requireNonNull(server, "server");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /**
     * 装配并启动单一网络实例；任何监听绑定或启动失败均先关闭网络，再关闭
     * 装配根及其唯一 JDBC 池。调用方必须使用受信网络或 loopback 地址。
     *
     * @throws IOException 监听地址绑定失败；数据库装配已关闭
     * @throws RuntimeException 配置、数据库 readiness 或网络启动失败
     */
    public static ReferenceMeetingJdkHttpService start(
            Map<String, String> environment, Clock clock, InetSocketAddress address)
            throws IOException {
        ReferenceMeetingHttpAssembly assembly =
                ReferenceMeetingHttpAssembly.start(environment, clock);
        MeetingJdkHttpServer server = null;
        try {
            server = assembly.newJdkHttpServer(address);
            server.start();
            return new ReferenceMeetingJdkHttpService(server, assembly);
        } catch (IOException | RuntimeException | Error failure) {
            if (server != null) {
                try {
                    server.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                assembly.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            LOGGER.log(Level.WARNING,
                    "reference meeting HTTP 网络启动失败 reason={0}",
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** 返回正在监听的随机或指定地址，不转移监听器所有权。 */
    public InetSocketAddress address() { return server.address(); }

    /**
     * 用单一截止时间关闭网络与装配资源。正常结果要求所有已受理请求完成、
     * worker 退出且资源关闭；超时结果保留未完成请求和仍存活线程的快照。
     * 重复调用返回首次结果，不重复关闭资源。
     */
    public synchronized CloseResult shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("HTTP close timeout must be 0..5 minutes");
        }
        if (closeResult != null) {
            return closeResult;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        MeetingJdkHttpServer.DrainResult network =
                server.shutdown(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
        Thread cleanup = new Thread(() -> {
            try {
                resources.close();
            } catch (Throwable failure) {
                cleanupFailure.set(failure);
            } finally {
                closed.countDown();
            }
        }, "meeting-http-resource-close");
        cleanup.setDaemon(false);
        cleanup.start();
        boolean cleanupCompleted = false;
        boolean interrupted = false;
        try {
            cleanupCompleted = closed.await(Math.max(0, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
        }
        boolean resourcesClosed = cleanupCompleted && cleanupFailure.get() == null;
        boolean clean = network.clean() && resourcesClosed && !interrupted;
        closeResult = new CloseResult(clean, network, resourcesClosed,
                !cleanupCompleted, cleanupFailure.get() != null);
        LOGGER.log(clean ? Level.INFO : Level.WARNING,
                "reference meeting HTTP 关闭 clean={0} resourcesClosed={1} "
                        + "cleanupPending={2} cleanupFailed={3}",
                new Object[]{clean, resourcesClosed, !cleanupCompleted,
                        cleanupFailure.get() != null});
        if (cleanupFailure.get() != null) {
            LOGGER.log(Level.SEVERE,
                    "reference meeting HTTP 资源关闭异常 reason=RESOURCE_CLOSE_FAILED "
                            + "exception={0}",
                    cleanupFailure.get().getClass().getSimpleName());
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return closeResult;
    }

    /** 默认五秒截止时间；调用方需审阅 {@link #shutdown(Duration)} 的非洁净结果。 */
    @Override
    public void close() {
        shutdown(DEFAULT_CLOSE_TIMEOUT);
    }

    /** 首次关闭时的证据快照；资源关闭抛异常时不报告已关闭或仍在关闭。 */
    public record CloseResult(
            boolean clean,
            MeetingJdkHttpServer.DrainResult network,
            boolean resourcesClosed,
            boolean cleanupPending,
            boolean cleanupFailed) {
    }
}
