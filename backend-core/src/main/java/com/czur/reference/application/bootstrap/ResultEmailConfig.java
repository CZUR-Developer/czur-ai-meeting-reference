package com.czur.reference.application.bootstrap;

import java.util.Map;

/** 部署方提供的结果邮件 SMTP 配置；缺少开关时完全关闭该交付方式。 */
public record ResultEmailConfig(String host, int port, String username, String password,
        String from) {
    public static final String ENABLED = "AI_MEETING_RESULT_EMAIL_ENABLED";
    public static final String HOST = "AI_MEETING_RESULT_EMAIL_SMTP_HOST";
    public static final String PORT = "AI_MEETING_RESULT_EMAIL_SMTP_PORT";
    public static final String USERNAME = "AI_MEETING_RESULT_EMAIL_SMTP_USERNAME";
    public static final String PASSWORD = "AI_MEETING_RESULT_EMAIL_SMTP_PASSWORD";
    public static final String FROM = "AI_MEETING_RESULT_EMAIL_FROM";

    public static ResultEmailConfig fromEnvironment(Map<String, String> values) {
        if (!"true".equals(values.get(ENABLED))) return null;
        try {
            String host = required(values, HOST);
            int port = Integer.parseInt(required(values, PORT));
            if (port < 1 || port > 65535) throw new IllegalArgumentException(PORT);
            return new ResultEmailConfig(host, port, required(values, USERNAME),
                    required(values, PASSWORD), required(values, FROM));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("result email SMTP config is invalid");
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(key);
        }
        return value;
    }

    @Override public String toString() {
        return "ResultEmailConfig[configured=true, secret=REDACTED]";
    }
}
