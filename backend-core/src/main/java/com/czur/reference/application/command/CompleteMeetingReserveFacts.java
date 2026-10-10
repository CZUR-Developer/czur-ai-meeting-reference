package com.czur.reference.application.command;

import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 完整 Create 使用的内部权益预占键与首次预占事实校验。
 *
 * <p>V003 的操作范围仍是 {@code usage.reserve.v1}；新版 Create 只把外部幂等键
 * 映射成带版本域的内部键，避免正常情况下与使用同一外部键的旧 Create 共用权益
 * 事实。映射只依赖外部键，不依赖请求摘要、Grant、会议 ID 或时间：同键异摘要
 * 必须由 V003 返回冲突，两方首次未命中时也必须能读取同一持久赢家。</p>
 *
 * <p>旧 Create 对外部键未保留任何前缀，因此旧调用方若刻意提交一个与派生结果
 * 完全相同的键，在不改变 V003 操作范围或旧入口契约的前提下无法绝对隔离。本类
 * 使用版本域 SHA-256 固定长度映射，防止普通同键误碰撞，但不声称能消除这一
 * 理论边界。键和摘要都是敏感材料，不得出现在日志或异常正文。</p>
 */
public final class CompleteMeetingReserveFacts {
    private static final byte[] KEY_DOMAIN =
            "czur.ai-meeting.usage-reserve.meeting-create.v2"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final String KEY_PREFIX = "meeting.create.v2.reserve:";

    private CompleteMeetingReserveFacts() {
    }

    /**
     * 从已验证的完整 Create 请求事实派生 V003 内部 reserve 幂等键。
     *
     * <p>输入的外部键由请求快照执行严格 UTF-8 和 16～128 码点校验。摘要只
     * 用于 V003 操作内容冲突判断，不参与键派生；请求正文变化但外部键不变时，
     * 本方法必须返回相同键。输出稳定且不暴露外部原文，便于重试和跨进程恢复。</p>
     *
     * <p>编码精确为“大端 4 字节域长度 + ASCII 域正文 + 大端 4 字节外部键
     * UTF-8 长度 + 外部键 UTF-8 正文”；对整段字节取 SHA-256 小写十六进制，
     * 前置 {@code meeting.create.v2.reserve:}。跨语言实现必须保持这个字节顺序，
     * 修改格式时要使用新的版本域和前缀。</p>
     *
     * @param requestFact 与本次 Create 对应的完整规范请求事实
     * @return 固定版本域、固定长度、可作为 {@code ReserveUsageRequest} 和
     *         {@code UsageReserveLookup.Request} 幂等键的内部字符串
     */
    public static String internalReserveKey(CreateMeetingRequestFact requestFact) {
        Objects.requireNonNull(requestFact, "requestFact");
        byte[] keyBytes = requestFact.requestSnapshot().idempotencyKey()
                .getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(length(KEY_DOMAIN.length));
            digest.update(KEY_DOMAIN);
            digest.update(length(keyBytes.length));
            digest.update(keyBytes);
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /**
     * 检查已持久化的首次 reserve 赢家能否供本次完整 Create 继续使用。
     *
     * <p>调用方先通过 {@link UsageReserveLookup} 读取结果；此处再绑定可信部署、
     * 主体、内部键、完整请求摘要、Profile 版本、请求能力与首次授权快照。初始
     * Reservation 必须未绑定、版本为零，预估量须在首次授权上限内，会议 ID 须
     * 符合公开 Create 响应格式。创建时应使用赢家的 {@code expectedMeetingId}。
     * </p>
     *
     * <p>故意不比较当前新签发 Grant 的 ID/期限、候选会议 ID、当前策略或再次
     * 计算的 UsageEstimate：它们可能在两方首次 miss 的并发竞争或合法重试中变化。
     * 预估量是首次事实，并非响应的会议时长上限；后者由授权 Profile 与部署限制
     * 决定。返回 false 时调用方须关闭式拒绝，不应生成新会话或继续写会议。</p>
     *
     * @param winner 首次 reserve 的稳定结果，而不是当前 Reservation 状态
     * @param requestFact 与本次 Create 对应的完整规范请求事实
     * @param deploymentScope 服务端配置的部署范围
     * @param subjectScope 已验证身份的稳定主体
     * @return 是否与该 Create 请求和首次授权事实完全自洽
     */
    public static boolean isMatchingInitialWinner(
            UsageOperationResult winner,
            CreateMeetingRequestFact requestFact,
            String deploymentScope,
            String subjectScope) {
        Objects.requireNonNull(winner, "winner");
        Objects.requireNonNull(requestFact, "requestFact");
        requireText("deploymentScope", deploymentScope);
        MeetingOwnership.requireOwnedSubjectScope(subjectScope);
        if (!UsageReserveLookup.isReplayableOwnedReserveResult(winner)
                || !deploymentScope.equals(winner.deploymentScope())
                || !subjectScope.equals(winner.subjectScope())
                || !UsageOperationScopes.RESERVE.equals(winner.operationScope())
                || !internalReserveKey(requestFact).equals(winner.idempotencyKey())
                || !requestFact.canonicalRequestDigest().equals(winner.requestDigest())) {
            return false;
        }
        CreateMeetingRequestSnapshot request = requestFact.requestSnapshot();
        AuthorizedProfileSnapshot profile = winner.requireAuthorizedProfileSnapshot();
        UsageReservation reservation = winner.reservation();
        return request.profile().id().equals(profile.profileId())
                && request.profile().revision() == profile.profileRevision()
                && request.requestedCapabilities().equals(profile.requestedCapabilities())
                && profile.grantedCapabilities().containsAll(request.requestedCapabilities())
                && deploymentScope.equals(reservation.deploymentScope())
                && subjectScope.equals(reservation.subjectScope())
                && isPublicMeetingId(reservation.expectedMeetingId());
    }

    private static boolean isPublicMeetingId(String value) {
        if (value == null || value.length() > MeetingIdPolicy.MAX_UTF8_BYTES
                || !value.matches("mtg_[A-Za-z0-9_-]+")) {
            return false;
        }
        return true;
    }

    private static byte[] length(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
