package com.czur.reference.application.model;

import com.czur.reference.application.command.MeetingCommandContext;

/** 从本场已持久化的 Create 请求和能力决策读取模型签发事实。 */
@FunctionalInterface
public interface ModelSessionLanguageLookup {
    /**
     * 调用方已核验会议状态；实现仍须限定同一认证主体和部署范围，并在同一
     * 持久快照中取得语言选择与首次服务端能力决策。不能从当前部署配置重算本场能力。
     */
    ModelSessionIssuer.Request load(MeetingCommandContext context, String meetingId);

    /** 文本总结须已有客户端输入标记，并由首次 Create 冻结的本场能力授权。 */
    default SummaryEligibility summaryEligibility(MeetingCommandContext context,
            String meetingId) {
        return new SummaryEligibility(false, false);
    }

    record SummaryEligibility(boolean clientStarted, boolean granted) { }
}
