package com.czur.reference.application.port;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 匿名结果 URL 的单份最终转写正文读取端口。
 *
 * <p>调用方只提供公开 artifactId 与沿用 Manifest 的 genTime；实现必须先核对
 * 已发布 Manifest 的访问决定和 ArtifactRef，再读取并验证持久内容。此端口不签发
 * 存储地址，也不让 HTTP 层解释不透明 storage binding。当前参考 Profile 只开放
 * {@code transcript.final}，其它类型均不通过此端口下载。</p>
 */
public interface MeetingArtifactContentPort {
    Result get(Request request);

    /** 与公开 Artifact 路径及 Manifest 结果链接一致的关闭式输入。 */
    record Request(String artifactId, long genTime) {
        private static final Pattern ARTIFACT_ID =
                Pattern.compile("^art_[A-Za-z0-9_-]+$");

        public Request {
            if (artifactId == null || artifactId.length() > 1024
                    || !ARTIFACT_ID.matcher(artifactId).matches() || genTime < -1) {
                throw new IllegalArgumentException("artifact request is invalid");
            }
        }

        @Override
        public String toString() {
            return "MeetingArtifactContentPort.Request[artifactId=redacted, genTime="
                    + genTime + ']';
        }
    }

    sealed interface Result permits Found, Pending, NotFound, Conflict, AccessDenied,
            Unavailable { }

    /** 已按 Manifest 中的引用核对的最终转写 JSON 正文。 */
    record Found(byte[] content) implements Result {
        public Found {
            Objects.requireNonNull(content, "content");
            if (content.length == 0) {
                throw new IllegalArgumentException("artifact content is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public String toString() {
            return "MeetingArtifactContentPort.Found[content=redacted]";
        }
    }

    record NotFound() implements Result { }

    /** 会议结果仍在生成；沿用 Manifest GET 的 202 状态投影。 */
    record Pending(MeetingStatus meetingStatus) implements Result {
        public Pending {
            Objects.requireNonNull(meetingStatus, "meetingStatus");
        }
    }

    /** 已发布事实与持久元数据或物理字节不一致，不能返回任何部分内容。 */
    record Conflict() implements Result { }

    /** 与 Manifest 共用的过期或删除撤权决定。 */
    record AccessDenied() implements Result { }

    /** 数据库或 Artifact Store 暂不可用；不能回退到 Manifest 中的元数据。 */
    record Unavailable(boolean retryable) implements Result { }
}
