package com.czur.aimeeting.client;

import java.util.List;
import java.util.ArrayList;

/**
 * 本场会前快照。mode决定会中字幕／互译呈现，summaryEnabled独立决定会后是否生成纪要。
 * 模型能力请求与结果交付相互独立，不接受模型地址或短期材料。
 * 旧MEETING_MINUTES模式及七参数构造器保留，语义为字幕＋总结；新App使用两种呈现模式加开关。
 */
public record MeetingOptions(Mode mode, String sourceLanguage, String targetLanguage,
        boolean presentationCapture, ResultDelivery resultDelivery, String resultWebUrl,
        List<String> resultEmails, boolean summaryEnabled) {
    /** 兼容既有模式调用：仅旧MEETING_MINUTES默认开启总结。 */
    public MeetingOptions(Mode mode, String sourceLanguage, String targetLanguage,
            boolean presentationCapture, ResultDelivery resultDelivery, String resultWebUrl,
            List<String> resultEmails) {
        this(mode, sourceLanguage, targetLanguage, presentationCapture, resultDelivery,
                resultWebUrl, resultEmails, mode == Mode.MEETING_MINUTES);
    }

    public MeetingOptions {
        resultEmails = List.copyOf(resultEmails);
        // 旧枚举始终表示纪要，避免新构造器把相同旧模式解释为“关闭总结”。
        summaryEnabled = summaryEnabled || mode == Mode.MEETING_MINUTES;
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
        public String requiredCapability() { return requiredCapability; }
    }
    public enum ResultDelivery { WEB, LOCAL, EMAIL, TRACELESS }

    public List<String> requestedCapabilities() {
        List<String> requested = new ArrayList<>(requiredCapabilities());
        requested.add(MeetingCapabilityState.SPEAKER);
        return List.copyOf(requested);
    }

    /**
     * 用户明确选择的功能均为本场必需能力。互译＋总结同时要求两项；发言人仍是可选项。
     * 页面用部署交集开放选项，SDK再以Create本场决策确认，未授权时不启动模型。
     */
    public List<String> requiredCapabilities() {
        List<String> required = new ArrayList<>();
        required.add(MeetingCapabilityState.TRANSCRIPT);
        if (mode == Mode.LIVE_TRANSLATION) required.add(MeetingCapabilityState.TRANSLATION);
        if (summaryEnabled) required.add(MeetingCapabilityState.SUMMARY);
        return List.copyOf(required);
    }
    public List<String> targetLanguages() {
        return mode == Mode.LIVE_TRANSLATION ? List.of(targetLanguage) : List.of();
    }
}
