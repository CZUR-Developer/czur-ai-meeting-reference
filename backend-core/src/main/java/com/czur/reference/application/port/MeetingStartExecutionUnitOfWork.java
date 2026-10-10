package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.ProductProfileSnapshot;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 在会议开始前冻结执行含义并原子推进 owned Meeting 的本地工作单元。
 *
 * <p>首次成功必须在同一个本地数据库事务中完成以下事实：锁定 Meeting，核对
 * 部署、主体、Profile 和首次权益预占。旧 v1 创建链使用首次授权事实运行
 * Capability Planner；完整 v2 Create 链以 V013 已冻结的 revision 1 计划为权威，
 * 用当前能力重新计算只作可执行性核对。然后写入不可变的
 * {@link FinalizationExecutionFact}，最后把
 * Meeting 推进到 STARTING。任何一步失败都必须整体回滚，不能出现“已开始但没有
 * execution fact”或“有 execution fact 但仍未开始”的半状态。</p>
 *
 * <p>当前 owned 创建链把已经完成身份与权益预占的会议保存为 CREATED，因此实现
 * 必须在 CREATED 首次路径中依次应用 AUTHORIZE 和 START；未来创建链若直接保存为
 * AUTHORIZED，则只应用 START。两个转换都使用请求显式提供的 {@code startedAt}，
 * 不得使用 {@code createdAt}、{@code updatedAt}、当前时钟或数据库默认时间代替。</p>
 *
 * <p>重放时，已经保存的 execution fact 是历史会议的权威执行含义。实现必须先读
 * 该事实，并核对请求中的 startedAt、title 和完整 Product Profile 快照；事实相等
 * 时直接返回其中已经冻结的 Capability Plan，不得按当前 Provider 或 deployment
 * limits 重新规划。STARTING、RUNNING 及后续状态也不得再次应用 START。</p>
 *
 * <p>该端口不调用模型、对象存储、权益 Provider 或消息系统，也不追加开始事件
 * Outbox。当前没有消费 MeetingStarted 的持久工作流；最终收口由后续 admission
 * 创建的 workflow fact 驱动。若未来出现真实异步消费者，应另行冻结事件契约并在
 * 本事务中追加，不能先发布一个没有消费者语义的占位事件。</p>
 */
public interface MeetingStartExecutionUnitOfWork {

    /**
     * 注册或重放旧 v1 创建链会议的开始执行事实。
     *
     * @param request 已由可信应用层组装、且不包含 Token、密码或模型密钥的完整输入
     * @return 首次开始、稳定重放或固定错误分类
     */
    Result start(StartRequest request);

    /**
     * 开始由完整 v2 Create 创建的会议。
     *
     * <p>可信应用层必须显式选择此入口，不能根据客户端标志或权益键的外形
     * 猜测来源。首次路径强制在同一事务读取并验证 V013 首次响应；缺行、损坏
     * 或当前能力计划漂移均关闭式失败，不会退回旧 v1 规划。已有 V011 执行事实
     * 时仍优先按历史事实重放，不读取 V013 或重算计划。此入口要求部署 V013；
     * 旧 v1 调用方继续使用 {@link #start(StartRequest)}。</p>
     *
     * @param request 已由可信应用层组装的开始输入，不包含凭据或模型密钥
     * @return 首次开始、历史重放或固定错误分类
     */
    Result startCompleteCreate(StartRequest request);

    /**
     * 会议开始事务的显式输入。
     *
     * <p>首次规划时，{@code requestedCapabilities} 必须与首次 reserve 持久化的
     * 授权快照完全相同；实现不能直接信任该集合，也不能接收调用方另行声明的
     * granted 集合。granted 能力只能从数据库中的首次授权事实取得。
     * {@code requestedCapabilities}、{@code modelCapabilities} 和
     * {@code deploymentLimits} 只用于执行事实不存在时的旧 v1 首次规划或完整 v2
     * 首次可执行性核对；已存在 execution
     * fact 的重放直接返回已冻结 Plan，不依赖旧 reserve 行仍然可读。</p>
     *
     * <p>{@code startedAt} 必须已经是 PostgreSQL {@code timestamptz(6)} 可无损
     * 保存的微秒精度值。title 显式允许为 null；空字符串不是 null，会作为真实标题
     * 参与 execution fact 的严格相等比较。</p>
     */
    record StartRequest(
            String deploymentScope,
            String subjectScope,
            String meetingId,
            Instant startedAt,
            String title,
            ProductProfile productProfile,
            Set<String> requestedCapabilities,
            CapabilityDescriptor modelCapabilities,
            DeploymentLimits deploymentLimits) {

        public StartRequest {
            requireText("deploymentScope", deploymentScope);
            subjectScope = MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            meetingId = MeetingIdPolicy.requireValid(meetingId);
            requireStartedAt(startedAt);
            Objects.requireNonNull(productProfile, "productProfile");
            // 在命令进入数据库前完成完整 Profile 与严格 UTF-8 校验；快照本身不被
            // 缓存在请求中，避免形成第二份可能与调用方字段漂移的数据来源。
            ProductProfileSnapshot.from(productProfile);
            requestedCapabilities = immutableCapabilities(requestedCapabilities);
            Objects.requireNonNull(modelCapabilities, "modelCapabilities");
            deploymentLimits = deploymentLimits == null
                    ? DeploymentLimits.NONE : deploymentLimits;
            if (title != null) {
                MeetingIdPolicy.utf8Length(title);
                if (title.codePointCount(0, title.length()) > 256) {
                    throw new IllegalArgumentException("title exceeds 256 code points");
                }
            }
        }

        /** 请求诊断不输出会议、主体、标题、能力集合或 Provider 标识。 */
        @Override
        public String toString() {
            return "MeetingStartExecutionUnitOfWork.StartRequest[redacted]";
        }
    }

