package com.czur.reference.application.model;

import java.time.Instant;
import java.util.Map;

/**
 * 独立文本翻译模型的材料签发端口。
 *
 * <p>模型本身由 Android SDK 直连；服务端只领取供应商短期材料，不接收原文、不执行翻译。
 * 宿主可以将任意 ASR 签发器与本端口组合，复用同一会议、能力决策与标准译文事件。
 * 原来已经包含翻译的实时模型不需要注册本端口。</p>
 */
@FunctionalInterface
public interface TranslationSessionIssuer {
    /**
     * 根据已经持久化并授权的本场事实签发材料。
     *
     * @param request 同一会议的源／目标语言；只有 translationEnabled 为 true 才应调用
     * @return 伙伴注册的 Android TranslationAdapter 标识及短期连接材料
     */
    Session issue(ModelSessionIssuer.Request request);

    /**
     * 随原 modelSession 响应下发的可选材料，不增加第二套会议状态或翻译内容转发端点。
     *
     * <p>endpoint 可以是供应商签名地址；token 允许为 null，鉴权可由签名地址或
     * initParams 中的供应商短期材料完成。所有材料都只用于本场模型请求，不能放入日志、
     * 产物或永久配置；尤其不能直接把长期 API Key 当成这里的 token。</p>
     */
    record Session(String adapterId, String endpoint, Instant expiresAt, String token,
                   Map<String, String> initParams) {
        public Session { initParams = Map.copyOf(initParams); }

        @Override public String toString() {
            return "TranslationSessionIssuer.Session[adapterId=" + adapterId
                    + ", materials=REDACTED]";
        }
    }

    /** 供应商暂时无法签发；沿用现有模型材料不可用响应，不输出供应商错误正文。 */
    class SessionIssueException extends IllegalStateException {
        public SessionIssueException() { super("translation session unavailable"); }
    }
}
