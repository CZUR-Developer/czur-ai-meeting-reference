package com.czur.reference.application.artifact;

/**
 * 会议正文类 JSON 产物的统一大小预算，供发布、读取和 HTTP 响应共同使用。
 *
 * <p>客户端最终转写会把整场文字同时写入顶层 {@code text} 和每个
 * {@code segments[].text}，因此同一段正文在产物里通常出现两次。固定 1 MiB
 * 上限只够约两三个小时的密集会议，优先于部署声明的最长时长生效，会出现
 * “Stop 已 READY、结果页读取 409”的不可达结果。这里按部署支持的最长会议
 * 时长推导容量：每会议分钟保留 16 KiB 的正文与元数据，另加 64 KiB 的尾部
 * 余量；旧调用没有部署时长的兼容下限为 {@link #LEGACY_MAX_BYTES}。这里只
 * 计算容量，不改变会议时长、计费用量、Manifest 或 JSON 结构。</p>
 */
public final class MeetingBodyArtifactBudget {
    /** 未传入部署预算的旧调用保持安全下限，不隐式读取进程环境。 */
    public static final long LEGACY_MAX_BYTES = 2L * 1024L * 1024L;
    private static final long BYTES_PER_MINUTE = 16L * 1024L;
    private static final long TAIL_BYTES = 64L * 1024L;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private MeetingBodyArtifactBudget() { }

    /**
     * 返回同一部署的发布、读取与 HTTP 响应都应采用的字节上限。
     *
     * @param maxDurationMs 已通过部署配置校验的最长会议时长，单位毫秒
     * @return 足以容纳最长会议正文 JSON 的预算；不预分配对应内存或磁盘
     */
    public static long forMaxDurationMs(long maxDurationMs) {
        if (maxDurationMs <= 0) throw new IllegalArgumentException("maxDurationMs must be positive");
        long minutes = Math.ceilDiv(maxDurationMs, MILLIS_PER_MINUTE);
        return Math.max(LEGACY_MAX_BYTES,
                Math.addExact(Math.multiplyExact(minutes, BYTES_PER_MINUTE), TAIL_BYTES));
    }
}
