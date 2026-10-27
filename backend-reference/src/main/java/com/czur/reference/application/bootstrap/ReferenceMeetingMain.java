package com.czur.reference.application.bootstrap;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 参考后端可执行入口：读取环境配置，启动一个进程并在终止信号时有序关闭。 */
public final class ReferenceMeetingMain {
    private static final Logger LOGGER = Logger.getLogger(ReferenceMeetingMain.class.getName());

    private ReferenceMeetingMain() { }

    public static void main(String[] args) {
        // 数据库时间戳只有微秒精度；毫秒刻度避免业务事实发生纳秒截断。
        try (ReferenceMeetingProcess process =
                     ReferenceMeetingProcess.start(System.getenv(),
                             Clock.tickMillis(ZoneOffset.UTC))) {
            CountDownLatch stopping = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    process.close();
                } finally {
                    stopping.countDown();
                }
            }, "reference-meeting-shutdown"));
            stopping.await();
        } catch (Exception failure) {
            LOGGER.log(Level.SEVERE, "参考后端启动或运行失败 reason={0}",
                    failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
