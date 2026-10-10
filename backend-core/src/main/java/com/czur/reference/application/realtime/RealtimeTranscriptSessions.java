package com.czur.reference.application.realtime;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.MeetingAudioFrame;

/**
 * 连接层所需的最窄字幕会话能力；实现负责从持久音频恢复、模型处理与事件发布。
 * WSS 只在持久 Start/绑定后 open，在 V015 Stored 后调用 acceptStored，并在连接
 * 关闭时释放会话。该端口不提供第二套音频 ACK 或事件游标。
 */
public interface RealtimeTranscriptSessions {
    Session open(String meetingId);

    interface Session extends AutoCloseable {
        /** 返回本次新落库事件；重复/已存事件返回 null，由后续事件补拉读取。 */
        EventLog acceptStored(MeetingAudioFrame frame);
        @Override void close();
    }
}
