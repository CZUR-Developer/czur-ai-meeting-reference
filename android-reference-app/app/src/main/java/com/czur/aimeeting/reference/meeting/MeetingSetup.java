package com.czur.aimeeting.reference.meeting;

import java.util.List;

/**
 * 用户在点击“开始会议”时冻结的本场会前设置。模型能力和结果交付能力彼此独立：
 * 前者进入 Create 的能力请求，后者留在 App 内决定网页或本地 PDF 交付。
 */
public record MeetingSetup(Mode mode, String sourceLanguage, String targetLanguage,
        boolean presentationCapture, ResultDelivery resultDelivery, String resultWebUrl) {
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

    public enum ResultDelivery { WEB, LOCAL, QR_CODE, EMAIL, TRACELESS }

    public static MeetingSetup transcript(String sourceLanguage, String resultWebUrl) {
        return new MeetingSetup(Mode.LIVE_TRANSCRIPT, sourceLanguage, "en-US", false,
                ResultDelivery.WEB, resultWebUrl);
    }

    /** 会前选择直接决定 Create 的请求能力；字幕是所有当前模式的基础产物。 */
    public List<String> requestedCapabilities() {
        return switch (mode) {
            case LIVE_TRANSCRIPT -> List.of(MeetingCapabilityState.TRANSCRIPT,
                    MeetingCapabilityState.SPEAKER);
            case MEETING_MINUTES -> List.of(MeetingCapabilityState.TRANSCRIPT,
                    MeetingCapabilityState.SUMMARY, MeetingCapabilityState.SPEAKER);
            case LIVE_TRANSLATION -> List.of(MeetingCapabilityState.TRANSCRIPT,
                    MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SPEAKER);
        };
    }

    public List<String> targetLanguages() {
        return mode == Mode.LIVE_TRANSLATION ? List.of(targetLanguage) : List.of();
    }
}
