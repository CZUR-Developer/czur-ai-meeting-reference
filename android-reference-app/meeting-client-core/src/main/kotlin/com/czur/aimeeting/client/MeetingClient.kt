package com.czur.aimeeting.client

import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.server.ReferenceLoginSession

/**
 * 完整会议入口，App 不接触模型材料、PCM 或 Server JSON。会前发现、登录与身份刷新异步；
 * 登录 UI 由 App 负责，身份来源可注入。通知使用配置的串行 callbackExecutor。
 */
interface MeetingClient : AutoCloseable {
    /** 无需登录的静态部署发现，显示能力取本地实现交集，实际授权仍以 Create 为准。 */
    fun discover(callback: Callback<MeetingDiscovery>)
    /** IO 读取注册身份，不创建会议／注销账号／替换进行中会议凭据；未登录也是类型化成功结果。 */
    fun refreshIdentity(callback: Callback<IdentityResult>)
    /** 默认参考账号密码登录，更新同一 LoginIdentityProvider；自定义身份返回后调用 refreshIdentity。 */
    fun login(username: String, password: String, callback: Callback<ReferenceLoginSession>)
    /** 立即返回控制句柄；同一客户端最多一场未完成会议，异步发现／创建／启动。 */
    fun start(options: MeetingOptions, listener: MeetingListener): MeetingSession
    /**
     * 只续办已保存原场发布／同键 Stop／实际 READY，不建新场或重开音源／模型／总结。
     * 使用原身份；部署可在身份扩展中批准同账号续签。默认在调用线程明确报告不支持。
     */
    fun resumeFinalization(checkpointId: String, callback: Callback<MeetingResult>) {
        callback.onError(MeetingError(MeetingErrorCode.STOP_STORE_MISSING))
    }
    /**
     * 续办进程异常退出留下的原场：用本机现场重建封口录音事实，补交缺失的标准纪要，
     * 再按与停止记录相同的发布／Stop／READY 顺序收口。不新建场次、不重开音源或模型，
     * 未确认现场保留。默认在调用线程明确报告不支持。
     */
    fun recoverInterrupted(meetingId: String, callback: Callback<RecoveryResult>) {
        callback.onError(MeetingError(MeetingErrorCode.RECOVERY_STORE_MISSING))
    }
    /** 取消并释放当前会议，关闭后不可重用；不做正常 Stop，不产生 READY。 */
    override fun close()

    /**
     * 结果及稳定错误码，不返回 HTTP／模型正文，也不返回面向终端用户的句子；
     * Void 成功值允许为 null。宿主按 [MeetingError.code] 映射自身文案和交互。
     */
    interface Callback<T> {
        fun onSuccess(result: T)
        /**
         * 稳定入口：宿主按 [MeetingError.code] 映射自己的文案和交互。
         * SDK 不返回面向终端用户的句子，也不把 HTTP／模型正文交出来。
         */
        fun onError(error: MeetingError)
        /**
         * 兼容入口：仅保留给宿主插件传递自己的错误文本；SDK 自身不再调用。
         * 新宿主应重写 [onError(error)]；默认实现只保留 UNKNOWN，不携带文本。
         */
        @Deprecated("Use onError(error: MeetingError) and map MeetingErrorCode")
        fun onError(message: String) { onError(MeetingError(MeetingErrorCode.UNKNOWN)) }
    }
}
