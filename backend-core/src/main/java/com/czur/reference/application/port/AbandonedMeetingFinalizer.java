package com.czur.reference.application.port;

import java.time.Duration;

/**
 * 服务端弃场收尾端口。
 *
 * <p>客户端没有 Stop 命令可复用时，由服务端按心跳事实收口一场已经开始、但
 * 长时间没有活跃的会议。实现必须在会议行锁内重新核对状态、心跳陈旧和 Stop
 * 用量优先级，不能在扫描结果之上直接改状态；用量结算仍走
 * {@code EntitlementProvider}，不在此端口里直接改预占。</p>
 *
 * <p>返回 false 表示本次候选条件已不成立（状态变化、心跳恢复、已有 Stop 用量），
 * 扫描器应静默跳过。数据库或产物存储暂时不可用应抛出运行时异常，让扫描器
 * 下轮重试；实现不得把失败伪装成成功。</p>
 */
@FunctionalInterface
public interface AbandonedMeetingFinalizer {
    /**
     * 收口一场弃场会议。
     *
     * @param meetingId 已由扫描器筛出的会议标识
     * @param heartbeatIdleWindow 判定陈旧的心跳窗口；实现必须再验证一次
     * @param maxMeetingDurationMs 部署最大会议时长，用于截断结算时长
     * @return true 表示已收口（含重放）；false 表示条件已不成立
     */
    boolean finalizeAbandoned(String meetingId, Duration heartbeatIdleWindow,
            long maxMeetingDurationMs);
}