    /** 首次成功与失败的封闭结果集合。 */
    sealed interface Result permits Success, Failure {
    }

    /**
     * 已确认的事务结果。
     *
     * <p>STARTED 表示本次事务首次同时提交 execution fact 和 STARTING 状态；
     * REPLAYED 表示数据库已存在等价事实，返回的 meeting 可以处于 STARTING、
     * RUNNING 或更晚状态。结果中的 executionFact 始终是数据库权威赢家。</p>
     */
    record Success(
            SuccessCode code,
            MeetingAggregate.MeetingSnapshot meeting,
            FinalizationExecutionFact executionFact) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(meeting, "meeting");
            Objects.requireNonNull(executionFact, "executionFact");
            if (!meeting.meetingId().equals(executionFact.meetingId())) {
                throw new IllegalArgumentException(
                        "meeting and executionFact must use the same meetingId");
            }
            if (!meeting.profileId().equals(executionFact.productProfile().id())
                    || meeting.profileRevision()
                    != executionFact.productProfile().revision()) {
                throw new IllegalArgumentException(
                        "meeting and executionFact must use the same profile");
            }
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                    meeting.subjectScope())) {
                throw new IllegalArgumentException("start success requires an owned meeting");
            }
            if (code == SuccessCode.STARTED) {
                if (meeting.state() != MeetingState.STARTING) {
                    throw new IllegalArgumentException(
                            "STARTED result requires STARTING meeting state");
                }
                if (!meeting.updatedAt().equals(executionFact.startedAt())) {
                    throw new IllegalArgumentException(
                            "STARTED result requires the frozen start time");
                }
            }
            if (code == SuccessCode.REPLAYED
                    && (meeting.state() == MeetingState.UNKNOWN
                    || meeting.state() == MeetingState.CREATED
                    || meeting.state() == MeetingState.AUTHORIZED)) {
                throw new IllegalArgumentException(
                        "REPLAYED result requires a post-START meeting state");
            }
        }

        /** 避免 record 默认输出会议归属、标题、Profile 和能力计划。 */
        @Override
        public String toString() {
            return "MeetingStartExecutionUnitOfWork.Success[code=" + code
                    + ", meetingState=" + meeting.state() + ", fact=redacted]";
        }
    }

    /** 不携带 SQL、连接地址、主体、Profile 正文或动态异常消息的固定失败。 */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (retryable != code.retryable()) {
                throw new IllegalArgumentException(
                        "retryable must match the stable error-code policy");
            }
        }

        public Failure(ErrorCode code) {
            this(Objects.requireNonNull(code, "code"), code.retryable());
        }
    }

    enum SuccessCode {
        STARTED,
        REPLAYED
    }

    /**
     * 会议开始事实的稳定错误集合。
     *
     * <p>PROFILE_MISMATCH 同时覆盖 Meeting、首次授权快照与完整 Profile 的 id/
     * revision 不一致；AUTHORIZATION_INCONSISTENT 表示 requested/granted 或绑定
     * Reservation 无法证明；FACT_INCONSISTENT 表示状态与 execution fact 的原子
     * 关系被破坏。只有临时存储不可用和提交结果未知允许自动重试。</p>
     */
    enum ErrorCode {
        MEETING_NOT_FOUND(false),
        ACCESS_DENIED(false),
        PROFILE_MISMATCH(false),
        AUTHORIZATION_INCONSISTENT(false),
        CREATE_FACT_INCONSISTENT(false),
        CAPABILITY_PLAN_FAILED(false),
        EXECUTION_FACT_CONFLICT(false),
        FACT_INCONSISTENT(false),
        START_TIME_CONFLICT(false),
        INVALID_MEETING_STATE(false),
        STORAGE_UNAVAILABLE(true),
        OUTCOME_UNKNOWN(true),
        INTERNAL(false);

        private final boolean retryable;

        ErrorCode(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static Set<String> immutableCapabilities(Set<String> values) {
        Objects.requireNonNull(values, "requestedCapabilities");
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            requireText("requested capability", value);
            MeetingIdPolicy.utf8Length(value);
            copy.add(value);
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("requestedCapabilities must not be empty");
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** 与 execution fact 共用四位年份和 PostgreSQL 微秒精度的协议值域。 */
    private static void requireStartedAt(Instant value) {
        Objects.requireNonNull(value, "startedAt");
        Instant minimum = Instant.parse("0000-01-01T00:00:00Z");
        Instant maximum = Instant.parse("9999-12-31T23:59:59.999999Z");
        if (value.isBefore(minimum)
                || value.isAfter(maximum)
                || value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "startedAt must be a four-digit year at microsecond precision");
        }
    }
}
