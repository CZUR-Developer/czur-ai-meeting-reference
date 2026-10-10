package com.czur.reference.application.port.model;

import java.util.Objects;

/**
 * 一场会议协商后的公共音频格式。
 *
 * <p>设备 App 只向 Driver 发送这一格式的 {@link MeetingAudioFrame}。模型厂商
 * 所需的其他采样率或编码由 Driver/后端媒体规范化模块处理，不能反向改变
 * SystemAudioBroker 或公开设备协议。</p>
 */
public record AudioProfile(
        AudioEncoding encoding,
        int sampleRateHz,
        int channelCount,
        int frameDurationMs) {

    public AudioProfile {
        Objects.requireNonNull(encoding, "encoding");
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive");
        }
        if (channelCount <= 0) {
            throw new IllegalArgumentException("channelCount must be positive");
        }
        if (frameDurationMs <= 0) {
            throw new IllegalArgumentException("frameDurationMs must be positive");
        }
    }

    /** 首版公开协议中的单声道 16 kHz PCM 配置。 */
    public static AudioProfile pcm16kMono(int frameDurationMs) {
        return new AudioProfile(AudioEncoding.PCM_S16LE, 16_000, 1, frameDurationMs);
    }

    /** 公共音频编码；扩展枚举时必须保持 UNKNOWN 分支兼容。 */
    public enum AudioEncoding {
        UNKNOWN,
        PCM_S16LE
    }
}
