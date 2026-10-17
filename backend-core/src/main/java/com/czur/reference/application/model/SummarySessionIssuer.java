package com.czur.reference.application.model;

import java.time.Instant;
import java.util.Map;

/**
 * 为单场会议签发总结 Adapter 所需的短期材料。
 *
 * <p>公共会议核心只依赖这个窄接口，不假设具体供应商、应用 ID 或永久密钥。
 * 发行器由宿主装配，成功响应的字段保持稳定；供应商实现不得把长期凭据放入
 * {@link Session}、日志或会议结果。</p>
 */
public interface SummarySessionIssuer {
    /** 在已经完成身份、本场能力和状态检查后签发一次短期总结材料。 */
    Session issue();

    /** 交给 Android Summary Adapter 的最小连接材料。 */
    interface Session {
        String adapterId();
        String endpoint();
        Instant expiresAt();
        String token();
        /** 供应商附加短期参数，由对应 Adapter 解释；旧发行器无需实现。 */
        default Map<String, String> initParams() { return Map.of(); }
    }

    /** Provider 暂时不可用；HTTP 层统一映射为可重试的模型错误。 */
    class SessionIssueException extends RuntimeException {
        public SessionIssueException() { super("summary session unavailable"); }
    }
}
