package com.czur.aimeeting.client.identity;

/**
 * 参考App的默认内存身份。登录网络流程由MeetingClient封装，成功后更新本实例；
 * Activity与Service共同持有它，避免通过Intent传递可能过时的token。
 *
 * <p>不实现用户库、持久化或后台管理。进程退出后重新登录。伙伴可完全替换本实现；
 * CZUR系统账号应使用自己的Provider，通用SDK不会替它登出。</p>
 */
public final class ReferenceIdentityProvider implements IdentityProvider {
    private volatile IdentitySession session;

    public ReferenceIdentityProvider() {}

    /** 兼容旧调用方已经取得的固定凭据；空token保持未登录。 */
    public ReferenceIdentityProvider(String accessToken) {
        if (!accessToken.isBlank()) session = new IdentitySession(accessToken, "");
    }

    /** 登录成功后写入同一实例；不存储用户名密码或打印会话。 */
    public void updateSession(IdentitySession session) { this.session = session; }
    /** App的“切换账号”清除参考会话，不影响设备系统登录。 */
    public void clearSession() { session = null; }

    @Override public IdentityResult currentIdentity() {
        IdentitySession current = session;
        return current == null ? IdentityResult.loginRequired("请先登录") : IdentityResult.loggedIn(current);
    }
}
