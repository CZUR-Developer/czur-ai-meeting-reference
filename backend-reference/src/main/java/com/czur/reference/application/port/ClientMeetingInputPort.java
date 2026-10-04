package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;

/**
 * Android 直连模型后向参考后端提交标准事件的最小入口。
 *
 * <p>App 只提交客户端事件身份和规范化字幕或译文；服务端负责会议归属、输入来源、
 * 全局事件序号及完整 ServerEnvelope。此端口不接收音频或模型厂商原始响应。
 * 无客户端输入标记的旧会议仍使用 WSS 音频链路。</p>
 */
public interface ClientMeetingInputPort {
    /** 首次启动客户端事件链路，或读取同一链路已完成的启动。 */
    StartResult start(MeetingCommandContext context, String meetingId);

    /**
     * 按客户端稳定身份与连续顺序提交一条标准字幕或译文。译文须经本场冻结能力
     * 决策许可；实现分配权威事件 ID/序号并保存完整 ServerEnvelope。
     */
    AppendResult append(MeetingCommandContext context, String meetingId,
            ClientEvent request);

    /** 两种事件共用客户端去重身份和顺序；内容保持强类型。 */
    sealed interface ClientEvent permits ClientTranscript, ClientTranslation {
        String clientEventId();
        long clientSequence();
    }

    /** 客户端顺序和 ID 只用于去重；权威事件序号由后端另行分配。 */
    record ClientTranscript(String clientEventId, long clientSequence,
            CanonicalTranscriptEvent transcript) implements ClientEvent {
        public ClientTranscript {
            if (clientEventId == null || clientEventId.isBlank()
                    || clientEventId.length() > 128) {
                throw new IllegalArgumentException("clientEventId is invalid");
            }
            if (clientSequence <= 0) {
                throw new IllegalArgumentException("clientSequence must be positive");
            }
            Objects.requireNonNull(transcript, "transcript");
        }
    }

    /** 与字幕共享同一序号空间的标准译文修订。 */
    record ClientTranslation(String clientEventId, long clientSequence,
            CanonicalTranslationEvent translation) implements ClientEvent {
        public ClientTranslation {
            if (clientEventId == null || clientEventId.isBlank()
                    || clientEventId.length() > 128) {
                throw new IllegalArgumentException("clientEventId is invalid");
            }
            if (clientSequence <= 0) {
                throw new IllegalArgumentException("clientSequence must be positive");
            }
            Objects.requireNonNull(translation, "translation");
        }
    }

    sealed interface StartResult permits Started, Rejected { }

    /** 持久客户端输入标记已存在；状态来自会议权威快照。 */
    record Started(MeetingState state) implements StartResult {
        public Started { Objects.requireNonNull(state, "state"); }
    }

    sealed interface AppendResult permits Appended, Rejected { }

    /** created=false 表示同一客户端事件的重放，不另增会议事件序号。 */
    record Appended(EventLog event, boolean created) implements AppendResult {
        public Appended { Objects.requireNonNull(event, "event"); }
    }

    /** 当前会议不可接受客户端事件；不会包含身份或正文。 */
    record Rejected(Reason reason) implements StartResult, AppendResult {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    enum Reason { NOT_FOUND, ACCESS_DENIED, INVALID_STATE, INPUT_SOURCE_CONFLICT,
        EVENT_CONFLICT, CAPABILITY_NOT_GRANTED, STORAGE_UNAVAILABLE }
}
