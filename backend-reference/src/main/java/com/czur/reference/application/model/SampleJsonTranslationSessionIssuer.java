package com.czur.reference.application.model;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * 独立文本翻译样例的无密钥发行器；仅用于自有样例 HTTP 服务的协议接线。
 *
 * <p>它不连接第三方模型，不假装签发供应商凭据。正式供应商应实现
 * TranslationSessionIssuer，在服务端换取短期材料，再与相同 ASR 发行器组合；
 * 无需修改会议编排、字幕界面或结果 Web。</p>
 */
public final class SampleJsonTranslationSessionIssuer implements TranslationSessionIssuer {
    public static final String ADAPTER_ID = "sample-json-translation";
    public static final String ENDPOINT = "AI_MEETING_SAMPLE_JSON_TRANSLATION_ENDPOINT";
    private final URI endpoint;
    private final Clock clock;

    public SampleJsonTranslationSessionIssuer(URI endpoint, Clock clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))
                || endpoint.getHost() == null) throw new IllegalArgumentException(ENDPOINT);
    }

    /** 未配置样例地址则返回 null，听悟的一体化翻译和仅 ASR 的默认行为保持。 */
    public static TranslationSessionIssuer fromEnvironment(Map<String, String> environment,
            Clock clock) {
        String endpoint = environment.get(ENDPOINT);
        return endpoint == null || endpoint.isBlank() ? null
                : new SampleJsonTranslationSessionIssuer(URI.create(endpoint), clock);
    }

    @Override public Session issue(ModelSessionIssuer.Request request) {
        return new Session(ADAPTER_ID, endpoint.toString(), clock.instant().plusSeconds(300),
                null, Map.of());
    }
}
