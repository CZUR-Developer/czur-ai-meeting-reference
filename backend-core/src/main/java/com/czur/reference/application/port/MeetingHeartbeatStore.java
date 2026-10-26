package com.czur.reference.application.port;

import java.time.Instant;

/**
 * 会议心跳事实的最小写入端口。
 *
 * <p>每场会议最多一行。第一次写入同时冻结服务端判定的“真正开始”和
 * “最后活跃”时间，并把计数置为 1；之后重复写入只刷新最后活跃时间并原子
 * 累加计数。本阶段只接收与记录，不推进会议状态、不触发收口、不参与结算。</p>
 *
 * <p>时间必须由调用方传入服务端时钟，实现不得改用数据库当前时间，避免同一
 * 场会议混用两个时钟。实现只接受确实存在的会议；会议缺失时应由外键拒绝，
 * 调用方按存储不可用处理，不能凭空创建会议或心跳事实。</p>
 */
public interface MeetingHeartbeatStore {
    /**
     * 记录一次会议心跳。
     *
     * @param meetingId 已存在会议的服务端标识
     * @param now 服务端时钟的本次活跃时间
     */
    void record(String meetingId, Instant now);
}
