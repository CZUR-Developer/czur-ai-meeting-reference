package com.czur.aimeeting.client.result

import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingOptions

/** 构建期动作扩展，平台 UI／文件／外部 App 归宿主，不接触 token、模型或底层 client。 */
interface ResultActionPlugin {
    /** 产品长期稳定 ID，不使用文案作键。 */
    fun id(): String
    fun displayName(): String
    /** 实际 READY 必需产物，Web／纯提示可为空。 */
    fun requiredArtifactTypes(): Set<String> = emptySet()
    /** 服务声明与模型能力相互独立。 */
    fun requiredServiceCapabilities(): Set<String> = emptySet()
    /**
     * 会前主线程只读准备状态并冻结参数闭包，不同步联网／扫码；阻止时不创建会议。
     * 闭包只捕获不可变参数及 Application Context，不能会后重读输入框或当前账号。
     */
    fun prepare(options: MeetingOptions): Preparation

    /** 每次执行是用户的一次动作，工作交工作队列／协议交 access；异步完成恰好回调一次。 */
    fun interface Execution {
        fun execute(context: ResultActionContext, callback: MeetingClient.Callback<ResultActionOutcome>)
    }
    /** 只在当前进程传递，不序列化平台闭包，不承诺重建恢复。 */
    data class Preparation(private val execution: Execution?, private val message: String) {
        fun execution(): Execution? = execution
        fun message(): String = message
        fun allowed(): Boolean = execution != null
        companion object {
            @JvmStatic fun allow(execution: Execution): Preparation = Preparation(execution, "")
            @JvmStatic fun block(message: String): Preparation = Preparation(null, message)
        }
    }
}
