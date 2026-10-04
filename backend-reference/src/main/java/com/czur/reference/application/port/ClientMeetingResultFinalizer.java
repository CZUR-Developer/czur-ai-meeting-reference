package com.czur.reference.application.port;

/**
 * 参考 App 事件会议的同步结果收口入口。
 *
 * <p>返回 {@code false} 表示该会议属于原有后端音频链路，应继续交给旧收口器；
 * 返回 {@code true} 表示客户端链路已处理；Stop 后不再接收 append，已收到的
 * 最终字幕会被冻结，未完成片段会被跳过。完全没有最终字幕时仍产生空产物清单。
 * 实现只能读取已持久化的标准事件，不从 App 的 Stop 请求正文获取最终文字。</p>
 */
@FunctionalInterface
public interface ClientMeetingResultFinalizer {
    boolean finalizeIfClient(String meetingId);
}
