package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;
import com.czur.aimeeting.client.MeetingOptions;

import java.util.List;

/**
 * 用户在点击“开始会议”时冻结的本场会前设置。模型能力和结果交付能力彼此独立：
 * 前者进入 Create 的能力请求，后者留在 App 内决定网页或本地 PDF 交付。
 */
public record MeetingSetup(Mode mode, String sourceLanguage, String targetLanguage,
        boolean presentationCapture, ResultDelivery resultDelivery, String resultWebUrl,
        List<String> resultEmails) {
    public MeetingSetup(Mode mode, String sourceLanguage, String targetLanguage,
            boolean presentationCapture, ResultDelivery resultDelivery, String resultWebUrl) {
        this(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery,
                resultWebUrl, List.of());
    }

    public MeetingSetup {
        resultEmails = List.copyOf(resultEmails);
    }
    public enum Mode {
        LIVE_TRANSCRIPT("实时字幕", MeetingCapabilityState.TRANSCRIPT),
        MEETING_MINUTES("AI 会议纪要", MeetingCapabilityState.SUMMARY),
        LIVE_TRANSLATION("现场互译", MeetingCapabilityState.TRANSLATION);

        private final String displayName;
        private final String requiredCapability;

        Mode(String displayName, String requiredCapability) {
            this.displayName = displayName;
            this.requiredCapability = requiredCapability;
        }

        public String displayName() { return displayName; }

        /** 创建后的本场决策必须允许此能力，才可启动模型与计费。 */
        public String requiredCapability() { return requiredCapability; }
    }

    public enum ResultDelivery { WEB, LOCAL, EMAIL, TRACELESS }

    public static MeetingSetup transcript(String sourceLanguage, String resultWebUrl) {
        return new MeetingSetup(Mode.LIVE_TRANSCRIPT, sourceLanguage, "en-US", false,
                ResultDelivery.WEB, resultWebUrl, List.of());
    }

    /** 会前选择直接决定 Create 的请求能力；字幕是所有当前模式的基础产物。 */
    public List<String> requestedCapabilities() {
        return toOptions().requestedCapabilities();
    }

    public List<String> targetLanguages() {
        return toOptions().targetLanguages();
    }

    /** P6-6 迁移期将旧 UI 快照转成唯一 core 业务类型，P6-7 会直接使用它。 */
    public MeetingOptions toOptions() {
        return new MeetingOptions(MeetingOptions.Mode.valueOf(mode.name()), sourceLanguage,
                targetLanguage, presentationCapture,
                MeetingOptions.ResultDelivery.valueOf(resultDelivery.name()), resultWebUrl, resultEmails);
    }
}
