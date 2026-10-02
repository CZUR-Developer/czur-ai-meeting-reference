package com.czur.reference.application.realtime;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;

import java.util.Objects;

/**
 * 将 Mock Driver 的规范字幕按持久帧身份发布为唯一会议事件的应用端口。
 *
 * <p>eventId 稳定绑定会议、分段和修订，允许完成声明产生没有新音频帧的 final。
 * 实现须在同一会议行锁下先按 eventId 找赢家；重放返回原 payload、序号和
 * 时间，不以新时钟或新序号重新构造。新事件才分配会议全局序号并提交；异常表示
 * 没有可推送的字幕，调用方关闭本 Mock session，之后从 V015 重放恢复。</p>
 */
public interface RealtimeTranscriptEventPublisher {
    Published publish(String meetingId, CanonicalTranscriptEvent transcript);

    record Published(EventLog event, boolean newlyAppended) {
        public Published { Objects.requireNonNull(event, "event"); }
    }
}
