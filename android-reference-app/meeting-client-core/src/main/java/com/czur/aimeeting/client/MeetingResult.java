package com.czur.aimeeting.client;

import java.util.Set;

/**
 * 同场真实READY产物事实。正常收口只返回清单，content／resultUrl为空，不承诺动作成功。
 * 正文由本场ResultActionAccess按需读取；交付字段与枚举仅保留旧公开接口适配。
 */
public record MeetingResult(String meetingId, String title, long genTime, Status status,
        Set<String> artifactTypes, MeetingResultContent content, String resultUrl,
        DeliveryStatus deliveryStatus) {
    public MeetingResult { artifactTypes = Set.copyOf(artifactTypes); }
    /** HTTP mapper解析真实清单／按需内容，不产生查看或通知成功状态。 */
    public MeetingResult(String meetingId, String title, long genTime, Status status,
            Set<String> artifactTypes, MeetingResultContent content) {
        this(meetingId, title, genTime, status, artifactTypes, content, null, DeliveryStatus.NOT_REQUESTED);
    }
    public enum Status { READY, READY_WITH_WARNINGS }
    /** 旧兼容字段；新宿主使用独立ResultActionOutcome，不参与会议阶段判断。 */
    public enum DeliveryStatus { NOT_REQUESTED, WEB_AVAILABLE, LOCAL_READY, EMAIL_SENT, EMAIL_FAILED }
    public MeetingResult withDelivery(String url, DeliveryStatus delivery) {
        return new MeetingResult(meetingId, title, genTime, status, artifactTypes, content, url, delivery);
    }
}
