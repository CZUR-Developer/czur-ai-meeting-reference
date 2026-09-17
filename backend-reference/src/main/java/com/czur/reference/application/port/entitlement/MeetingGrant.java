package com.czur.reference.application.port.entitlement;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link EntitlementProvider#authorizeStart(AuthorizeStartRequest)} 返回的会议授权。
 *
 * <p>Grant 同时绑定部署、主体、Product Profile 版本和本场能力请求，不能只凭
 * 裸 {@code grantId} 跨主体使用。有效期只约束首次预占；会议一旦原子绑定预占，
 * Grant 后续过期不会改变会议归属，也不会阻止最终用量提交。</p>
 */
public record MeetingGrant(
        String grantId,
        String deploymentScope,
        String subjectScope,
        String profileId,
        int profileRevision,
        Set<String> requestedCapabilities,
        Set<String> grantedCapabilities,
        long maxDurationMs,
        Instant issuedAt,
        Instant expiresAt) {

    public MeetingGrant {
        requireText("grantId", grantId);
        requireText("deploymentScope", deploymentScope);
        requireText("subjectScope", subjectScope);
        requireText("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        requestedCapabilities = immutableTextSet(requestedCapabilities, "requestedCapabilities");
        grantedCapabilities = immutableTextSet(grantedCapabilities, "grantedCapabilities");
        if (maxDurationMs <= 0) {
            throw new IllegalArgumentException("maxDurationMs must be positive");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    /** 截止时刻本身视为过期；实现授权和预占时必须使用同一规则。 */
    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(expiresAt);
    }

    private static Set<String> immutableTextSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            requireText(name + " value", value);
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
