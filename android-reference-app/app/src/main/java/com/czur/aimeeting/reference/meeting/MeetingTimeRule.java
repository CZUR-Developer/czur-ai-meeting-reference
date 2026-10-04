package com.czur.aimeeting.reference.meeting;

import java.util.Locale;

/**
 * 一场会议的时长与无转写阈值。调用方传入单调时钟时间，测试可直接跳到阈值；
 * 本类只计算提示和停止原因，真正停止仍由会议 Service 的原有收口流程执行。
 */
public final class MeetingTimeRule {
    private static final long MINUTE_MS = 60_000L;
    private static final long LIMIT_WARNING_MS = 20 * MINUTE_MS;
    private static final long NO_TEXT_WARNING_MS = 4 * MINUTE_MS;
    private static final long NO_TEXT_STOP_MS = 5 * MINUTE_MS;

    public enum StopCause { NONE, TIME_LIMIT, NO_TRANSCRIPT }

    public record Snapshot(long elapsedMs, long remainingMs, boolean limitWarning,
            boolean noTranscriptWarning, long noTranscriptRemainingMs, StopCause stopCause) {}

    private final long startedAtMs;
    private final long maxDurationMs;
    private long lastTranscriptAtMs;

    public MeetingTimeRule(long startedAtMs, long maxDurationMs) {
        if (maxDurationMs <= 0) throw new IllegalArgumentException("会议时长上限必须大于零");
        this.startedAtMs = startedAtMs;
        this.maxDurationMs = maxDurationMs;
        lastTranscriptAtMs = startedAtMs;
    }

    /** 任意非空原文 partial/final 都重新开始无转写计时；译文不单独作为恢复信号。 */
    public void onTranscript(long nowMs, String text) {
        if (text != null && !text.isBlank()) {
            lastTranscriptAtMs = nowMs;
        }
    }

    /** 20 分钟提醒只适用于上限超过 20 分钟的会议；5 分钟停止无需曾有字幕。 */
    public Snapshot at(long nowMs) {
        long elapsed = Math.max(0L, nowMs - startedAtMs);
        long remaining = Math.max(0L, maxDurationMs - elapsed);
        long idle = Math.max(0L, nowMs - lastTranscriptAtMs);
        StopCause stop = elapsed >= maxDurationMs ? StopCause.TIME_LIMIT
                : idle >= NO_TEXT_STOP_MS ? StopCause.NO_TRANSCRIPT : StopCause.NONE;
        return new Snapshot(elapsed, remaining,
                maxDurationMs > LIMIT_WARNING_MS && remaining <= LIMIT_WARNING_MS,
                idle >= NO_TEXT_WARNING_MS && idle < NO_TEXT_STOP_MS,
                Math.max(0L, NO_TEXT_STOP_MS - idle), stop);
    }

    /** 页面与悬浮窗共用的 HH:mm:ss 格式，避免两处倒计时显示不一致。 */
    public static String format(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L,
                (seconds / 60L) % 60L, seconds % 60L);
    }
}
