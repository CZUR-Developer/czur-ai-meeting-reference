package com.czur.reference.application.command;

import com.czur.reference.application.port.identity.VerifiedIdentity;

import java.util.Objects;

/**
 * 一次认证会议命令的服务端安全上下文。
 *
 * <p>上下文只包含服务端部署配置决定的 {@code deploymentScope}，以及身份
 * Provider 已经验证完成的 {@link VerifiedIdentity}。客户端不得提交部署范围；
 * 原始 Token、密码、签名报文或 {@code SecretCredential} 也不得进入本对象，避免
 * 它们沿命令、数据库、Outbox 或异步 Worker 继续传播。</p>
 *
 * <p>该对象只表达“谁在服务端哪个部署范围内发起本次调用”，不替代权限检查、
 * 会议归属检查或权益判断。调用方仍须先检查命令所需的稳定权限码，再使用
 * {@link MeetingAccessGuard} 校验已有会议。T14 内部 Worker 不得伪造此上下文来
 * 冒充终端用户。</p>
 */
public record MeetingCommandContext(
        String deploymentScope,
        VerifiedIdentity identity) {

    public MeetingCommandContext {
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope must not be blank");
        }
        Objects.requireNonNull(identity, "identity");
    }

    /**
     * 返回不包含部署、主体、角色、权限或凭据引用的诊断摘要。
     *
     * <p>禁止恢复 record 默认格式；即使 {@link VerifiedIdentity#toString()} 已经
     * 脱敏，上下文也不应依赖嵌套对象未来的输出实现来维持安全边界。</p>
     */
    @Override
    public String toString() {
        return "MeetingCommandContext[serverScoped=true, identityVerified=true]";
    }
}
