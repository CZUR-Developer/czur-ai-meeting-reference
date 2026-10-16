package com.czur.reference.application.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

/**
 * 独立二进制 HTTP 请求的传输值对象。
 *
 * <p>录音正文不能经过 {@link HttpRequest} 的 UTF-8 {@code String} 和 1MiB JSON
 * 上限；本对象只保存已经约束的请求元数据和一个由网络宿主拥有的流。调用方必须
 * 在处理完成后关闭本对象，不能把流交给第二个会议或缓存。媒体协议的媒体类型、
 * 摘要和业务路径仍由上层端口核对，本类不解释厂商格式。</p>
 */
public final class BinaryHttpRequest implements AutoCloseable {
    /** 参考服务单次媒体上传的传输上限；JSON 正文上限保持不变。 */
    public static final long MAX_CONTENT_BYTES = 64L * 1024L * 1024L;

    private final HttpRequest metadata;
    private final long contentLength;
    private final InputStream content;

    /**
     * 创建一个已经声明长度的二进制请求。
     *
     * @param method 大写 HTTP 方法
     * @param path 已规范化绝对路径
     * @param headers 已规范化请求头
     * @param query 已解码查询参数
     * @param contentLength 正文长度；本协议不接受未知长度或零长度录音
     * @param content 请求正文流，关闭责任转移给本对象调用方
     */
    public BinaryHttpRequest(String method, String path, Map<String, String> headers,
            Map<String, String> query, long contentLength, InputStream content) {
        this.metadata = new HttpRequest(method, path, headers, query, "");
        if (contentLength <= 0 || contentLength > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("binary content length is invalid");
        }
        this.contentLength = contentLength;
        this.content = Objects.requireNonNull(content, "content");
    }

    /** 返回不含正文的请求元数据，供认证和路由复用既有 HTTP 逻辑。 */
    public HttpRequest metadata() { return metadata; }

    public String method() { return metadata.method(); }
    public String path() { return metadata.path(); }
    public Map<String, String> headers() { return metadata.headers(); }
    public Map<String, String> query() { return metadata.query(); }
    public String header(String name) { return metadata.header(name); }
    public long contentLength() { return contentLength; }

    /** 返回当前请求的单次正文流；调用方不得关闭后再次读取。 */
    public InputStream content() { return content; }

    @Override
    public void close() throws IOException {
        content.close();
    }

    @Override
    public String toString() {
        return "BinaryHttpRequest[method=" + method() + ", pathPresent=true"
                + ", contentLength=redacted, body=stream]";
    }
}
