package com.czur.aimeeting.client.result

import com.czur.aimeeting.client.*
import com.czur.aimeeting.client.server.DeploymentOptions
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 插件扩展／会前冻结／实际产物门及同一 READY 手动重做，保留原用例，不建异常平台。 */
class ResultActionRegistryTest {
    @Test fun onlyRegisteredImplementationsAppearRegardlessOfDeclaredEmailService() {
        val notice = plugin("example.notice") {
            ResultActionPlugin.Preparation.allow { _, callback -> callback.onSuccess(ResultActionOutcome.success("example.notice", "已完成")) }
        }
        val registry = ResultActionRegistry(listOf(notice))
        assertEquals(listOf(notice), registry.available(discovery(true)))
        assertFalse(registry.prepare("email", options(), discovery(true)).allowed())
        assertTrue(ResultActionRegistry(emptyList()).available(discovery(true)).isEmpty())
    }
    @Test fun prepareReadsLocalConditionAndFreezesParametersOnce() {
        val preparedRecipient = AtomicReference<String>()
        val received = AtomicReference<String>()
        val prepares = AtomicInteger()
        val plugin = plugin("partner.notice") {
            prepares.incrementAndGet()
            val snapshot = preparedRecipient.get()
            if (snapshot == null) ResultActionPlugin.Preparation.block("请先完成接收者登记")
            else ResultActionPlugin.Preparation.allow { context, callback ->
                received.set("$snapshot:${context.result().meetingId()}:${context.result().genTime()}")
                callback.onSuccess(ResultActionOutcome.success("partner.notice", "已完成"))
            }
        }
        val registry = ResultActionRegistry(listOf(plugin))
        assertEquals("请先完成接收者登记", registry.prepare("partner.notice", options(), discovery(false)).message())
        preparedRecipient.set("准备对象A")
        val prepared = registry.prepare("partner.notice", options(), discovery(false))
        preparedRecipient.set("稍后变化B")
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(registry, prepared.action()!!, context(emptySet())).status())
        assertEquals("准备对象A:mtg_ready:555", received.get())
        assertEquals(2, prepares.get()) // 一次阻止、一次允许，会后不重新准备。
    }
    @Test fun failedActionCanBeManuallyExecutedAgainWithSameReadyFacts() {
        val calls = AtomicInteger()
        val plugin = plugin("example.notice") {
            ResultActionPlugin.Preparation.allow { _, callback ->
                if (calls.incrementAndGet() == 1) callback.onError("本次提示未显示，请再次执行")
                else callback.onSuccess(ResultActionOutcome.success("example.notice", "已完成"))
            }
        }
        val registry = ResultActionRegistry(listOf(plugin))
        val prepared = registry.prepare("example.notice", options(), discovery(false)).action()!!
        val context = context(setOf("transcript.final"))
        assertEquals(ResultActionOutcome.Status.FAILED, execute(registry, prepared, context).status())
        assertEquals(1, calls.get()) // 不自动重试。
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(registry, prepared, context).status())
        assertEquals(MeetingResult.Status.READY, context.result().status())
        assertEquals(555L, context.result().genTime()); assertEquals(2, calls.get())
    }
    @Test fun requiredServiceAndActualArtifactMustExistBeforeExecution() {
        val calls = AtomicInteger()
        val plugin = object : ResultActionPlugin {
            override fun id(): String = "partner.summary"
            override fun displayName(): String = "总结通知"
            override fun requiredArtifactTypes(): Set<String> = setOf("summary.structured")
            override fun requiredServiceCapabilities(): Set<String> = setOf(ResultActionRegistry.RESULT_EMAIL)
            override fun prepare(options: MeetingOptions): ResultActionPlugin.Preparation = ResultActionPlugin.Preparation.allow { _, callback ->
                calls.incrementAndGet(); callback.onSuccess(ResultActionOutcome.success(id(), "已完成"))
            }
        }
        val registry = ResultActionRegistry(listOf(plugin))
        assertTrue(registry.available(discovery(false)).isEmpty())
        assertFalse(registry.prepare(plugin.id(), options(), discovery(false)).allowed())
        val prepared = registry.prepare(plugin.id(), options(), discovery(true)).action()!!
        assertEquals(ResultActionOutcome.Status.FAILED, execute(registry, prepared, context(setOf("transcript.final"))).status())
        assertEquals(0, calls.get())
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(registry, prepared, context(setOf("summary.structured"))).status())
    }
    @Test fun oldConstructorsMapToActionsWithoutChangingSummaryOrSavingPolicy() {
        val ids = arrayOf("web", "local.pdf", "email", "")
        var index = 0
        for (delivery in MeetingOptions.ResultDelivery.entries) {
            val old = MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US", false,
                delivery, "https://result.example.test", emptyList())
            assertEquals(ids[index++], old.resultOptions().actionId())
            assertEquals(delivery, old.resultOptions().legacyDelivery()); assertTrue(old.summaryEnabled())
            assertEquals(if (delivery == MeetingOptions.ResultDelivery.TRACELESS) ResultOptions.Policy.NO_ACTIVE_DELIVERY else ResultOptions.Policy.READY,
                old.resultOptions().policy())
        }
        val chosen = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "zh-CN", "en-US", false,
            ResultOptions.action("partner.notice"), "", emptyList(), true)
        assertEquals("partner.notice", chosen.resultOptions().actionId()); assertTrue(chosen.summaryEnabled())
        assertTrue(chosen.requiredCapabilities().contains("translation.realtime")); assertTrue(chosen.requiredCapabilities().contains("summary.structured"))
    }
    private fun plugin(id: String, prepareBlock: (MeetingOptions) -> ResultActionPlugin.Preparation): ResultActionPlugin = object : ResultActionPlugin {
        override fun id(): String = id
        override fun displayName(): String = "本场完成提示"
        override fun prepare(options: MeetingOptions): ResultActionPlugin.Preparation = prepareBlock(options)
    }
    private fun options(): MeetingOptions = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US", false,
        MeetingOptions.ResultDelivery.WEB, "https://result.example.test", emptyList())
    private fun discovery(email: Boolean): MeetingDiscovery = MeetingDiscovery(DeploymentOptions(setOf("transcript.realtime"), 600000, email), emptySet())
    private fun context(artifacts: Set<String>): ResultActionContext = ResultActionContext(
        MeetingResult("mtg_ready", "本场", 555, MeetingResult.Status.READY, artifacts, null), object : ResultActionAccess {
            override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) { fail("提示插件不能读取正文") }
            override fun sendEmail(recipients: List<String>, resultUrl: String, callback: MeetingClient.Callback<Void?>) { fail("提示插件不能调用邮件协议") }
        }, ResultActionRegistry.serviceCapabilities(discovery(true)), Executor { it.run() }, Executor { it.run() })
    private fun execute(registry: ResultActionRegistry, prepared: ResultActionRegistry.PreparedAction, context: ResultActionContext): ResultActionOutcome {
        val outcome = CompletableFuture<ResultActionOutcome>()
        registry.execute(prepared, context, object : MeetingClient.Callback<ResultActionOutcome> {
            override fun onSuccess(result: ResultActionOutcome) { outcome.complete(result) }
            override fun onError(message: String) { fail(message) }
        })
        return outcome.get(2, TimeUnit.SECONDS)
    }
}
