package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.time.Instant;
import java.util.Objects;

/**
 * 只读取得一次会议输入收口所需的可信上下文。
 *
 * <p>持久实现须从同一一致性快照核对 Meeting、V013 首次 Create 和 V011 冻结
 * Start：部署/主体归属、Profile、源语言、能力计划及会议状态必须相符。此端口不从
 * 当前配置、客户端完成声明或 WSS Token 补齐事实，也不写库。参考 P2 只支持
 * ASR-only：翻译能力若被有效计划启用，返回 {@link Inconsistent}，不能把请求的
 * 目标语言误写进最终事实。</p>
 *
 * <p>{@link WaitingForStop} 表示 Stop 尚未提交；{@link Found} 只允许表示
 * STOPPING + INPUT_COMPLETE_PENDING 或已完成 Admission 的 INPUT_ACCEPTED。
 * 后者的 {@code meetingUpdatedAt} 可能晚于接管时间，重放必须另读 V008 赢家，
 * 不得用该时间或当前时钟重建已提交输入。</p>
 */
public interface MeetingInputClosureContextPort {
    /** @return 同一持久快照裁决的上下文；临时存储错误不得伪装成等待 Stop。 */
    Result load(String meetingId);

    sealed interface Result permits Found, WaitingForStop, Missing, Inconsistent, Unavailable { }

    record Found(Context context) implements Result {
        public Found { Objects.requireNonNull(context, "context"); }
        @Override public String toString() { return "Found[context=redacted]"; }
    }

    record WaitingForStop() implements Result { }
    record Missing() implements Result { }
    record Inconsistent() implements Result { }
    record Unavailable() implements Result { }

    /**
     * 已通过 Meeting/V013/V011 联合核验的冻结值；不携带凭据或模型正文。
     * {@code meetingUpdatedAt} 在 pending 状态是 Stop 提交时间的下界。
     */
    record Context(String deploymentScope, String subjectScope, String meetingId,
                   InputState inputState, Instant meetingUpdatedAt,
                   String sourceLanguage) {
        public Context {
            requireText("deploymentScope", deploymentScope);
            subjectScope = MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            meetingId = MeetingIdPolicy.requireValid(meetingId);
            Objects.requireNonNull(inputState, "inputState");
            if (inputState != InputState.INPUT_COMPLETE_PENDING
                    && inputState != InputState.INPUT_ACCEPTED) {
                throw new IllegalArgumentException("input state is not closing");
            }
            Objects.requireNonNull(meetingUpdatedAt, "meetingUpdatedAt");
            if (meetingUpdatedAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException("meetingUpdatedAt must use microseconds");
            }
            requireText("sourceLanguage", sourceLanguage);
        }

        @Override public String toString() {
            return "Context[inputState=" + inputState + ", values=redacted]";
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
