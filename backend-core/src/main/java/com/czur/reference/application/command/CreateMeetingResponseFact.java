package com.czur.reference.application.command;

import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingState;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 统一 Create 成功响应的可重放事实。
 *
 * <p>这个值对象冻结的是 Create 成功响应真正需要的业务事实，而不是当前
 * {@link com.czur.reference.domain.meeting.MeetingAggregate} 的可变快照。这样，
 * 创建请求的首次成功结果可以在进程重启和幂等重放时保持字节来源稳定；会议
 * 后续进入 {@code RUNNING}、{@code STOPPING} 或最终收口状态，也不会反过来
 * 改写首次 Create 响应。首版响应明确要求状态为 {@link MeetingState#CREATED}，
 * 与现有创建事务的成功语义一致。</p>
 *
 * <p>本类型只冻结应用层事实，不负责数据库写入、幂等键绑定、权益计算、模型
 * 会话创建或 HTTP JSON 编码。实时会话地址、会话令牌和过期时间必须由真正的
 * 会话分配端口提供；构造器不会猜测默认 URL、空令牌或默认期限。将来需要把
 * 本事实写入创建事务时，应与请求事实、完整响应 JSON 及幂等范围放进同一持久
 * 化边界，而不是从旧的 {@code MeetingCommandResult} 临时拼装。</p>
 *
 * <p>集合在构造时复制并按稳定顺序保存。响应事实只验证 Profile 引用本身和能力
 * 决策对象的结构，不在此处重新执行 Product Profile、权益 Grant 与能力计划的
 * 交叉计算；这些一致性必须由创建编排器在构造事实前完成并在同一事务中冻结。
 * 实时令牌和地址是敏感字段，
 * {@link #toString()} 不输出它们，也不输出会议标识、Profile、能力名称或其余
 * 响应正文；日志调用方可以安全记录该对象的结构摘要。</p>
 */
public final class CreateMeetingResponseFact {
    /** OpenAPI response 中固定的 WebSocket 子协议名称。 */
    public static final String REALTIME_SUBPROTOCOL = "czur.ai-meeting.realtime.v1";

    private final String meetingId;
    private final MeetingState state;
    private final CreateMeetingRequestSnapshot.ProfileRef acceptedProfile;
    private final Set<String> grantedCapabilities;
    private final List<CapabilityDecision> capabilityDecisions;
    private final AudioProfile acceptedAudioProfile;
    private final RealtimeSession realtime;
    private final Limits limits;

    /**
     * 创建并校验一份首版 Create 成功响应事实。
     *
     * @param meetingId 服务端生成的会议标识；必须符合公共会议标识策略
     * @param state 首次 Create 的状态；当前只能是 {@code CREATED}
     * @param acceptedProfile 服务端实际接受的 Profile 引用
     * @param grantedCapabilities 本场授权后可使用的能力集合
     * @param capabilityDecisions 本场能力计划的不可变决策列表
     * @param acceptedAudioProfile 服务端接受的音频格式
     * @param realtime 已真实分配的实时会话描述，不能使用占位 URL 或令牌
     * @param limits 本场执行限制
     * @throws NullPointerException 必填参数为空
     * @throws IllegalArgumentException 字段、集合或状态不符合协议
     */
    public CreateMeetingResponseFact(
            String meetingId,
            MeetingState state,
            CreateMeetingRequestSnapshot.ProfileRef acceptedProfile,
            Set<String> grantedCapabilities,
            List<CapabilityDecision> capabilityDecisions,
            AudioProfile acceptedAudioProfile,
            RealtimeSession realtime,
            Limits limits) {
        this.meetingId = requireResponseMeetingId(meetingId);
        if (state != MeetingState.CREATED) {
            throw new IllegalArgumentException(
                    "Create response fact requires CREATED state");
        }
        this.state = state;
        this.acceptedProfile = Objects.requireNonNull(acceptedProfile, "acceptedProfile");
        this.grantedCapabilities = immutableCapabilities(grantedCapabilities);
        this.capabilityDecisions = immutableDecisions(capabilityDecisions);
        this.acceptedAudioProfile = requireOpenApiAudioProfile(acceptedAudioProfile);
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** 返回服务端生成的会议标识；该值适用于响应编码，不应写入普通日志。 */
    public String meetingId() {
        return meetingId;
    }

    /** 返回已冻结的首次创建状态，首版固定为 {@link MeetingState#CREATED}。 */
    public MeetingState state() {
        return state;
    }

    /** 返回服务端实际接受的产品档位引用。 */
    public CreateMeetingRequestSnapshot.ProfileRef acceptedProfile() {
        return acceptedProfile;
    }

    /** 返回授权后的不可变能力集合。 */
    public Set<String> grantedCapabilities() {
        return grantedCapabilities;
    }

    /** 返回本场最终能力决策；列表元素本身也是不可变值对象。 */
    public List<CapabilityDecision> capabilityDecisions() {
        return capabilityDecisions;
    }

    /** 返回服务端接受的音频格式。 */
    public AudioProfile acceptedAudioProfile() {
        return acceptedAudioProfile;
    }

    /** 返回已经分配的实时会话地址、协议、令牌和过期时间。 */
    public RealtimeSession realtime() {
        return realtime;
    }

    /** 返回本场会议执行限制。 */
    public Limits limits() {
        return limits;
    }

    /**
     * 日志安全的结构摘要；不输出会议标识、Profile、能力名称、URL、令牌或时间。
     */
    @Override
    public String toString() {
        return "CreateMeetingResponseFact[state=" + state
                + ", grantedCapabilityCount=" + grantedCapabilities.size()
                + ", capabilityDecisionCount=" + capabilityDecisions.size()
                + ", realtimePresent=true, limitsPresent=true]";
    }

    private static Set<String> immutableCapabilities(Set<String> values) {
        Objects.requireNonNull(values, "grantedCapabilities");
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            copy.add(requireProtocolText("grantedCapabilities item", value));
        }
        return Collections.unmodifiableSet(copy);
    }

    /**
     * OpenAPI 对外会议标识比领域内部兼容标识更窄，必须满足 {@code mtg_...}
     * 的 ASCII 形式；先复用领域长度/UTF-8 校验，再执行公开协议的字符边界。
     */
    private static String requireResponseMeetingId(String value) {
        String meetingId = MeetingIdPolicy.requireValid(value);
        if (!meetingId.startsWith("mtg_") || meetingId.length() <= 4) {
            throw new IllegalArgumentException("meetingId must start with mtg_");
        }
        for (int index = 4; index < meetingId.length(); index++) {
            char character = meetingId.charAt(index);
            boolean asciiLetter = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z';
            boolean digit = character >= '0' && character <= '9';
            if (!asciiLetter && !digit && character != '_' && character != '-') {
                throw new IllegalArgumentException(
                        "meetingId contains a character outside the OpenAPI pattern");
            }
        }
        return meetingId;
    }

    private static List<CapabilityDecision> immutableDecisions(
            List<CapabilityDecision> values) {
        Objects.requireNonNull(values, "capabilityDecisions");
        TreeSet<String> seen = new TreeSet<>();
        for (CapabilityDecision value : values) {
            Objects.requireNonNull(value, "capabilityDecisions item");
            if (!seen.add(value.capability())) {
                throw new IllegalArgumentException(
                        "capabilityDecisions must not contain duplicate capabilities");
            }
        }
        return values.stream()
                .sorted((left, right) -> left.capability().compareTo(right.capability()))
                .toList();
    }

    /** Create response 的 AudioProfile 必须落在 OpenAPI 首版公开范围内。 */
    private static AudioProfile requireOpenApiAudioProfile(AudioProfile value) {
        Objects.requireNonNull(value, "acceptedAudioProfile");
        if (value.encoding() != AudioProfile.AudioEncoding.PCM_S16LE
                || value.sampleRateHz() != 16_000
                || value.channelCount() != 1
                || value.frameDurationMs() < 20
                || value.frameDurationMs() > 200) {
            throw new IllegalArgumentException(
                    "acceptedAudioProfile is outside OpenAPI limits");
        }
        return value;
    }

    private static String requireProtocolText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " contains line break");
        }
        return value;
    }

    /**
     * OpenAPI Create response 中的实时会话描述。
     *
     * <p>令牌不提供默认值，也不允许为空；会话分配端口必须先完成真实的
     * 身份/会议绑定再构造此值对象。过期时间只验证存在性，不与当前时钟比较，
     * 因为重放一份已经过期的历史响应仍然需要保留其原始事实。</p>
     *
     * @param url 可解析且为绝对 URI 的实时连接地址
     * @param subprotocol 必须为公共实时子协议
     * @param sessionToken 由实时会话端口签发的非空令牌
     * @param expiresAt 令牌过期时间
     */
    public record RealtimeSession(
            URI url,
            String subprotocol,
            String sessionToken,
            Instant expiresAt) {
        public RealtimeSession {
            Objects.requireNonNull(url, "realtime.url");
            if (!url.isAbsolute() || url.toString().indexOf('\r') >= 0
                    || url.toString().indexOf('\n') >= 0) {
                throw new IllegalArgumentException("realtime.url must be an absolute URI");
            }
            if (!REALTIME_SUBPROTOCOL.equals(subprotocol)) {
                throw new IllegalArgumentException(
                        "realtime.subprotocol must be " + REALTIME_SUBPROTOCOL);
            }
            requireProtocolText("realtime.sessionToken", sessionToken);
            Objects.requireNonNull(expiresAt, "realtime.expiresAt");
        }

        /** 避免令牌和地址进入诊断日志。 */
        @Override
        public String toString() {
            return "RealtimeSession[url=redacted, subprotocol=" + subprotocol
                    + ", sessionToken=redacted, expiresAtPresent=true]";
        }
    }

    /**
     * OpenAPI Create response 中冻结的执行限制。
     *
     * <p>{@code maxReplayAudioMs} 对应协议中的可选字段；{@code null} 表示服务端
     * 没有声明该项限制，而不是把缺省值猜成零。</p>
     */
    public record Limits(
            long maxMeetingDurationMs,
            int maxAudioFrameBytes,
            int eventReplayWindow,
            Long maxReplayAudioMs) {
        public Limits {
            if (maxMeetingDurationMs <= 0) {
                throw new IllegalArgumentException("limits.maxMeetingDurationMs must be positive");
            }
            if (maxAudioFrameBytes <= 0) {
                throw new IllegalArgumentException("limits.maxAudioFrameBytes must be positive");
            }
            if (eventReplayWindow <= 0) {
                throw new IllegalArgumentException("limits.eventReplayWindow must be positive");
            }
            if (maxReplayAudioMs != null && maxReplayAudioMs < 0) {
                throw new IllegalArgumentException("limits.maxReplayAudioMs must not be negative");
            }
        }

        /** 限制对象不含敏感文本，保持默认摘要安全。 */
        @Override
        public String toString() {
            return "Limits[maxMeetingDurationMs=" + maxMeetingDurationMs
                    + ", maxAudioFrameBytes=" + maxAudioFrameBytes
                    + ", eventReplayWindow=" + eventReplayWindow
                    + ", maxReplayAudioMs=" + maxReplayAudioMs + ']';
        }
    }
}
