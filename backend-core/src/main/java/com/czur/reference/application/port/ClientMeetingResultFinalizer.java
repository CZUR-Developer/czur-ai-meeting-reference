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

    /**
     * 读取持久待办并按登记原因收口；没有待办时回落为普通客户端收口。
     *
     * <p>后台扫描只对已落待办的会议调用本入口。默认实现供旧装配与测试替身
     * 保持原行为；JDBC 实现会在结果事务内删除待办，重复调用按结果存在性幂等。</p>
     *
     * @param meetingId 已有待办或需要普通客户端收口的会议标识
     * @return true 表示本场已收口或由弃场链接管；false 表示输入事实尚不满足
     */
    default boolean finalizePendingClient(String meetingId) {
        return finalizeIfClient(meetingId);
    }
}
