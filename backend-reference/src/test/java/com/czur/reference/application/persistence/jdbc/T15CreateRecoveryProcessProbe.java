package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.protocol.json.CreateMeetingResponseFactJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 真实子 JVM 的单次 Create 调用入口。只输出稳定结果码与会议标识；数据库地址、
 * 主体、幂等键、响应正文和实时令牌均不打印。父测试持有同一 advisory lock，
 * 待两个独立进程都进入数据库锁等待后才放行。
 */
public final class T15CreateRecoveryProcessProbe {
    private T15CreateRecoveryProcessProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 5) {
            System.err.println("PROBE_INVALID_ARGUMENTS");
            System.exit(2);
        }
        try {
            CompleteMeetingCreationUnitOfWork.Result result =
                    T15CreateRecoveryFixtures.unitOfWork(
                                    T15CreateRecoveryFixtures.dataSource())
                            .create(T15CreateRecoveryFixtures.request(
                                    args[0], args[1], args[2], args[3], args[4]));
            if (result instanceof CompleteMeetingCreationUnitOfWork.Success success) {
                byte[] body = CreateMeetingResponseFactJson.encode(success.response())
                        .getBytes(StandardCharsets.UTF_8);
                System.out.println("RESULT=" + success.code()
                        + " MEETING=" + success.response().meetingId()
                        + " RESPONSE_SHA256=" + HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256").digest(body)));
            } else if (result instanceof CompleteMeetingCreationUnitOfWork.Failure failure) {
                System.out.println("RESULT=" + failure.code());
            } else {
                throw new IllegalStateException("unknown Create result");
            }
        } catch (Exception failure) {
            System.err.println("PROBE_FAILED");
            System.exit(3);
        }
    }
}
