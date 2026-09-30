package com.czur.reference.application.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAPI {@code ErrorEnvelope} 的 transport-neutral 表示。
 *
 * <p>字段与 {@code contracts/ai-meeting-openapi.yaml} 逐项对齐。错误只允许稳定
 * 枚举/码、国际化 messageKey 和不含业务标识的 diagnosticId；不接收异常消息、
 * meetingId、Token、数据库地址或供应商响应正文。该对象只编码响应，不决定领域
 * 是否重试；{@code retryable} 必须来自现有端口结果。</p>
 */
public record HttpErrorEnvelope(String specVersion, Error error) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public HttpErrorEnvelope {
        requireText("specVersion", specVersion);
        Objects.requireNonNull(error, "error");
    }

    /** 统一公开错误 JSON；使用固定字段顺序便于契约测试和日志脱敏审查。 */
    public String toJson() {
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("domain", error.domain().name());
        errorBody.put("code", error.code());
        errorBody.put("severity", error.severity().name());
        errorBody.put("retryable", error.retryable());
        errorBody.put("retryAfterMs", error.retryAfterMs());
        errorBody.put("userAction", error.userAction().name());
        errorBody.put("messageKey", error.messageKey());
        errorBody.put("diagnosticId", error.diagnosticId());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("specVersion", specVersion);
        document.put("error", errorBody);
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("error envelope encoding failed", impossible);
        }
    }

    public record Error(
            Domain domain,
            String code,
            Severity severity,
            boolean retryable,
            Integer retryAfterMs,
            UserAction userAction,
            String messageKey,
            String diagnosticId) {
        public Error {
            Objects.requireNonNull(domain, "domain");
            requireText("code", code);
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(userAction, "userAction");
            requireText("messageKey", messageKey);
            requireText("diagnosticId", diagnosticId);
            if (retryAfterMs != null && retryAfterMs < 0) {
                throw new IllegalArgumentException("retryAfterMs must not be negative");
            }
            if (!retryable && retryAfterMs != null) {
                throw new IllegalArgumentException(
                        "non-retryable error must not include retryAfterMs");
            }
        }
    }

    public enum Domain { CONTRACT, IDENTITY, ENTITLEMENT, SESSION, AUDIO, NETWORK,
        MODEL, STORAGE, SUMMARY, INTERNAL }

    public enum Severity { WARNING, FATAL }

    public enum UserAction { NONE, RETRY, LOGIN, CONTACT_ADMIN, CHECK_NETWORK, FREE_STORAGE }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must be stable text");
        }
    }
}
