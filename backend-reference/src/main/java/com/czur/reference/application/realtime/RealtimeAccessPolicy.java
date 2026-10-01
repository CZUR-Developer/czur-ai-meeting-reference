package com.czur.reference.application.realtime;

import com.czur.reference.application.port.InputState;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;

/**
 * 持久会议状态与实时会话用途之间的最小判定表。
 *
 * <p>本策略只解释已经从同一数据库快照取得的 {@code state/inputState}，不验证
 * Token、会议归属、流标识或过期时间。调用方必须先完成这些核验，不能把
 * {@link Decision#WRITE} 当作独立授权。判定与现有最终收口事实的合法组合保持
 * 一致，但把“事实合法”和“当前允许写音频”分开：例如
 * {@code AUTHORIZED + INPUT_OPEN} 是合法持久事实，本期仍不开放实时输入。</p>
 *
 * <p>{@link Decision#READ_ONLY} 仅表示已授权连接可以按持久事件游标补拉；它不允许
 * 新音频、新的完成声明或用旧 ACK 恢复写入。未知或不可能的状态组合返回
 * {@link Decision#FACT_INCONSISTENT}，由入口关闭式拒绝并记录脱敏故障原因，
 * 不得误当作普通只读会话。</p>
 */
public final class RealtimeAccessPolicy {
    private RealtimeAccessPolicy() {
    }

    /**
     * 只按同一持久快照中的两种状态分类；不访问模型、存储、网络或时钟。
     *
     * @param state 会议生命周期状态
     * @param inputState 会议级输入接管状态
     * @return 明确的写入、只读、拒绝或事实不一致决定
     */
    public static Decision classify(MeetingState state, InputState inputState) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(inputState, "inputState");
        return switch (inputState) {
            case INPUT_OPEN -> switch (state) {
                case CREATED, STARTING, RUNNING -> Decision.WRITE;
                case AUTHORIZED -> Decision.DENY;
                default -> Decision.FACT_INCONSISTENT;
            };
            case INPUT_COMPLETE_PENDING -> state == MeetingState.STOPPING
                    ? Decision.WRITE : Decision.FACT_INCONSISTENT;
            case INPUT_ACCEPTED -> switch (state) {
                case FINALIZING, READY, READY_WITH_WARNINGS, FAILED -> Decision.READ_ONLY;
                case DELETION_PENDING, DELETED -> Decision.DENY;
                default -> Decision.FACT_INCONSISTENT;
            };
            case INPUT_CANCELLED -> switch (state) {
                case CANCELLED -> Decision.READ_ONLY;
                case DELETION_PENDING, DELETED -> Decision.DENY;
                default -> Decision.FACT_INCONSISTENT;
            };
        };
    }

    /** 实时入口对已经完成凭据、归属与流身份核验的快照所作决定。 */
    public enum Decision {
        /** 允许同一逻辑流按后续持久游标规则写入；首次输入仍须完成 v2 Start。 */
        WRITE,
        /** 仅允许后续事件补拉，不允许新音频或新的完成声明。 */
        READ_ONLY,
        /** 合法会议事实，但本期或当前删除状态禁止建立实时写会话。 */
        DENY,
        /** 两种持久状态不可能组成已承诺的会议事实，应关闭式失败。 */
        FACT_INCONSISTENT
    }
}
