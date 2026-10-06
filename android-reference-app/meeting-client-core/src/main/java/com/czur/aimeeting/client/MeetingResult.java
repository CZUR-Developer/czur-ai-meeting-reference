package com.czur.aimeeting.client;

import java.util.Set;

/** 类型化的就绪结果。WEB/EMAIL 仅需清单与链接信息，content 可为 null，不下载全部截图。 */
public record MeetingResult(String meetingId, String title, long genTime, Status status,
        Set<String> artifactTypes, MeetingResultContent content, String resultUrl,
        DeliveryStatus deliveryStatus) {
    public MeetingResult { artifactTypes = Set.copyOf(artifactTypes); }
    /** HTTP mapper 只解析清单/内容，具体交付由唯一 Coordinator 填充。 */
    public MeetingResult(String meetingId, String title, long genTime, Status status,
            Set<String> artifactTypes, MeetingResultContent content) {
        this(meetingId, title, genTime, status, artifactTypes, content, null, DeliveryStatus.NOT_REQUESTED);
    }
    public enum Status { READY, READY_WITH_WARNINGS }
    public enum DeliveryStatus { NOT_REQUESTED, WEB_AVAILABLE, LOCAL_READY, EMAIL_SENT, EMAIL_FAILED }
    public MeetingResult withDelivery(String url, DeliveryStatus delivery) {
        return new MeetingResult(meetingId, title, genTime, status, artifactTypes, content, url, delivery);
    }
}
