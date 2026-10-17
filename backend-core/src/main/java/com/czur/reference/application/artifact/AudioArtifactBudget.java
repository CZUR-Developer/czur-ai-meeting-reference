package com.czur.reference.application.artifact;

/**
 * 同场封口录音的统一大小预算，供 HTTP 接收边界与实际产物 Store 共同使用。
 *
 * <p>SDK 的标准采音为 16 kHz、单声道 PCM16，即每毫秒 32 字节。Opus 不可用时
 * 会正常保存该 PCM，因此不能继续用固定 64 MiB 限制声明可持续一小时的会议。
 * 预算按部署声明的最长会计算，另外保留 1 MiB 的尾帧／容器余量；原 64 MiB
 * 作为较短会议的兼容下限。这里只计算容量，不改变会议时长、计费用量或 JSON 限制。</p>
 */
public final class AudioArtifactBudget {
    /** 未传入部署预算的旧调用保持原限制，不隐式读取进程环境。 */
    public static final long LEGACY_MAX_BYTES = 64L * 1024L * 1024L;
    private static final long PCM_BYTES_PER_MS = 32L;
    private static final long TAIL_BYTES = 1024L * 1024L;

    private AudioArtifactBudget() { }

    /**
     * 返回同一部署的 HTTP／Store 都应采用的字节上限。
     *
     * @param maxDurationMs 已通过部署配置校验的最长会议时长，单位毫秒
     * @return 足以容纳最长会议 PCM 回退录音的预算；不预分配对应内存或磁盘
     */
    public static long forMaxDurationMs(long maxDurationMs) {
        if (maxDurationMs <= 0) throw new IllegalArgumentException("maxDurationMs must be positive");
        return Math.max(LEGACY_MAX_BYTES,
                Math.addExact(Math.multiplyExact(maxDurationMs, PCM_BYTES_PER_MS), TAIL_BYTES));
    }
}
