package com.czur.reference.application.realtime;

import com.czur.reference.application.port.model.RealtimeSessionSpec;

/**
 * 从同一可信只读快照联合 V013 首次 Create 与 V011 冻结计划的 Mock 会话规格。
 * 不从当前部署配置或 WSS 客户端字段补齐源语言、音频和执行限制；事实缺损时抛
 * 固定分类异常，调用方关闭连接，不启动模型会话。
 */
@FunctionalInterface
public interface RealtimeMockSessionSpecLookup {
    RealtimeSessionSpec load(String meetingId);
}
