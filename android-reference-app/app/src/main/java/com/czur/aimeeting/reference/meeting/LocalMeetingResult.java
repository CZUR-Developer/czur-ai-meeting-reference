package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingResultContent;

/** P6-6 迁移期本地 PDF 参数；所有协议 JSON 已由 core 转成类型化内容。 */
public record LocalMeetingResult(String title, MeetingResultContent content) { }
