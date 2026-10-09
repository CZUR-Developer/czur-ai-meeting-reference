package com.czur.aimeeting.client;

import com.czur.aimeeting.model.SealedAudioArtifact;
import java.io.IOException;
import java.util.Objects;

/**
 * 部署方的正常收尾发布扩展，不是第二个会议状态机或通用插件 RPC。
 * 在唯一 Coordinator IO 队列、最终事件/用量/原选定总结保存后、后端 Stop 前执行。
 * 正常返回表示已确认本场发布及所需资源登记；未确认必须抛 IOException。
 * 网络响应丢失后的外部幂等由发布方按同场协议处理；此接口不自动重试、恢复或删除文件。
 */
@FunctionalInterface
public interface MeetingArtifactPublisher {
    void publish(Context context) throws IOException;

    /** 显式装配的发布器默认可处理音频；默认无副作用实现则返回 false。 */
    default boolean supportsAudioPublication() { return true; }

    /** 参考及旧构造器默认值：无录音、无上传副作用，不冒称已装配音频发布。 */
    static MeetingArtifactPublisher none() { return None.INSTANCE; }

    /**
     * 仅含本场 sealed 事实及开场已冻结的服务访问信息。不持有全局身份 Provider，
     * 不在上传时重新登录、交换 Token 或追随其他账号。本地位置与访问凭据禁止日志。
     */
    final class Context {
        private final String meetingId;
        private final SealedAudioArtifact audio;
        private final long frozenAudioDurationMs;
        private final String serverUrl;
        private final String accessToken;
        private final ClientIdentity clientIdentity;

        Context(String meetingId, SealedAudioArtifact audio, long frozenAudioDurationMs,
                MeetingClientConfig sessionConfig) {
            this.meetingId = Objects.requireNonNull(meetingId, "meetingId");
            this.audio = Objects.requireNonNull(audio, "audio");
            this.frozenAudioDurationMs = frozenAudioDurationMs;
            this.serverUrl = sessionConfig.serverUrl();
            this.accessToken = sessionConfig.accessToken();
            this.clientIdentity = sessionConfig.clientIdentity();
        }

        public String meetingId() { return meetingId; }
        public SealedAudioArtifact audio() { return audio; }
        public long frozenAudioDurationMs() { return frozenAudioDurationMs; }
        public String serverUrl() { return serverUrl; }
        public String accessToken() { return accessToken; }
        public ClientIdentity clientIdentity() { return clientIdentity; }

        @Override public String toString() {
            return "MeetingArtifactPublisher.Context[meetingId=redacted, audio=redacted, "
                    + "serverUrl=redacted, accessToken=redacted, clientIdentity=redacted]";
        }
    }

    final class None implements MeetingArtifactPublisher {
        private static final MeetingArtifactPublisher INSTANCE = new None();
        private None() { }
        @Override public boolean supportsAudioPublication() { return false; }
        @Override public void publish(Context context) { }
    }
}
