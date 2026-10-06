package com.czur.aimeeting.client.server;

/**
 * 参考后端签发的终端用户会话。访问令牌只供本次进程中的会议请求使用；
 * 页面不会持久化令牌，也不会把令牌或密码写入日志。
 */
public final class ReferenceLoginSession {
    private final String accessToken;
    private final String expiresAt;

    public ReferenceLoginSession(String accessToken, String expiresAt) {
        this.accessToken = accessToken;
        this.expiresAt = expiresAt;
    }

    /** 后续会议 API 使用的 Bearer Token。 */
    public String accessToken() {
        return accessToken;
    }

    /** 后端返回的 UTC 到期时间，采用 RFC 3339 字符串。 */
    public String expiresAt() {
        return expiresAt;
    }
}
