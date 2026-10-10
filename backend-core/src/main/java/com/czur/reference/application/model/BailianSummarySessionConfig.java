package com.czur.reference.application.model;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** 百炼文本应用的后端部署配置；永久 Key 只用于签发短期 Key。 */
public record BailianSummarySessionConfig(URI tokenUrl, URI completionBaseUrl,
        String appId, String permanentApiKey, int ttlSeconds) {
    public static final String ENABLED = "AI_MEETING_BAILIAN_SUMMARY_ENABLED";
    public static final String TOKEN_URL = "AI_MEETING_BAILIAN_TOKEN_URL";
    public static final String COMPLETION_BASE_URL = "AI_MEETING_BAILIAN_COMPLETION_BASE_URL";
    public static final String APP_ID = "AI_MEETING_BAILIAN_APP_ID";
    public static final String API_KEY = "DASHSCOPE_API_KEY";
    public static final String TTL_SECONDS = "AI_MEETING_BAILIAN_TOKEN_TTL_SECONDS";
    private static final String DEFAULT_TOKEN_URL =
            "https://dashscope.aliyuncs.com/api/v1/tokens";
    private static final String DEFAULT_COMPLETION_BASE_URL =
            "https://dashscope.aliyuncs.com/api/v1/apps";

    public BailianSummarySessionConfig {
        validEndpoint(tokenUrl, TOKEN_URL);
        validEndpoint(completionBaseUrl, COMPLETION_BASE_URL);
        if (appId == null || !appId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(APP_ID);
        }
        if (permanentApiKey == null || permanentApiKey.isBlank()
                || !permanentApiKey.equals(permanentApiKey.trim())) {
            throw new IllegalArgumentException(API_KEY);
        }
        if (ttlSeconds < 1 || ttlSeconds > 1800) {
            throw new IllegalArgumentException(TTL_SECONDS);
        }
    }

    public static BailianSummarySessionConfig fromEnvironment(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        String enabled = values.getOrDefault(ENABLED, "false");
        if ("false".equals(enabled)) return null;
        if (!"true".equals(enabled)) throw new IllegalArgumentException(ENABLED);
        int ttl;
        try {
            ttl = Integer.parseInt(values.getOrDefault(TTL_SECONDS, "300"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(TTL_SECONDS);
        }
        return new BailianSummarySessionConfig(
                configuredUri(values, TOKEN_URL, DEFAULT_TOKEN_URL),
                configuredUri(values, COMPLETION_BASE_URL, DEFAULT_COMPLETION_BASE_URL),
                values.get(APP_ID), values.get(API_KEY), ttl);
    }

    public URI tokenRequestUrl() {
        return URI.create(tokenUrl + "?expire_in_seconds=" + ttlSeconds);
    }

    public URI completionUrl() {
        return URI.create(completionBaseUrl.toString().replaceAll("/+$", "")
                + "/" + appId + "/completion");
    }

    private static void validEndpoint(URI endpoint, String field) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !("https".equals(endpoint.getScheme())
                || ("http".equals(endpoint.getScheme())
                && ("127.0.0.1".equals(endpoint.getHost())
                || "localhost".equals(endpoint.getHost()))))) {
            throw new IllegalArgumentException(field);
        }
    }

    private static URI configuredUri(Map<String, String> values, String field, String fallback) {
        try {
            return URI.create(values.getOrDefault(field, fallback));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(field);
        }
    }

    @Override public String toString() {
        return "BailianSummarySessionConfig[validated=true, secrets=REDACTED]";
    }
}
