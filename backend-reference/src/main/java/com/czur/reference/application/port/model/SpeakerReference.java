package com.czur.reference.application.port.model;

/**
 * 字幕事件中的发言人引用。
 *
 * <p>{@code sessionSpeakerId} 只代表本场临时发言人；跨会议人物 ID 和显示
 * 名称可能为空。Driver 不得把供应商声纹 ID 直接填入公共字段。</p>
 */
public record SpeakerReference(
        String sessionSpeakerId,
        String personId,
        String displayName) {

    public SpeakerReference {
        requireText("sessionSpeakerId", sessionSpeakerId);
        if (personId != null && personId.isBlank()) {
            throw new IllegalArgumentException("personId must not be blank");
        }
        if (displayName != null && displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
