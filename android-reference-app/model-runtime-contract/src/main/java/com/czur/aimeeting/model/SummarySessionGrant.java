package com.czur.aimeeting.model;

import java.util.Objects;

/** 总结前按本场能力签发的短期文本模型材料；不得进入标准事件、结果或日志。 */
public final class SummarySessionGrant {
    private final String adapterId;
    private final String endpoint;
    private final String expiresAt;
    private final String token;

    public SummarySessionGrant(String adapterId, String endpoint, String expiresAt,
            String token) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.token = Objects.requireNonNull(token, "token");
    }

    public String adapterId() { return adapterId; }
    public String endpoint() { return endpoint; }
    public String expiresAt() { return expiresAt; }
    public String token() { return token; }

    @Override public String toString() {
        return "SummarySessionGrant[adapterId=" + adapterId
                + ", endpoint=redacted, expiresAt=" + expiresAt + ", token=redacted]";
    }
}
