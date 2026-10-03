package com.czur.aimeeting.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 后端为本场模型连接下发的短期材料。endpoint 可能自带签名参数，不能记录完整值。
 * initParams 是供应商专属启动参数；它们不得进入标准事件或会后结果。
 */
public final class ModelSessionGrant {
    private final String meetingId;
    private final String adapterId;
    private final String endpoint;
    private final String expiresAt;
    private final String token;
    private final Map<String, String> initParams;

    public ModelSessionGrant(String meetingId, String adapterId, String endpoint,
            String expiresAt, String token, Map<String, String> initParams) {
        this.meetingId = Objects.requireNonNull(meetingId, "meetingId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.token = token;
        this.initParams = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(initParams, "initParams")));
    }

    public String meetingId() { return meetingId; }
    public String adapterId() { return adapterId; }
    public String endpoint() { return endpoint; }
    public String expiresAt() { return expiresAt; }
    /** 可选的独立令牌；签名连接 URL 已足够时为 null。 */
    public String token() { return token; }
    public Map<String, String> initParams() { return initParams; }

    @Override
    public String toString() {
        return "ModelSessionGrant[meetingId=" + meetingId + ", adapterId=" + adapterId
                + ", endpoint=redacted, expiresAt=" + expiresAt + ", token=redacted]";
    }
}
