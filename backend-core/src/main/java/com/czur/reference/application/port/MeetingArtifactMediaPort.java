package com.czur.reference.application.port;

import com.czur.reference.application.port.artifact.ArtifactStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 统一结果中媒体产物的流式读取端口。
 *
 * <p>该端口与 {@link MeetingArtifactContentPort} 的小型 JSON 读取端口分开：长录音
 * 不会先读入 {@code byte[]}，也不会经过 JSON/Base64 响应。实现仍必须先复用同一
 * Manifest 的 genTime、撤权和 READY 事实，再返回已经由 {@link ArtifactStore}
 * 打开的读取句柄。调用方拥有 {@link Found} 的关闭责任，HTTP 宿主负责把句柄关闭
 * 在响应流结束之后。</p>
 *
 * <p>端口不暴露对象存储地址、Bucket、Key 或访问凭据；媒体的类型、长度和摘要只取
 * 自统一 Manifest／READY descriptor。当前公共实现首先支持 {@code audio.original}，
 * 未来其它二进制产物仍需按同一访问规则显式加入。</p>
 */
public interface MeetingArtifactMediaPort {
    /** 按公开结果 URL 的同一 artifactId/genTime 上下文打开媒体流。 */
    Result open(MeetingArtifactContentPort.Request request);

    sealed interface Result permits Found, Pending, NotFound, Conflict, AccessDenied,
            Unavailable { }

    /** 已按 Manifest 和 READY metadata 核对的媒体流；调用方必须关闭。 */
    record Found(MeetingManifest.ArtifactRef reference,
                 ArtifactStore.ReadHandle handle) implements Result, AutoCloseable {
        public Found {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(handle, "handle");
            ArtifactStore.ArtifactDescriptor descriptor = handle.artifact();
            var spec = descriptor.intent().spec();
            if (!reference.artifactId().equals(spec.artifactId())
                    || !reference.type().equals(spec.artifactType())
                    || !reference.mediaType().equals(spec.mediaType())
                    || reference.size() != descriptor.content().size()
                    || !reference.checksum().equals(descriptor.content().checksum())) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                    // 构造失败时只报告事实不一致，关闭失败不能覆盖主因。
                }
                throw new IllegalArgumentException("media handle does not match manifest");
            }
        }

        /** 返回已由 Store 打开的流；调用方不得把它交给其它场次或缓存。 */
        public InputStream content() {
            return handle.content();
        }

        /** 关闭底层 Store 句柄。 */
        @Override
        public void close() throws IOException {
            handle.close();
        }

        @Override
        public String toString() {
            return "MeetingArtifactMediaPort.Found[reference=redacted, handle=redacted]";
        }
    }

    /** 会议清单尚未形成，沿用 Manifest 查询的 202 状态语义。 */
    record Pending(MeetingStatus meetingStatus) implements Result {
        public Pending {
            Objects.requireNonNull(meetingStatus, "meetingStatus");
        }
    }

    record NotFound() implements Result { }

    /** Manifest、READY metadata 或底层 descriptor 不一致。 */
    record Conflict() implements Result { }

    /** 结果链接已过期或会议已删除。 */
    record AccessDenied() implements Result { }

    /** 数据库或 Store 暂不可用；宿主可按 retryable 决定是否稍后重试。 */
    record Unavailable(boolean retryable) implements Result { }
}
