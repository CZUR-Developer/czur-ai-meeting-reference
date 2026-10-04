package com.czur.reference.application.model;

import com.czur.reference.application.command.MeetingCommandContext;

/** 从本场已持久化的 Create 请求读取源语言，供模型会话签发使用。 */
@FunctionalInterface
public interface ModelSessionLanguageLookup {
    /** 调用方已核验会议状态；实现仍须限定同一认证主体和部署范围。 */
    String load(MeetingCommandContext context, String meetingId);
}
