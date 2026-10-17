package com.czur.reference.application.port.speaker;

import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 可选的会后跨会议人物身份插件，与实时 ASR 的本场分人能力分开装配。
 *
 * <p>核心在 Stop 已冻结输入、但尚未取得会议行锁时调用 {@link #identify}；
 * 注册入口先完成认证、归属与能力检查，再在事务外调用 {@link #enroll}。
 * 实现可读取请求的同场录音、切片并访问供应商。调用阻塞，因此只能在后端工作
 * 线程执行，不应持有调用方连接、音频流或请求身份。实现由宿主管理生命周期。</p>
 *
 * <p>供应商分组／特征引用是插件私有事实；返回的 personId 必须是跨会议稳定的
 * 标准人物编号。本场编号不能作为人物编号。无匹配返回空映射，不伪造人物；
 * 识别失败由核心降级为未识别，不阻断字幕／总结／录音 READY。未装配插件时
 * 不声明 {@code speaker.identity.cross_meeting}，也不访问插件人员库。</p>
 */
public interface SpeakerIdentityProvider {
    /** 正常会议可能没有足够单人语音；注册可提示补充样本，识别保持未匹配。 */
    final class SampleUnavailable extends Exception {
        public SampleUnavailable(String message) { super(message); }
    }

    /** 声明插件真实实现的能力，不包含用户权益或实时模型材料。 */
    CapabilityDescriptor descriptor();

    /** 仅返回已匹配的本场 speaker；不存在的键表示暂未识别。 */
    Identification identify(MeetingSpeakers request) throws Exception;

    /**
     * 用选定发言人的同场音频注册或更新人物。名称属于调用方业务输入；插件负责
     * 稳定人物与私有供应商特征映射，并使重复注册已知人物仍返回同一 personId。
     */
    Person enroll(Enrollment request) throws Exception;

    /** 已由核心核对过归属、冻结事件与录音登记的不可变输入；不包含 Token。 */
    record MeetingSpeakers(String deploymentScope, String subjectScope, String meetingId,
            ArtifactDescriptor audio, List<SpeakerSample> speakers) {
        public MeetingSpeakers {
            Objects.requireNonNull(deploymentScope, "deploymentScope");
            Objects.requireNonNull(subjectScope, "subjectScope");
            Objects.requireNonNull(meetingId, "meetingId");
            Objects.requireNonNull(audio, "audio");
            speakers = List.copyOf(speakers);
        }
    }

    /** 时间窗只来自该 speaker 的最终原文；样本选择与供应商格式转换由插件负责。 */
    record SpeakerSample(String sessionSpeakerId, List<AudioWindow> windows) {
        public SpeakerSample {
            if (sessionSpeakerId == null || sessionSpeakerId.isBlank()) {
                throw new IllegalArgumentException("session speaker is required");
            }
            windows = List.copyOf(windows);
        }
    }

    /** 相对同场录音开头的毫秒时间窗，右端不包含；不携带识别正文。 */
    record AudioWindow(long startMs, long endMs) {
        public AudioWindow {
            if (startMs < 0 || endMs <= startMs) {
                throw new IllegalArgumentException("speaker audio window is invalid");
            }
        }
    }

    /** 标准人物信息；供应商 featureId、相似度或采样细节不进入公共结果。 */
    record Person(String personId, String displayName) {
        public Person {
            if (personId == null || personId.isBlank()
                    || displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("person id and display name are required");
            }
        }
    }

    /** 只包含有效匹配；未识别的人不写入映射。 */
    record Identification(Map<String, Person> matches) {
        public Identification { matches = Map.copyOf(matches); }
    }

    /**
     * 会议已 READY 后的命名注册。existingPerson 可空；存在时插件更新对应人物，
     * 不应重复创建人物。供应商通信完成后核心才冻结新的身份产物与清单修订。
     */
    record Enrollment(MeetingSpeakers meeting, String sessionSpeakerId,
            String displayName, Person existingPerson) {
        public Enrollment {
            Objects.requireNonNull(meeting, "meeting");
            if (sessionSpeakerId == null || sessionSpeakerId.isBlank()
                    || displayName == null || displayName.isBlank()
                    || displayName.codePointCount(0, displayName.length()) > 128) {
                throw new IllegalArgumentException("speaker enrollment is invalid");
            }
        }
    }
}
