package com.czur.reference.application.manifest;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingState;

import java.time.Clock;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Manifest 结果 URL 的访问策略。
 *
 * <p>该策略只处理已经从同一个数据库快照读出的会议状态和最新 Manifest；它
 * 不访问数据库、不生成 Manifest，也不把 {@code genTime} 写入 Manifest 正文。
 * JDBC、REST 或 Web 适配器可以复用同一套状态判断，避免在各层分别解释
 * “生成中”“已完成”和“删除已受理”。</p>
 *
 * <p>结果链接沿用现有规则：{@code genTime=-1} 永不过期；非负值表示链接生成
 * 时间（毫秒），过期时间由构造时注入的统一 TTL 决定。这里不内置 24 小时、7 天
 * 等产品数值，调用方必须在装配层明确提供 TTL。</p>
 */
public final class ManifestResultAccessPolicy {
    private final Clock clock;
    private final Duration resultLinkTtl;

    /**
     * 创建结果访问策略。
     *
     * @param clock 访问判断使用的时钟；测试应传入固定时钟
     * @param resultLinkTtl 所有非永久结果链接共用的有效期，不能为负
     */
    public ManifestResultAccessPolicy(Clock clock, Duration resultLinkTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resultLinkTtl = requireNonNegative(resultLinkTtl);
    }

    /**
     * 使用系统 UTC 时钟创建策略；有效期仍必须由部署装配层显式传入。
     */
    public ManifestResultAccessPolicy(Duration resultLinkTtl) {
        this(Clock.systemUTC(), resultLinkTtl);
    }

    /** 返回本策略使用的统一结果链接有效期。 */
    public Duration resultLinkTtl() {
        return resultLinkTtl;
    }

    /**
     * 根据一个数据库快照决定公开结果。
     *
     * <p>顺序是固定的：删除撤权优先于过期和正文解析；过期链接不可读取；没有
     * revision 的 {@code NOT_STARTED/GENERATING} 才是 Pending；完成状态必须有
     * 与会议状态、身份和 revision 对齐的完整 Manifest。所有不一致均关闭式返回
     * {@link MeetingManifestPort.Conflict}，不会返回空正文或猜测状态。</p>
     *
     * @param request 已校验的结果请求，包含 meetingId 和 genTime
     * @param meeting 从同一数据库 statement 读取的会议状态
     * @param latest 同一 statement 读取的 latest revision；空值表示 revision 数为零
     */
    public MeetingManifestPort.Result evaluate(
            MeetingManifestPort.Request request,
            MeetingStatus meeting,
            Optional<MeetingManifest> latest) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(meeting, "meeting");
        Objects.requireNonNull(latest, "latest");

        if (!request.meetingId().equals(meeting.meetingId())) {
            return new MeetingManifestPort.Conflict(
                    MeetingManifestPort.ConflictReason.SNAPSHOT_INCONSISTENT);
        }

        // 删除受理后立即撤销公开结果。即使旧 revision 仍保留在事实表中，也不能
        // 通过结果 URL 继续读取；因此删除检查必须先于 genTime 和 Manifest 解析。
        if (isDeletion(meeting)) {
            return new MeetingManifestPort.AccessDenied();
        }
        if (isExpired(request.genTime())) {
            return new MeetingManifestPort.AccessDenied();
        }

        ManifestStatus state = meeting.manifestState();
        if (state == ManifestStatus.NOT_STARTED || state == ManifestStatus.GENERATING) {
            return latest.isEmpty()
                    ? new MeetingManifestPort.Pending(meeting)
                    : new MeetingManifestPort.Conflict(
                            MeetingManifestPort.ConflictReason.SNAPSHOT_INCONSISTENT);
        }

        if (!isComplete(state) || latest.isEmpty()) {
            return new MeetingManifestPort.Conflict(
                    MeetingManifestPort.ConflictReason.SNAPSHOT_INCONSISTENT);
        }

        MeetingManifest manifest = latest.orElseThrow();
        if (!isConsistent(meeting, manifest)) {
            return new MeetingManifestPort.Conflict(
                    MeetingManifestPort.ConflictReason.SNAPSHOT_INCONSISTENT);
        }
        return new MeetingManifestPort.Found(manifest);
    }

    /**
     * 判断结果链接是否已经过期。TTL 的结束点采用半开区间：在
     * {@code genTime + TTL} 时刻及之后均视为过期；这与旧页面“生成时间加有效期”
     * 的毫秒语义一致。溢出时把结束点视为可表示时间范围之外，不错误地提前拒绝。
     */
    private boolean isExpired(long genTime) {
        if (genTime == -1) {
            return false;
        }
        Instant now = Objects.requireNonNull(clock.instant(), "clock.instant");
        Instant expiresAt;
        try {
            expiresAt = Instant.ofEpochMilli(genTime).plus(resultLinkTtl);
        } catch (ArithmeticException | DateTimeException failure) {
            // 非负 TTL 的正向溢出意味着在 Instant 可表示范围内不会到期；不把
            // 合法链接误判为过期。Clock 自身异常则由上层 JDBC 适配器归为不可用。
            return false;
        }
        return !now.isBefore(expiresAt);
    }

    private static boolean isDeletion(MeetingStatus meeting) {
        return meeting.state() == MeetingState.DELETION_PENDING
                || meeting.state() == MeetingState.DELETED
                || meeting.manifestState() == ManifestStatus.DELETION_PENDING
                || meeting.manifestState() == ManifestStatus.DELETED;
    }

    private static boolean isComplete(ManifestStatus status) {
        return status == ManifestStatus.READY
                || status == ManifestStatus.READY_WITH_WARNINGS
                || status == ManifestStatus.FAILED;
    }

    private static boolean isConsistent(MeetingStatus meeting, MeetingManifest manifest) {
        if (!meeting.meetingId().equals(manifest.meetingId())
                || meeting.manifestState() != manifest.status()
                || !meeting.finalization().isAccepted()) {
            return false;
        }
        return switch (manifest.status()) {
            case READY -> meeting.state() == MeetingState.READY;
            case READY_WITH_WARNINGS -> meeting.state() == MeetingState.READY_WITH_WARNINGS;
            case FAILED -> meeting.state() == MeetingState.FAILED;
            default -> false;
        };
    }

    private static Duration requireNonNegative(Duration value) {
        Objects.requireNonNull(value, "resultLinkTtl");
        if (value.isNegative()) {
            throw new IllegalArgumentException("resultLinkTtl must not be negative");
        }
        return value;
    }
}
