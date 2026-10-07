package com.czur.aimeeting.client.identity;

import java.util.Objects;

/**
 * 身份读取的最小结果。仅LOGGED_IN携带会话，另外两种状态交给App显示提示／打开登录入口。
 * 不携带账号服务原始响应、密码或系统错误堆栈；本类型不执行任何页面动作。
 */
public final class IdentityResult {
    public enum Status { LOGGED_IN, LOGIN_REQUIRED, READ_FAILED }
    private final Status status;
    private final IdentitySession session;
    private final String message;

    private IdentityResult(Status status, IdentitySession session, String message) {
        this.status = status;
        this.session = session;
        this.message = Objects.requireNonNull(message, "message");
    }

    public static IdentityResult loggedIn(IdentitySession session) {
        return new IdentityResult(Status.LOGGED_IN, Objects.requireNonNull(session, "session"), "已登录");
    }
    /** 提示必须面向用户，不能包含凭据。App自行选择打开系统或伙伴登录页面。 */
    public static IdentityResult loginRequired(String message) {
        return new IdentityResult(Status.LOGIN_REQUIRED, null, message);
    }
    /** 已知的读取错误可直接返回此状态；不将失败解释成自动退出设备系统账号。 */
    public static IdentityResult readFailed(String message) {
        return new IdentityResult(Status.READ_FAILED, null, message);
    }
    public Status status() { return status; }
    /** 只有LOGGED_IN时非null。 */
    public IdentitySession session() { return session; }
    public String message() { return message; }
}
