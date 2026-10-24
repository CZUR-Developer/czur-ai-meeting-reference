package com.czur.reference.application.bootstrap;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 本机局域网参考入口：只启动 HTTP 业务服务，模型音频由 Android 直连 Provider。 */
public final class ReferenceClientMeetingMain {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceClientMeetingMain.class.getName());

    private ReferenceClientMeetingMain() { }

    public static void main(String[] args) {
        try (ReferenceClientMeetingProcess process =
                     ReferenceClientMeetingProcess.start(System.getenv(),
                             Clock.tickMillis(ZoneOffset.UTC))) {
            CountDownLatch stopping = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    process.close();
                } finally {
                    stopping.countDown();
                }
            }, "reference-client-meeting-shutdown"));
            stopping.await();
        } catch (Exception failure) {
            LOGGER.log(Level.SEVERE, "参考客户端直连后端启动或运行失败", failure);
            System.exit(1);
        }
    }
}
