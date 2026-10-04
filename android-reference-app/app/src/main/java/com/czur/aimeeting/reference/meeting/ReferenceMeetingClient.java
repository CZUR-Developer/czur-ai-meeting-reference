package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.io.IOException;

/**
 * 参考 App 使用的最小会议客户端端口。页面只调用这些业务动作，不拼接 HTTP 协议；
 * 后续 SDK 提供自己的实现时，可替换此端口而不修改字幕页面。
 */
public interface ReferenceMeetingClient {
    /** 鉴权并创建会议，返回会议 ID 与后端冻结的能力决策。 */
    CreatedMeeting createMeeting(MeetingSetup setup) throws IOException;

    /** 告知后端本场由 App 提交标准事件，并进入可接收字幕的状态。 */
    void startClient(String meetingId) throws IOException;

    /** 单独领取本场模型直连材料；旧 Create 响应的 realtime 字段不属于模型。 */
    ModelSessionGrant requestModelSession(String meetingId) throws IOException;

    /** 提交一次字幕修订；调用方负责按 clientSequence 顺序提交。 */
    void appendTranscript(String meetingId, int clientSequence, TranscriptEvent event)
            throws IOException;

    /** 与字幕共用 clientSequence，提交已归一化译文；仅本场获准互译时调用。 */
    void appendTranslation(String meetingId, int clientSequence, TranslationEvent event)
            throws IOException;

    /**
     * 请求结束会议。返回 true 表示后端已受理完整输入（HTTP 200）；
     * 返回 false 表示 Stop 已记录但仍在收口（HTTP 202）。
     */
    boolean stopMeeting(String meetingId) throws IOException;
}
