package com.czur.aimeeting.client.server;

import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.MeetingResult;

import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.io.IOException;
import java.util.List;

/**
 * 共用 Coordinator 内部使用的 Server 协议端口。App 的完整业务入口不暴露模型材料；
 * 本端口只保留归一化请求/响应，阻塞调用由协调器的独立 IO 队列执行。
 */
public interface MeetingServerClient {
    /** 读取匿名部署能力；实际开场仍以 Create 冻结的本场决策为准。 */
    DeploymentOptions discover() throws IOException;

    /** 鉴权并创建会议，返回会议 ID 与后端冻结的能力决策。 */
    CreatedMeeting createMeeting(MeetingOptions setup) throws IOException;

    /** 告知后端本场由 App 提交标准事件，并进入可接收字幕的状态。 */
    void startClient(String meetingId) throws IOException;

    /** 单独领取本场模型直连材料；旧 Create 响应的 realtime 字段不属于模型。 */
    ModelSessionGrant requestModelSession(String meetingId) throws IOException;

    /** 本场最终字幕齐备后才领取短期文本模型材料；Mock 路径不调用。 */
    SummarySessionGrant requestSummarySession(String meetingId) throws IOException;

    /** 提交一次字幕修订；调用方负责按 clientSequence 顺序提交。 */
    void appendTranscript(String meetingId, int clientSequence, TranscriptEvent event)
            throws IOException;

    /** 与字幕共用 clientSequence，提交已归一化译文；仅本场获准互译时调用。 */
    void appendTranslation(String meetingId, int clientSequence, TranslationEvent event)
            throws IOException;

    /** 演示截图与字幕事件使用独立的包序号；每包至少一帧。 */
    void appendPresentation(String meetingId, int chunkSequence, long offsetMs, byte[] jpeg)
            throws IOException;

    /**
     * 提交本场停止采音后冻结的累计时长，单位为整数毫秒，允许实测零。
     * 同场重试必须提交同一值；成功返回表示后端已接管该值，价格及扣费规则由后端负责。
     * 调用顺序为最终事件保存之后、总结及 Stop 之前，在协调器的同一 IO 队列执行。
     *
     * <p>只有模型会话明确提供冻结值时才调用。默认方法保留旧实现源码兼容，
     * 但会明确报错，不将未实现误当作已提交；旧会话默认 empty 不会走此端口。
     */
    default void submitUsage(String meetingId, long audioDurationMs) throws IOException {
        throw new IOException("当前后端端口未实现采音用量提交");
    }

    /** 在 Stop 前提交已归一化的本场纪要；后端不接收厂商原始输出。 */
    void submitSummary(String meetingId, SummaryResult summary) throws IOException;

    /**
     * 请求结束会议。accepted=true 只说明输入接管；HTTP 202 仍在收口。
     * 这不是结果 READY，协调器还需读取实际 Manifest。
     */
    StopMeetingResponse stopMeeting(String meetingId) throws IOException;

    /** Stop 成功后由后端向会前选定邮箱发送这一场的有效期结果链接。 */
    void sendResultEmail(String meetingId, List<String> recipients, String resultUrl)
            throws IOException;

    /** Stop 200 后等待本场 Manifest 就绪，并只读取 READY 最终产物。 */
    MeetingResult readResult(String meetingId, long genTime, boolean includeContent) throws IOException;
}
