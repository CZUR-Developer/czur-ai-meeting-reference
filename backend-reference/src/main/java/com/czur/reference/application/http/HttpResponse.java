package com.czur.reference.application.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 与具体 HTTP server 无关的出站响应。
 *
 * <p>适配器只负责选择状态码、公开响应头和已经编码的 UTF-8 JSON body；网络
 * 写入、压缩、TLS 和连接关闭由外层 HTTP runtime 负责。响应头禁止 CR/LF，body
 * 不进入 {@link #toString()}，避免把 receipt、错误正文或未来新增字段写入日志。</p>
 */
public record HttpResponse(int status, Map<String, String> headers, String body) {
    public HttpResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP status code");
        }
        Objects.requireNonNull(headers, "headers");
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null
                    || entry.getKey().indexOf('\r') >= 0 || entry.getKey().indexOf('\n') >= 0
                    || entry.getValue().indexOf('\r') >= 0
                    || entry.getValue().indexOf('\n') >= 0) {
                throw new IllegalArgumentException("response header is invalid");
            }
            copy.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue());
        }
        headers = Collections.unmodifiableMap(copy);
        body = body == null ? "" : body;
    }

    public static HttpResponse json(int status, String body) {
        return new HttpResponse(status, Map.of(
                "content-type", "application/json",
                "cache-control", "no-store"), body);
    }

    @Override
    public String toString() {
        return "HttpResponse[status=" + status + ", headerCount=" + headers.size()
                + ", bodyBytes=" + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ']';
    }
}
