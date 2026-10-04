package com.czur.aimeeting.model;

import java.io.IOException;

/**
 * 构建期注册的文本总结协议 Adapter。SDK 负责一次 HTTP 请求和短期材料，Adapter 只
 * 构造厂商请求、解析厂商响应并返回标准纪要；不会接触长期 Key 或后端持久化。
 */
public interface SummaryAdapter {
    String id();

    String requestBody(SummaryInput input);

    SummaryResult parseResponse(String response, String language) throws IOException;
}
