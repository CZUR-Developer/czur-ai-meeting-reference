package com.czur.reference.application.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 与 Servlet、Netty 或 JDK HttpServer 无关的入站 HTTP 请求值对象。
 *
 * <p>底层 HTTP 适配器只需要把方法、路径、查询参数、已规范化请求头和 UTF-8
 * body 交给本对象；本对象不解析 Bearer、不验证权限，也不持有连接生命周期。
 * body/头字段受到固定大小限制，避免传输层把无限输入交给领域层。诊断文本只
 * 输出方法和是否存在 body，绝不输出路径参数、Token、幂等键或正文。</p>
 */
public record HttpRequest(
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> query,
        String body) {
    public static final int MAX_PATH_CHARS = 4096;
    public static final int MAX_HEADER_COUNT = 64;
    public static final int MAX_BODY_CHARS = 1_048_576;

    public HttpRequest {
        method = normalizeMethod(method);
        path = requirePath(path);
        headers = immutableHeaders(headers);
        query = immutableValues(query, "query");
        body = body == null ? "" : body;
        if (body.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("body exceeds transport limit");
        }
    }

    /** 省略查询参数的构造器，适合没有 query 的 POST/DELETE 请求。 */
    public HttpRequest(
            String method,
            String path,
            Map<String, String> headers,
            String body) {
        this(method, path, headers, Map.of(), body);
    }

    /** 按 RFC 头字段大小写不敏感地读取一个请求头。 */
    public String header(String name) {
        Objects.requireNonNull(name, "name");
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** 返回不含认证、路径参数和正文的安全诊断摘要。 */
    @Override
    public String toString() {
        return "HttpRequest[method=" + method + ", pathPresent=true, headerCount="
                + headers.size() + ", queryCount=" + query.size()
                + ", bodyPresent=" + !body.isEmpty() + ']';
    }

    private static String normalizeMethod(String value) {
        if (value == null || value.isBlank() || value.length() > 16
                || !value.chars().allMatch(character -> character >= 'A' && character <= 'Z')) {
            throw new IllegalArgumentException("method must be uppercase ASCII");
        }
        return value;
    }

    private static String requirePath(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_PATH_CHARS
                || value.charAt(0) != '/' || value.indexOf('?') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("path is invalid");
        }
        return value;
    }

    private static Map<String, String> immutableHeaders(Map<String, String> values) {
        Objects.requireNonNull(values, "headers");
        if (values.size() > MAX_HEADER_COUNT) {
            throw new IllegalArgumentException("too many headers");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || !key.equals(key.trim())
                    || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0
                    || value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("header is invalid");
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (normalized.put(lower, value) != null) {
                throw new IllegalArgumentException("duplicate header names are not allowed");
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static Map<String, String> immutableValues(
            Map<String, String> values, String name) {
        Objects.requireNonNull(values, name);
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null
                    || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(name + " contains invalid value");
            }
            if (copy.put(key, value) != null) {
                throw new IllegalArgumentException(name + " contains duplicate key");
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
