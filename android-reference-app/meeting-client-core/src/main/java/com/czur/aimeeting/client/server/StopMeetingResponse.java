package com.czur.aimeeting.client.server;

/** Stop 只表达输入接管；即使 accepted=true，组合层仍需读取 Manifest 确认结果 READY。 */
public record StopMeetingResponse(boolean accepted, String inputState,
        String finalizationReceipt, String manifestState) { }
