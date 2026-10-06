package com.czur.aimeeting.client;

import com.czur.aimeeting.client.server.ReferenceLoginSession;

/**
 * 完整会议入口；App 不接触模型材料、音频帧或 Server 协议 JSON。
 * discover/login 是异步会前入口，配置 accessToken 可先为空；登录成功后用令牌创建本场配置。
 * 所有通知排到配置的串行 callbackExecutor；Android 通常传主线程 Executor。
 */
public interface MeetingClient extends AutoCloseable {
    /**
     * 异步发现部署静态能力、链接期限及邮件开关，并返回本地实现能力的交集。
     * 不要求已登录；可显示的会前模式不等于下一场已获授权，以 Create 的决策为准。
     */
    void discover(Callback<MeetingDiscovery> callback);
    /**
     * 异步复用终端用户登录协议。不修改此客户端的不可变配置；成功后 App 用返回令牌
     * 创建新的本场配置/客户端。登录页面、凭据保存及系统身份仍由 App 决定。
     */
    void login(String username, String password, Callback<ReferenceLoginSession> callback);
    /**
     * 立即返回控制句柄，实际能力发现/Create/材料/Start 在内部异步执行。
     * 同一客户端同时只允许一场未完成会议；已完成后可开始下一场，每场使用新 Adapter。
     * 所有阶段、显示及结果经配置的串行 callbackExecutor 交付。
     */
    MeetingSession start(MeetingOptions options, MeetingListener listener);
    /**
     * 幂等取消并释放当前会议，关闭后不可重用；关闭后不再派发尚未执行的业务通知。
     * 不调用后端正常 Stop，也不产生 READY 或结果；交付前应使用 session.requestStop()。
     */
    @Override void close();

    /** 结果和本地可读错误均经 callbackExecutor；不交付 HTTP/模型原始正文。 */
    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }
}
