package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;
import java.util.List;

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

    /**
     * 在停止采音后提交本场冻结的累计采音时长，单位为整数毫秒。
     *
     * <p>调用方从实际标准PCM字节数计算，16kHz、单声道、PCM16采用字节数除以32；
     * 静音帧计入，模型重发和总结等待不计入。零表示已确认没有采到音频，未知用量
     * 不调用此方法。实现一次持久冻结用量与对应Reservation版本，相同值可重放，
     * 不同值不能覆盖。后续Stop读取同一事实结算，不能改用墙钟或字幕末尾。</p>
     *
     * @param context 已认证的会议归属用户上下文
     * @param meetingId 已启动客户端事件输入的同场会议
     * @param audioDurationMs 已停止采音后冻结的累计值，不得为负数
     * @return 首次冻结、同值重放或现有输入拒绝结果
     */
    default UsageSubmitResult submitUsage(MeetingCommandContext context,
            String meetingId, long audioDurationMs) {
        return new Rejected(Reason.STORAGE_UNAVAILABLE);
    }

    sealed interface UsageSubmitResult permits UsageSubmitted, Rejected { }

    /** created=false表示已有同值事实，后续结算仍使用首次冻结的版本和身份。 */
    record UsageSubmitted(long audioDurationMs, boolean created) implements UsageSubmitResult { }

    /** Stop 前提交本场唯一的标准纪要；相同正文重放安全，不能用新正文覆盖。 */
    default SummarySubmitResult submitSummary(MeetingCommandContext context,
            String meetingId, StructuredSummary summary) {
        return new Rejected(Reason.STORAGE_UNAVAILABLE);
    }

    record StructuredSummary(String language, String title, String overview,
            List<String> keyPoints, List<String> actionItems) {
        public StructuredSummary {
            if (language == null || language.isBlank() || language.length() > 64
                    || title == null || title.isBlank() || title.length() > 512
                    || overview == null || overview.isBlank() || overview.length() > 16_384) {
                throw new IllegalArgumentException("summary text is invalid");
            }
            keyPoints = checkedItems(keyPoints);
            actionItems = checkedItems(actionItems);
        }

        private static List<String> checkedItems(List<String> items) {
            if (items == null || items.size() > 100 || items.stream().anyMatch(
                    item -> item == null || item.isBlank() || item.length() > 4096)) {
                throw new IllegalArgumentException("summary items are invalid");
            }
            return List.copyOf(items);
        }
    }

    sealed interface SummarySubmitResult permits SummarySubmitted, Rejected { }

    record SummarySubmitted(boolean created) implements SummarySubmitResult { }

    /** 上传一个不可变的有序演示截图包；正文为规范化 JSON，内容由 ArtifactStore 保存。 */
    default CaptureAppendResult appendPresentation(MeetingCommandContext context,
            String meetingId, PresentationChunk request) {
        return new Rejected(Reason.STORAGE_UNAVAILABLE);
    }

    record PresentationChunk(int chunkSequence, int frameCount, byte[] content) {
        public PresentationChunk {
            if (chunkSequence <= 0 || frameCount <= 0 || content == null || content.length == 0) {
                throw new IllegalArgumentException("presentation chunk is invalid");
            }
            content = content.clone();
        }

        @Override public byte[] content() { return content.clone(); }
    }

    sealed interface CaptureAppendResult permits CaptureAppended, Rejected { }

    record CaptureAppended(String artifactId, int chunkSequence, int frameCount,
            boolean created) implements CaptureAppendResult { }

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
    record Rejected(Reason reason) implements StartResult, AppendResult,
            CaptureAppendResult, SummarySubmitResult, UsageSubmitResult {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    enum Reason { NOT_FOUND, ACCESS_DENIED, INVALID_STATE, INPUT_SOURCE_CONFLICT,
        EVENT_CONFLICT, CAPABILITY_NOT_GRANTED, SPEAKER_NOT_GRANTED,
        PRESENTATION_NOT_REQUESTED, SUMMARY_NOT_GRANTED,
        STORAGE_UNAVAILABLE }
}
