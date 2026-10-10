package com.czur.reference.application.port.identity;

import java.util.Objects;

/**
 * 身份验证调用的短生命周期输入。
 *
 * <p>该类型故意不是 record，防止工具自动生成包含字段值的 {@code toString}。
 * 凭据只以 {@link SecretCredential} 形式存在。{@code audience} 和
 * {@code clientId} 由可信入口适配器填写，用于阻止把签发给其他服务或客户端
 * 的凭据拿来创建会议；它们不能替代部署范围。</p>
 */
public final class IdentityVerificationRequest {
    /** 首版身份端口接受的凭据类别。 */
    public enum CredentialType {
        BEARER_TOKEN,
        DEVICE_ASSERTION
    }

    private final CredentialType credentialType;
    private final SecretCredential credential;
    private final String audience;
    private final String clientId;

    public IdentityVerificationRequest(
            CredentialType credentialType,
            SecretCredential credential,
            String audience,
            String clientId) {
        this.credentialType = Objects.requireNonNull(credentialType, "credentialType");
        this.credential = Objects.requireNonNull(credential, "credential");
        this.audience = requireText("audience", audience);
        this.clientId = requireText("clientId", clientId);
    }

    public CredentialType credentialType() {
        return credentialType;
    }

    /** 返回受控凭据包装；Provider 只能在同步验证调用栈中使用它。 */
    public SecretCredential credential() {
        return credential;
    }

    public String audience() {
        return audience;
    }

    public String clientId() {
        return clientId;
    }

    /** 诊断输出明确省略凭据对象，避免未来包装实现变化造成泄露。 */
    @Override
    public String toString() {
        return "IdentityVerificationRequest[credentialType=" + credentialType
                + ", audience=" + audience + ", clientId=" + clientId
                + ", credential=REDACTED]";
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
