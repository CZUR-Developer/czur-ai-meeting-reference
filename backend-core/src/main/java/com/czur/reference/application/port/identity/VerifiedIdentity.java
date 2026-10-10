package com.czur.reference.application.port.identity;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次凭据验证产生的身份与当次授权声明。
 *
 * <p>{@link #subject()} 是稳定归属；roles 和 permissions 是本次验证的动态
 * 声明，只能用于当前请求授权，不能加入会议所有者或幂等 scope。该对象不保存
 * 原始 Token。{@code credentialId} 只能是 Provider 返回的安全、不透明引用，
 * 不能放入凭据正文或可逆编码。</p>
 */
public record VerifiedIdentity(
        Subject subject,
        Set<String> roles,
        Set<String> permissions,
        Instant issuedAt,
        Instant expiresAt,
        String credentialId) {

    public VerifiedIdentity {
        Objects.requireNonNull(subject, "subject");
        roles = immutableTextSet(roles, "roles");
        permissions = immutableTextSet(permissions, "permissions");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (credentialId != null && credentialId.isBlank()) {
            throw new IllegalArgumentException("credentialId must be null or non-blank");
        }
    }

    /** 截止时刻本身视为已过期，避免不同适配器产生一毫秒的授权差异。 */
    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(expiresAt);
    }

    /** 判断本次已验证身份是否包含指定稳定权限码。 */
    public boolean hasPermission(String permission) {
        return permission != null && permissions.contains(permission);
    }

    /**
     * 返回可以安全进入诊断信息的脱敏摘要。
     *
     * <p>record 默认实现会逐项输出 {@link #subject()}、角色、权限和
     * {@code credentialId}。这些值分别属于稳定归属、当次授权声明和凭据引用，
     * 均不得因为日志或异常拼接而泄漏。本实现只保留身份已经通过验证以及凭据的
     * 时间窗口；不要在后续改动中重新加入主体或声明集合。</p>
     */
    @Override
    public String toString() {
        return "VerifiedIdentity[verified=true, issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt + ']';
    }

    private static Set<String> immutableTextSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank values");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }
}
