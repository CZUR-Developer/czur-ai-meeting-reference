package com.czur.reference.application.model;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 听悟实时任务的部署配置。RAM 密钥只留在后端；未启用时不要求模型凭据。
 * 诊断文本不含地址、AppKey 或密钥。
 */
public record TingwuModelSessionConfig(
        URI createTaskUrl, String appKey, String accessKeyId, String accessKeySecret,
        String sourceLanguage, Duration connectTtl) {
    public static final String ENABLED = "AI_MEETING_TINGWU_ENABLED";
    public static final String CREATE_TASK_URL = "AI_MEETING_TINGWU_CREATE_TASK_URL";
    public static final String APP_KEY = "AI_MEETING_TINGWU_APP_KEY";
    public static final String ACCESS_KEY_ID = "AI_MEETING_TINGWU_ACCESS_KEY_ID";
    public static final String ACCESS_KEY_SECRET = "AI_MEETING_TINGWU_ACCESS_KEY_SECRET";
    public static final String SOURCE_LANGUAGE = "AI_MEETING_TINGWU_SOURCE_LANGUAGE";
    public static final String TINGWU_APP_KEY = "TINGWU_APP_KEY";
    public static final String ALIYUN_ACCESS_KEY_ID = "ALIYUN_ACCESS_KEY_ID";
    public static final String ALIYUN_ACCESS_KEY_SECRET = "ALIYUN_ACCESS_KEY_SECRET";
    private static final String DEFAULT_URL =
            "https://tingwu.cn-beijing.aliyuncs.com/openapi/tingwu/v2/tasks";

    public TingwuModelSessionConfig {
        Objects.requireNonNull(createTaskUrl, "createTaskUrl");
        Objects.requireNonNull(connectTtl, "connectTtl");
        if (!"https".equals(createTaskUrl.getScheme())
                || createTaskUrl.getHost() == null || createTaskUrl.getRawQuery() != null
                || createTaskUrl.getRawFragment() != null) {
            throw new IllegalArgumentException(CREATE_TASK_URL);
        }
        required(appKey, APP_KEY);
        required(accessKeyId, ACCESS_KEY_ID);
        required(accessKeySecret, ACCESS_KEY_SECRET);
        if (!"cn".equals(sourceLanguage) && !"en".equals(sourceLanguage)) {
            throw new IllegalArgumentException(SOURCE_LANGUAGE);
        }
        if (connectTtl.isNegative() || connectTtl.isZero()) {
            throw new IllegalArgumentException("connectTtl");
        }
    }

    /** 显式开启后检查凭据；缺省关闭，不影响原有 Mock/Create/WSS 链路。 */
    public static TingwuModelSessionConfig fromEnvironment(Map<String, String> environment) {
        String enabled = environment.getOrDefault(ENABLED, "false");
        if ("false".equals(enabled)) return null;
        if (!"true".equals(enabled)) throw new IllegalArgumentException(ENABLED);
        URI url;
        try {
            url = URI.create(environment.getOrDefault(CREATE_TASK_URL, DEFAULT_URL));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(CREATE_TASK_URL);
        }
        return new TingwuModelSessionConfig(url,
                credential(environment, TINGWU_APP_KEY, APP_KEY),
                credential(environment, ALIYUN_ACCESS_KEY_ID, ACCESS_KEY_ID),
                credential(environment, ALIYUN_ACCESS_KEY_SECRET, ACCESS_KEY_SECRET),
                environment.getOrDefault(SOURCE_LANGUAGE, "cn"), Duration.ofMinutes(5));
    }

    /** 优先复用已有听悟评测凭据注入名；空占位符不遮蔽另一来源。 */
    private static String credential(Map<String, String> values, String preferred, String scoped) {
        String value = values.get(preferred);
        return required(value == null || value.isBlank() ? values.get(scoped) : value, preferred);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    @Override public String toString() {
        return "TingwuModelSessionConfig[validated=true, secrets=REDACTED]";
    }
}
