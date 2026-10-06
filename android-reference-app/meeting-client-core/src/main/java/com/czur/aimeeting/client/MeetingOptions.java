package com.czur.aimeeting.client;

import java.util.List;

/** 本场会前设置；模型能力请求与结果交付相互独立，不接受模型地址或短期材料。 */
public record MeetingOptions(Mode mode, String sourceLanguage, String targetLanguage,
        boolean presentationCapture, ResultDelivery resultDelivery, String resultWebUrl,
        List<String> resultEmails) {
    public MeetingOptions { resultEmails = List.copyOf(resultEmails); }

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
        public String requiredCapability() { return requiredCapability; }
    }
    public enum ResultDelivery { WEB, LOCAL, EMAIL, TRACELESS }

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
