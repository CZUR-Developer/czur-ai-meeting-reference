package com.czur.aimeeting.client.identity;

import java.util.Objects;

/**
 * 某部署的当前登录会话。accessToken用于该部署会议协议的Bearer认证，displayName仅供UI。
 * 不保存密码、不自动退出设备账号、不实现token刷新；具体身份来源处理自己的账号流程。
 * 使用普通类而非record，避免自动toString把凭据带到调试日志。
 */
public final class IdentitySession {
    private final String accessToken;
    private final String displayName;

    /** 创建后端可接受的会话；无展示名时传空字符串，token不得为空。 */
    public IdentitySession(String accessToken, String displayName) {
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        if (accessToken.isBlank()) throw new IllegalArgumentException("登录会话缺少凭据");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    /** 仅提供给会议协议使用，调用方不得打印到日志或加入可读状态提示。 */
    public String accessToken() { return accessToken; }
    /** 用户界面的账号名称，不参与后端权益判定。 */
    public String displayName() { return displayName; }
}
