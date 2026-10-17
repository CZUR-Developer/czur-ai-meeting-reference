package com.czur.reference.application.speaker;

import com.czur.reference.application.bootstrap.MeetingHttpAssembly;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** 参考会后人物插件的显式外置配置；未选择 Provider 时完全关闭。 */
public record ReferenceSpeakerIdentityConfig(URI endpoint, String serviceId, String appId,
        String apiKey, String apiSecret, String groupPrefix, double matchThreshold,
        Duration timeout, String ffmpeg, int pcmSampleRate, int pcmChannels) {
    /**
     * 读取配置快照并返回唯一核心 Context 的装配工厂。真实配置只由进程注入，
     * 不写仓库、不在异常或日志中输出密钥。未设置选择器不要求任何声纹字段。
     */
    public static MeetingHttpAssembly.SpeakerIdentityFactory factory(Map<String, String> values, Clock clock) {
        String provider = values.getOrDefault("AI_MEETING_SPEAKER_IDENTITY_PROVIDER", "").strip();
        if (provider.isEmpty() || "none".equals(provider)) return null;
        if (!"xfyun".equals(provider)) throw new IllegalArgumentException("未知参考人物 Provider");
        var config = new ReferenceSpeakerIdentityConfig(
                URI.create(values.getOrDefault("AI_MEETING_XFYUN_VOICEPRINT_ENDPOINT",
                        "https://api.xf-yun.com/v1/private/s1aa729d0")),
                values.getOrDefault("AI_MEETING_XFYUN_VOICEPRINT_SERVICE_ID", "s1aa729d0"),
                required(values, "AI_MEETING_XFYUN_VOICEPRINT_APP_ID"),
                required(values, "AI_MEETING_XFYUN_VOICEPRINT_API_KEY"),
                required(values, "AI_MEETING_XFYUN_VOICEPRINT_API_SECRET"),
                values.getOrDefault("AI_MEETING_XFYUN_VOICEPRINT_GROUP_PREFIX", "reference_"),
                Double.parseDouble(values.getOrDefault("AI_MEETING_XFYUN_VOICEPRINT_MATCH_THRESHOLD", "0.6")),
                Duration.ofMillis(Long.parseLong(values.getOrDefault("AI_MEETING_XFYUN_VOICEPRINT_TIMEOUT_MS", "10000"))),
                values.getOrDefault("AI_MEETING_FFMPEG", "ffmpeg"),
                Integer.parseInt(values.getOrDefault("AI_MEETING_SPEAKER_PCM_SAMPLE_RATE", "16000")),
                Integer.parseInt(values.getOrDefault("AI_MEETING_SPEAKER_PCM_CHANNELS", "1")));
        return (database, artifacts) -> new ReferenceXfyunSpeakerIdentityProvider(config,
                new JdbcReferenceSpeakerPersons(database), artifacts, clock);
    }

    public ReferenceSpeakerIdentityConfig {
        if (endpoint.getHost() == null || !"https".equals(endpoint.getScheme())
                || endpoint.getRawQuery() != null || !serviceId.matches("[A-Za-z0-9_]+")
                || !groupPrefix.matches("[A-Za-z0-9_]{1,12}")
                || !Double.isFinite(matchThreshold) || matchThreshold < 0 || matchThreshold > 1
                || timeout.isNegative() || timeout.isZero() || pcmSampleRate <= 0 || pcmChannels <= 0) {
            throw new IllegalArgumentException("参考人物 Provider 配置无效");
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参考人物配置 " + key);
        return value;
    }

    @Override public String toString() { return "ReferenceSpeakerIdentityConfig[provider=xfyun, configured=true]"; }
}
