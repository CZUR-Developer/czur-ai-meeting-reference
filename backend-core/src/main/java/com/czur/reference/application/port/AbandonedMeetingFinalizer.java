package com.czur.reference.application.port;

/**
 * 服务端弃场收尾端口。
 *
 * <p>客户端没有 Stop 命令可复用时，扫描器先持久化弃场决定（冻结结算时长、
 * 目标终态与决定时间），在连接外完成权益结算后才调用本端口。实现必须按
 * {@code meetingId} 读取该决定，并只在决定已结算、目标为
 * {@code READY_WITH_WARNINGS} 时写结果与终态；不能在扫描结果之上直接转状态，
 * 也不能自己调用权益端口。</p>
 *
 * <p>实现应在同一事务内完成结果、Manifest、终态写入与决定删除，重复执行幂等
 * 收敛。返回 false 表示决定缺失、尚未结算或状态不一致，扫描器应留待下一轮；
 * 数据库或产物存储暂时不可用应抛出运行时异常，让扫描器下轮重试。实现不得把
 * 失败伪装成成功。</p>
 */
@FunctionalInterface
public interface AbandonedMeetingFinalizer {
    /**
     * 按已落库的弃场决定收口一场会议。
     *
     * @param meetingId 已有弃场决定的会议标识
     * @return true 表示已收口（含此前已收口）；false 表示决定不存在或尚不满足
     */
    boolean finalizeAbandoned(String meetingId);
}
