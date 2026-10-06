package com.czur.aimeeting.client;

/** 已由部署方配置的会议档位引用；本场能力仍以 Server 的 Create 响应为准。 */
public record MeetingProfile(String id, int revision) {
    public static MeetingProfile reference() { return new MeetingProfile("reference-asr", 1); }
}
