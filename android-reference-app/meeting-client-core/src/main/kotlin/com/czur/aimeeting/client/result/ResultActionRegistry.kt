package com.czur.aimeeting.client.result

import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.MeetingErrorCode

import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.MeetingOptions
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * 构建期结果动作清单，不默认添插件，不持有会议／重做 Stop／自动重试。
 * 宿主会前准备一次，在真实 READY 后派发，独立动作失败不改变原结果。
 */
class ResultActionRegistry(plugins: List<ResultActionPlugin>) {
    private val plugins: Map<String, ResultActionPlugin>
    init {
        val registered = LinkedHashMap<String, ResultActionPlugin>()
        for (plugin in plugins) {
            require(plugin.id().isNotBlank() && registered.putIfAbsent(plugin.id(), plugin) == null) {
                "结果动作ID为空或重复"
            }
        }
        this.plugins = Collections.unmodifiableMap(registered)
    }
    /** 保留装配顺序，只列出满足部署服务的实现，输入参数在 prepare 检查。 */
    fun available(discovery: MeetingDiscovery): List<ResultActionPlugin> {
        val services = serviceCapabilities(discovery)
        return java.util.List.copyOf(plugins.values.filter { services.containsAll(it.requiredServiceCapabilities()) })
    }
    /** 会前 Hook 冻结声明／闭包；产物存在性会后检查，不在会前虚构。 */
    fun prepare(actionId: String, options: MeetingOptions, discovery: MeetingDiscovery): Preparation {
        val plugin = plugins[actionId] ?: return Preparation(null, "当前应用未装配所选结果动作")
        if (!serviceCapabilities(discovery).containsAll(plugin.requiredServiceCapabilities()))
            return Preparation(null, "当前部署尚未提供所选结果服务")
        val prepared = plugin.prepare(options)
        if (!prepared.allowed()) return Preparation(null, prepared.message())
        return Preparation(PreparedAction(plugin.id(), plugin.requiredArtifactTypes(),
            plugin.requiredServiceCapabilities(), prepared.execution()!!), "")
    }
    /** UI 队列核真实产物／服务并派发，成功／失败均回到同队列；宿主决定是否手动再执行。 */
    fun execute(action: PreparedAction, context: ResultActionContext, callback: MeetingClient.Callback<ResultActionOutcome>) {
        val completed = AtomicBoolean()
        val completion = object : MeetingClient.Callback<ResultActionOutcome> {
            override fun onSuccess(result: ResultActionOutcome) {
                if (!completed.compareAndSet(false, true)) return
                LOG.info("结果动作完成 id=${action.id()} status=${result.status()}")
                context.uiExecutor().execute {
                    callback.onSuccess(ResultActionOutcome(action.id(), result.status(), result.message()))
                }
            }
            override fun onError(message: String) { onSuccess(ResultActionOutcome.failure(action.id(), message)) }
        }
        context.uiExecutor().execute {
            if (!context.result().artifactTypes().containsAll(action.requiredArtifactTypes())) {
                completion.onError(MeetingError(MeetingErrorCode.RESULT_ARTIFACT_MISSING))
                return@execute
            }
            if (!context.serviceCapabilities().containsAll(action.requiredServiceCapabilities())) {
                completion.onError(MeetingError(MeetingErrorCode.RESULT_SERVICE_UNAVAILABLE))
                return@execute
            }
            LOG.info("结果动作开始 id=${action.id()}")
            try { action.execution().execute(context, completion) }
            catch (failure: RuntimeException) {
                LOG.warning("结果动作失败 id=${action.id()} type=${failure.javaClass.simpleName}")
                completion.onError(MeetingError(MeetingErrorCode.RESULT_ACTION_FAILED))
            }
        }
    }

    /** 单场冻结声明与参数闭包，只在进程内持有。 */
    data class PreparedAction(private val id: String, private var requiredArtifactTypes: Set<String>,
        private var requiredServiceCapabilities: Set<String>, private val execution: ResultActionPlugin.Execution) {
        init {
            requiredArtifactTypes = java.util.Set.copyOf(requiredArtifactTypes)
            requiredServiceCapabilities = java.util.Set.copyOf(requiredServiceCapabilities)
        }
        fun id(): String = id
        fun requiredArtifactTypes(): Set<String> = requiredArtifactTypes
        fun requiredServiceCapabilities(): Set<String> = requiredServiceCapabilities
        fun execution(): ResultActionPlugin.Execution = execution
    }
    data class Preparation(private val action: PreparedAction?, private val message: String) {
        fun action(): PreparedAction? = action
        fun message(): String = message
        fun allowed(): Boolean = action != null
    }
    companion object {
        const val RESULT_LINK = "result.link"
        const val RESULT_EMAIL = "result.email"
        private val LOG = Logger.getLogger(ResultActionRegistry::class.java.name)
        /** 适配已有发现字段，不与模型能力求交。 */
        @JvmStatic fun serviceCapabilities(discovery: MeetingDiscovery): Set<String> {
            val deployment = discovery.deployment()
            val services = HashSet<String>()
            if (deployment.resultLinkTtlMs() > 0) services.add(RESULT_LINK)
            if (deployment.resultEmailEnabled()) services.add(RESULT_EMAIL)
            return java.util.Set.copyOf(services)
        }
    }
}
