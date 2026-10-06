package com.czur.aimeeting.client;

import com.czur.aimeeting.model.ModelAdapter;

/**
 * 合作伙伴的模型消息解析工厂。每场会议调用一次 {@link #create()}，返回独立的 Adapter；
 * 音源、WebSocket、Server 请求和显示路由由共用流程负责，工厂只创建协议解析状态。
 */
@FunctionalInterface
public interface ModelAdapterFactory {
    ModelAdapter create();
}
