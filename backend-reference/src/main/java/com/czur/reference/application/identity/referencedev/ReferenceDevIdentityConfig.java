package com.czur.reference.application.identity.referencedev;

import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link ReferenceDevIdentityProvider} 的不可变本地演示配置。
 *
 * <p>该配置默认禁用。只有显式设置 {@value #ENABLED} 为 {@code true} 时才读取
 * 其余字段并进行 fail-fast 校验。它不是 record，且不会保存 Bearer Token 原文：
 * 构造期间只把 Token 临时转换成 UTF-8 字节，保存 SHA-256 摘要后立即清零临时
 * 数组。Java 无法清除调用方环境映射中已经存在的 {@link String}，部署方仍应
 * 通过 Secret 注入而不是把 Token 提交到配置文件。</p>
 *
 * <p>subject、组织、角色、权限和凭据时间窗都来自独立配置项，绝不从 Token
 * 摘要、邮箱、设备序列号或动态角色推导。该 Provider 仅用于公开参考代码的本地
 * 演示，不代表生产认证、OIDC、JWT、账号管理或计费实现。</p>
 */
public final class ReferenceDevIdentityConfig {
    public static final String ENABLED = "REFERENCE_DEV_IDENTITY_ENABLED";
    public static final String BEARER_TOKEN = "REFERENCE_DEV_IDENTITY_BEARER_TOKEN";
    public static final String SUBJECT_SCOPE = "REFERENCE_DEV_IDENTITY_SUBJECT_SCOPE";
    public static final String ORGANIZATION_SCOPE = "REFERENCE_DEV_IDENTITY_ORGANIZATION_SCOPE";
    public static final String ROLES = "REFERENCE_DEV_IDENTITY_ROLES";
    public static final String PERMISSIONS = "REFERENCE_DEV_IDENTITY_PERMISSIONS";
    public static final String AUDIENCE = "REFERENCE_DEV_IDENTITY_AUDIENCE";
    public static final String CLIENT_ID = "REFERENCE_DEV_IDENTITY_CLIENT_ID";
    public static final String ISSUED_AT = "REFERENCE_DEV_IDENTITY_ISSUED_AT";
    public static final String EXPIRES_AT = "REFERENCE_DEV_IDENTITY_EXPIRES_AT";
    public static final String CREDENTIAL_ID = "REFERENCE_DEV_IDENTITY_CREDENTIAL_ID";

    static final int MIN_TOKEN_BYTES = 32;
    static final int MAX_TOKEN_BYTES = 4_096;
    private static final int SHA_256_BYTES = 32;

    private final boolean enabled;
    private final byte[] credentialDigest;
    private final Subject subject;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final String audience;
    private final String clientId;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final String credentialId;

    private ReferenceDevIdentityConfig(
            boolean enabled,
            byte[] credentialDigest,
            Subject subject,
            Set<String> roles,
            Set<String> permissions,
            String audience,
            String clientId,
            Instant issuedAt,
            Instant expiresAt,
            String credentialId) {
        this.enabled = enabled;
        this.credentialDigest = credentialDigest == null ? null : credentialDigest.clone();
        this.subject = subject;
        this.roles = roles;
        this.permissions = permissions;
        this.audience = audience;
        this.clientId = clientId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.credentialId = credentialId;
    }

    /** 从当前进程环境读取一次配置；不会缓存或修改环境映射。 */
    public static ReferenceDevIdentityConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * 从显式环境映射创建配置。
     *
     * @param environment 调用方持有的环境映射；本类不保存该对象
     * @return 默认禁用或已经完整校验的 reference-dev 配置
     * @throws IllegalArgumentException 启用标记或任一启用配置非法；消息只包含字段名
     */
    public static ReferenceDevIdentityConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        if (!parseEnabled(environment.get(ENABLED))) {
            return disabled();
        }

        byte[] tokenBytes = null;
        byte[] digest = null;
        try {
            String token = requireText(BEARER_TOKEN, environment.get(BEARER_TOKEN));
            tokenBytes = token.getBytes(StandardCharsets.UTF_8);
            if (tokenBytes.length < MIN_TOKEN_BYTES || tokenBytes.length > MAX_TOKEN_BYTES) {
                throw invalid(BEARER_TOKEN,
                        "UTF-8 字节长度必须在 " + MIN_TOKEN_BYTES + " 到 "
                                + MAX_TOKEN_BYTES + " 之间");
            }
            digest = sha256(tokenBytes);

            String organization = optionalText(
                    ORGANIZATION_SCOPE, environment.get(ORGANIZATION_SCOPE));
            String subjectScope = requireText(SUBJECT_SCOPE, environment.get(SUBJECT_SCOPE));
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(subjectScope)) {
                throw invalid(SUBJECT_SCOPE, "不能使用历史兼容保留值");
            }
            Subject subject = new Subject(subjectScope, organization);
            Set<String> roles = parseSet(ROLES, environment.get(ROLES));
            Set<String> permissions = parseSet(PERMISSIONS, environment.get(PERMISSIONS));
            String audience = requireText(AUDIENCE, environment.get(AUDIENCE));
            String clientId = requireText(CLIENT_ID, environment.get(CLIENT_ID));
            Instant issuedAt = parseInstant(ISSUED_AT, environment.get(ISSUED_AT));
            Instant expiresAt = parseInstant(EXPIRES_AT, environment.get(EXPIRES_AT));
            if (!expiresAt.isAfter(issuedAt)) {
                throw invalid(EXPIRES_AT, "必须晚于 " + ISSUED_AT);
            }
            String credentialId = requireText(CREDENTIAL_ID, environment.get(CREDENTIAL_ID));

            return new ReferenceDevIdentityConfig(true, digest, subject, roles, permissions,
                    audience, clientId, issuedAt, expiresAt, credentialId);
        } finally {
            if (tokenBytes != null) {
                Arrays.fill(tokenBytes, (byte) 0);
            }
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }

    /** 返回是否显式启用了仅供本地演示的固定凭据身份实现。 */
    public boolean enabled() {
        return enabled;
    }

    byte[] copyCredentialDigest() {
        ensureEnabled();
        return credentialDigest.clone();
    }

    Subject subject() {
        ensureEnabled();
        return subject;
    }

    Set<String> roles() {
        ensureEnabled();
        return roles;
    }

    Set<String> permissions() {
        ensureEnabled();
        return permissions;
    }

    String audience() {
        ensureEnabled();
        return audience;
    }

    String clientId() {
        ensureEnabled();
        return clientId;
    }

    Instant issuedAt() {
        ensureEnabled();
        return issuedAt;
    }

    Instant expiresAt() {
        ensureEnabled();
        return expiresAt;
    }

    String credentialId() {
        ensureEnabled();
        return credentialId;
    }

    /**
     * 诊断文本只说明开关状态；Token、摘要、主体和全部身份声明均固定脱敏。
     */
    @Override
    public String toString() {
        return "ReferenceDevIdentityConfig{enabled=" + enabled
                + ", credential=REDACTED, identity=REDACTED}";
    }

    private static ReferenceDevIdentityConfig disabled() {
        return new ReferenceDevIdentityConfig(false, null, null, null, null,
                null, null, null, null, null);
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException("reference-dev identity is disabled");
        }
    }

    private static boolean parseEnabled(String value) {
        if (value == null || value.isBlank() || "false".equals(value.trim())) {
            return false;
        }
        if ("true".equals(value.trim())) {
            return true;
        }
        throw invalid(ENABLED, "必须是 true 或 false");
    }

    private static Set<String> parseSet(String name, String value) {
        String text = requireText(name, value);
        TreeSet<String> result = new TreeSet<>();
        for (String item : text.split(",", -1)) {
            String normalized = item.trim();
            if (normalized.isEmpty()) {
                throw invalid(name, "逗号分隔项不能为空");
            }
            if (!result.add(normalized)) {
                throw invalid(name, "不能包含重复项");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Instant parseInstant(String name, String value) {
        String text = requireText(name, value);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException failure) {
            throw invalid(name, "必须是 ISO-8601 UTC 时间");
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(name, "不能为空");
        }
        return value;
    }

    private static String optionalText(String name, String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw invalid(name, "只能省略，不能填写空白值");
        }
        return value;
    }

    private static byte[] sha256(byte[] value) {
        try {
            byte[] result = MessageDigest.getInstance("SHA-256").digest(value);
            if (result.length != SHA_256_BYTES) {
                Arrays.fill(result, (byte) 0);
                throw new IllegalStateException("SHA-256 provider returned invalid digest length");
            }
            return result;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", impossible);
        }
    }

    private static IllegalArgumentException invalid(String name, String detail) {
        return new IllegalArgumentException("reference-dev 身份配置 " + name + " 非法：" + detail);
    }
}
