package com.czur.aimeeting.client;

import com.czur.aimeeting.client.server.ReferenceLoginSession;
import com.czur.aimeeting.client.identity.IdentityResult;

/**
 * 完整会议入口；App 不接触模型材料、音频帧或 Server 协议 JSON。
 * discover/login/refreshIdentity是异步会前入口；登录页面由App负责，身份来源可注入。
 * 所有通知排到配置的串行 callbackExecutor；Android 通常传主线程 Executor。
 */
public interface MeetingClient extends AutoCloseable {
    /**
     * 异步发现部署静态能力、链接期限及邮件开关，并返回本地实现能力的交集。
     * 不要求已登录；可显示的会前模式不等于下一场已获授权，以 Create 的决策为准。
     */
    void discover(Callback<MeetingDiscovery> callback);
    /**
     * 在IO队列读取已注册身份来源，并经callbackExecutor返回类型化状态。
     * 进入页面／登录返回时可调用；未登录或读取失败通过onSuccess的状态表达，App自行
     * 显示对应入口。此操作不创建会议、不退出设备账号，也不替换进行中会议的凭据。
     */
    void refreshIdentity(Callback<IdentityResult> callback);
    /**
     * 默认参考账号密码登录。成功后更新同一个ReferenceIdentityProvider，页面与Service
     * 可继续共用它。自定义身份来源使用其App登录入口，返回后调用refreshIdentity，
     * 无须实现账号密码接口。保留返回值供旧调用方创建token配置。
     */
    void login(String username, String password, Callback<ReferenceLoginSession> callback);
    /**
     * 立即返回控制句柄，实际能力发现/Create/材料/Start 在内部异步执行。
     * 同一客户端同时只允许一场未完成会议；已完成后可开始下一场，每场使用新 Adapter。
     * 所有阶段、显示及结果经配置的串行 callbackExecutor 交付。
     */
    MeetingSession start(MeetingOptions options, MeetingListener listener);
    /**
     * 只续办已保存原场的发布／同键 Stop／实际 READY，结果经 callbackExecutor 返回。
     * 不创建新会议、打开音源／实时模型、重跑总结或恢复会中窗口。
     * 仅适用于尾部全部确认且应用私有恢复条件已齐的主动交付记录；与未完成会议互斥。
     * 默认保留原凭据；部署可显式重写 IdentityProvider.identityForFinalization，
     * 核同一原账号后正常续签，并同步应用于原场记录及 HTTP，不借其他账号完成原场。
     * 身份未就绪或认证失败保留待处理。默认方法为旧实现保留源码兼容，
     * 在调用线程明确通知不支持；提供恢复能力的实现须按自己的回调执行器异步派发。
     */
    default void resumeFinalization(String checkpointId, Callback<MeetingResult> callback) {
        callback.onError("当前会议客户端未实现停止记录续办");
    }
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
