package com.czur.aimeeting.model;

/**
 * SDK 从系统音频能力读取的声源到达方向（DOA）样本。角度是系统提供的物理量，
 * SDK 只做技术性平滑；左／右栏划分、语种回退和片段锁侧由应用决定。
 *
 * <p>回调可发生在非 UI 线程，应用应与字幕回调排入同一 UI 队列。时间戳使用 Android
 * 单调时钟 elapsedRealtime 的毫秒值，可用于判断样本时效，不能当作服务器时间。</p>
 */
public final class AudioDirectionSample {
    /** 区分硬件支持但暂未检测到声源，与当前平台完全没有方向能力。 */
    public enum Status {
        /** 本次读到有效物理角度，raw 和 stable 角度可用于本次业务决策。 */
        AVAILABLE,
        /** 系统支持方向能力，但本次未读到有效声源；应用应清除旧的全局方向。 */
        NO_DIRECTION,
        /** 当前音源／系统没有可用方向能力；普通 MIC 和 Debug WAV 使用此状态。 */
        UNSUPPORTED
    }

    private final float angleDegrees;
    private final float stableAngleDegrees;
    private final long sampledAtElapsedRealtimeMs;
    private final Status status;

    /**
     * 创建系统样本。AVAILABLE 时角度为 0～360°；其他状态使用 Float.NaN，
     * 禁止把旧有效角度伪装成本次观测。stableAngleDegrees 为技术平滑后的角度。
     */
    public AudioDirectionSample(float angleDegrees, float stableAngleDegrees,
            long sampledAtElapsedRealtimeMs, Status status) {
        this.angleDegrees = angleDegrees;
        this.stableAngleDegrees = stableAngleDegrees;
        this.sampledAtElapsedRealtimeMs = sampledAtElapsedRealtimeMs;
        this.status = status;
    }

    /** 当前物理原始角度；不可用时为 Float.NaN。 */
    public float angleDegrees() { return angleDegrees; }

    /** SDK 技术平滑后的角度；不包含左右栏或语言等应用业务含义。 */
    public float stableAngleDegrees() { return stableAngleDegrees; }

    /** 本次读取的 elapsedRealtime 毫秒时间戳。 */
    public long sampledAtElapsedRealtimeMs() { return sampledAtElapsedRealtimeMs; }

    /** 本次方向能力／观测状态；应用据此决定是否使用方向或降级。 */
    public Status status() { return status; }
}
