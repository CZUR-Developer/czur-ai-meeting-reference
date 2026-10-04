package com.czur.reference.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 后端为单场会议向模型申请临时连接材料；不接收或转发音频。 */
public interface ModelSessionIssuer {
    /**
     * 根据本场首次 Create 请求与服务端能力决策签发临时模型材料。
     *
     * @param request 已认证且已持久化的本场语言与能力事实；翻译开关只能来自
     *                本场能力决策，不能由调用方请求参数或模型全局配置推断
     * @return Adapter 标识及短期连接材料；不得在日志中输出完整返回值
     */
    Session issue(Request request);

    /**
     * 模型签发所需的最小本场事实。语言保留公开协议的 IETF 代码；具体厂商代码
     * 由 Adapter 签发器映射。目标语言仅代表 Create 时选择的语言，
     * {@code translationEnabled} 才代表产品、权益、模型和部署限制均已允许翻译。
     */
    record Request(String sourceLanguage, List<String> targetLanguages,
                   boolean translationEnabled) {
        public Request {
            targetLanguages = List.copyOf(targetLanguages);
        }

        @Override public String toString() {
            return "ModelSessionIssuer.Request[targetCount=" + targetLanguages.size()
                    + ", translationEnabled=" + translationEnabled + "]";
        }
    }

    /** endpoint 可自带凭据；调用者只在认证响应中返回，绝不写日志或会议事实。 */
    record Session(String adapterId, String endpoint, Instant expiresAt,
                   Map<String, String> initParams) {
        /** JoinUrl 与 TaskId 均属于临时材料，避免 record 默认诊断文本泄露。 */
        @Override public String toString() {
            return "ModelSessionIssuer.Session[adapterId=" + adapterId
                    + ", materials=REDACTED]";
        }
    }
}
