package com.czur.reference.application.port.identity;

/**
 * 把部署入口收到的凭据转换为稳定主体和本次授权声明的端口。
 *
 * <p>实现只负责验证身份，不创建会议、不判断套餐、不预占用量，也不能把
 * 原始凭据复制到返回值、日志、异常、数据库或 Outbox。部署范围由服务端装配
 * 决定，不属于客户端凭据请求，也不由该端口返回。</p>
 */
@FunctionalInterface
public interface IdentityProvider {
    /**
     * 同步验证一次短生命周期凭据。
     *
     * <p>实现必须返回稳定错误码。调用方负责在整个请求处理完成后关闭
     * {@link IdentityVerificationRequest#credential()}。</p>
     */
    IdentityProviderResult<VerifiedIdentity> verifyCredential(IdentityVerificationRequest request);
}
