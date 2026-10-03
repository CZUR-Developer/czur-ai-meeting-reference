package com.czur.reference.application.model;

import java.time.Instant;
import java.util.Map;

/** 后端为单场会议向模型申请临时连接材料；不接收或转发音频。 */
public interface ModelSessionIssuer {
    Session issue();

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
