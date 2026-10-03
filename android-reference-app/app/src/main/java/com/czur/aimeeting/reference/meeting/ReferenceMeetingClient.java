package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.TranscriptEvent;
import java.io.IOException;

/**
 * 参考 App 使用的最小会议客户端端口。页面只调用这些业务动作，不拼接 HTTP 协议；
 * 后续 SDK 提供自己的实现时，可替换此端口而不修改字幕页面。
 */
public interface ReferenceMeetingClient {
    /** 鉴权并创建一场参考 ASR 会议，返回后端分配的会议 ID。 */
    String createMeeting() throws IOException;

    /** 告知后端本场由 App 提交标准事件，并进入可接收字幕的状态。 */
    void startClient(String meetingId) throws IOException;

    /** 提交一次字幕修订；调用方负责按 clientSequence 顺序提交。 */
    void appendTranscript(String meetingId, int clientSequence, TranscriptEvent event)
            throws IOException;
}
