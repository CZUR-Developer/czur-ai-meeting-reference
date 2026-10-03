package com.czur.reference.application.model;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 第二种模型格式的本地演示会话材料签发器。
 *
 * <p>部署方提供一个按公开样例协议收发 JSON 的 WebSocket 服务地址；这里不
 * 建立模型连接，也不转发音频。Android 参考 App 依据 {@code adapterId}
 * 选择 {@code sample-json-ws} Adapter，仍由闭源 SDK 管理连接和发送音频。
 * 地址可指向本机测试服务；本签发器本身不声称该地址有真实模型。</p>
 */
public final class SampleJsonWsModelSessionIssuer implements ModelSessionIssuer {
    public static final String ADAPTER_ID = "sample-json-ws";
    private static final Duration SESSION_TTL = Duration.ofMinutes(5);

    private final URI endpoint;
    private final Clock clock;

    /** @param endpoint 部署方提供的 ws 或 wss 模型地址 */
    public SampleJsonWsModelSessionIssuer(URI endpoint, Clock clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!("ws".equals(endpoint.getScheme()) || "wss".equals(endpoint.getScheme()))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("sampleJsonWsEndpoint");
        }
    }

    /** 返回该部署的固定模型地址及本次使用窗口，不申请真实厂商任务。 */
    @Override
    public Session issue() {
        return new Session(ADAPTER_ID, endpoint.toString(),
                clock.instant().plus(SESSION_TTL), Map.of());
    }
}
