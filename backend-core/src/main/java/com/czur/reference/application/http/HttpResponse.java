package com.czur.reference.application.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 与具体 HTTP server 无关的出站响应。
 *
 * <p>适配器只负责选择状态码、公开响应头和已经编码的 UTF-8 JSON body；网络
 * 写入、压缩、TLS 和连接关闭由外层 HTTP runtime 负责。响应头禁止 CR/LF，body
 * 不进入 {@link #toString()}，避免把 receipt、错误正文或未来新增字段写入日志。
 * header 名和值合计最多 {@link #MAX_HEADER_BYTES} 个 UTF-8 字节。普通响应
 * body 最多 {@link #MAX_BODY_BYTES} 个严格 UTF-8 字节；只有已经由部署预算
 * 明确放开的端点（当前是会议正文产物下载）可以在构造时指定单条响应上限，
 * 不会全局放开其他接口。</p>
 */
public record HttpResponse(int status, Map<String, String> headers, String body, int maxBodyBytes) {
    /** 所有规范化 header 名和值的严格 UTF-8 字节总上限。 */
    public static final int MAX_HEADER_BYTES = 16 * 1024;
    /** 普通响应 body 的严格 UTF-8 字节上限。 */
    public static final int MAX_BODY_BYTES = 1_048_576;

    /** 未显式指定端点上限时沿用普通响应上限。 */
    public HttpResponse(int status, Map<String, String> headers, String body) {
        this(status, headers, body, MAX_BODY_BYTES);
    }

    public HttpResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP status code");
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("response body limit must be positive");
        }
        Objects.requireNonNull(headers, "headers");
        Map<String, String> copy = new LinkedHashMap<>();
        long headerBytes = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null
                    || entry.getKey().indexOf('\r') >= 0 || entry.getKey().indexOf('\n') >= 0
                    || entry.getValue().indexOf('\r') >= 0
                    || entry.getValue().indexOf('\n') >= 0) {
                throw new IllegalArgumentException("response header is invalid");
            }
            String lower = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (copy.put(lower, entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate response header names are not allowed");
            }
            headerBytes += (long) utf8Bytes(lower, "response header")
                    + utf8Bytes(entry.getValue(), "response header");
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IllegalArgumentException("response headers exceed transport limit");
            }
        }
        headers = Collections.unmodifiableMap(copy);
        body = body == null ? "" : body;
        if (utf8Bytes(body, "response body") > maxBodyBytes) {
            throw new IllegalArgumentException("response body exceeds transport limit");
        }
    }

    public static HttpResponse json(int status, String body) {
        return json(status, body, MAX_BODY_BYTES);
    }

    /**
     * 端点已按部署预算放宽的单条响应；调用方必须传入与读取端口相同的上限。
     */
    public static HttpResponse json(int status, String body, int maxBodyBytes) {
        return new HttpResponse(status, Map.of(
                "content-type", "application/json",
                "cache-control", "no-store"), body, maxBodyBytes);
    }

    @Override
    public String toString() {
        return "HttpResponse[status=" + status + ", headerCount=" + headers.size()
                + ", bodyBytes=" + utf8Bytes(body, "response body") + ']';
    }

    private static int utf8Bytes(String value, String name) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            return encoded.remaining();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(name + " must be valid UTF-8 text");
        }
    }
}
