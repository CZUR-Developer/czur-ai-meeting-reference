package com.czur.aimeeting.client;

import java.util.Set;

/** 类型化的就绪结果。WEB/EMAIL 仅需清单与链接信息，content 可为 null，不下载全部截图。 */
public record MeetingResult(String meetingId, String title, long genTime, Status status,
        Set<String> artifactTypes, MeetingResultContent content) {
    public MeetingResult { artifactTypes = Set.copyOf(artifactTypes); }
    public enum Status { READY, READY_WITH_WARNINGS }
}
