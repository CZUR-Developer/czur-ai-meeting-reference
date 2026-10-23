package com.czur.aimeeting.reference.meeting

import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import com.czur.aimeeting.client.*
import com.czur.aimeeting.client.result.*
import com.czur.aimeeting.client.result.ResultActionPlugin.Preparation
import com.czur.aimeeting.client.server.DeploymentOptions
import com.czur.aimeeting.client.provider.BailianSummaryAdapter
import com.czur.aimeeting.client.provider.SampleJsonModelAdapter
import com.czur.aimeeting.client.provider.TingwuModelAdapter
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSignal
import com.czur.aimeeting.model.TranscriptListener
import com.czur.aimeeting.reference.*
import com.czur.aimeeting.reference.AppResultActionPlugin.Settings
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory
import com.czur.aimeeting.reference.ui.OverlayViewFactory
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 真实装配、冻结与插件执行，不用 Android 空 stub 绘制控件。 */
class MeetingAppAssemblyTest {
    @After fun restoreDefault() {
        MeetingAppAssembly.clearPreparedMeeting()
        MeetingAppAssembly.registerModelAdapters(ModelAdapterRegistry())
        MeetingAppAssembly.registerResultActions(listOf(WebResultActionPlugin()))
        MeetingAppAssembly.registerOverlayFactory(OverlayViewFactory())
        MeetingAppAssembly.registerPageFactory(MeetingPageViews.DefaultFactory())
        MeetingAppAssembly.configureResultWeb("")
    }
    @Test fun defaultModelRegistryKeepsBuiltInAdaptersAndReadyCapabilities() {
        val adapters = MeetingAppAssembly.modelAdapters()
        assertEquals(TingwuModelAdapter.ID, adapters.create(TingwuModelAdapter.ID).id())
        assertEquals(SampleJsonModelAdapter.ID, adapters.create(SampleJsonModelAdapter.ID).id())
        assertEquals(BailianSummaryAdapter.ID, adapters.createSummary(BailianSummaryAdapter.ID).id())
        // 注册表声明本地能力；本场发言人仍须与部署能力和 Create 授权取交集。
        assertEquals(setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION,
            MeetingCapabilityState.SUMMARY, MeetingCapabilityState.SPEAKER), adapters.setupCapabilities())
    }
    @Test fun partnerRegistryIsRegisteredAtAssemblyAndKeepsDefaults() {
        val adapters = ModelAdapterRegistry().register("partner-ws", ::PartnerAdapter, setOf(MeetingCapabilityState.TRANSCRIPT))
        MeetingAppAssembly.registerModelAdapters(adapters)
        assertSame(adapters, MeetingAppAssembly.modelAdapters())
        assertEquals("partner-ws", MeetingAppAssembly.modelAdapters().create("partner-ws").id())
        assertEquals(setOf(MeetingCapabilityState.TRANSCRIPT), MeetingAppAssembly.modelAdapters().realtimeCapabilities("partner-ws"))
        assertEquals(TingwuModelAdapter.ID, MeetingAppAssembly.modelAdapters().create(TingwuModelAdapter.ID).id())
    }
    @Test fun registeredFactoryCreatesIndependentAdapterForEachMeeting() {
        val created = AtomicInteger()
        MeetingAppAssembly.registerModelAdapters(ModelAdapterRegistry().register("partner-ws", {
            created.incrementAndGet(); PartnerAdapter()
        }, setOf(MeetingCapabilityState.TRANSCRIPT)))
        assertEquals(0, created.get())
        val first = MeetingAppAssembly.modelAdapters().create("partner-ws")
        val second = MeetingAppAssembly.modelAdapters().create("partner-ws")
        assertNotSame(first, second); assertEquals(2, created.get())
    }
    @Test fun pageFactoryOnlyReplacesViewsAndKeepsDefaultWebAndWindow() {
        val factory = MeetingPageViews.Factory { throw AssertionError("尚未创建页面") }
        MeetingAppAssembly.registerPageFactory(factory)
        assertSame(factory, MeetingAppAssembly.pageFactory())
        assertTrue(MeetingAppAssembly.overlayFactory() is OverlayViewFactory)
        assertEquals(listOf("web"), MeetingAppAssembly.resultActions().map { it.id() })
    }
    @Test fun processWebConfigurationDoesNotChangePreparedSameMeetingClosure() {
        val opened = AtomicReference<String>()
        MeetingAppAssembly.configureResultWeb("https://first.example.test/index.html")
        val prepared = MeetingAppAssembly.prepare(setup("web"), discovery(), WebResultActionPlugin(MeetingAppAssembly.resultWebUrl(), opened::set)).meeting()!!
        MeetingAppAssembly.configureResultWeb("https://next.example.test/index.html")
        assertEquals("https://next.example.test/index.html", MeetingAppAssembly.resultWebUrl())
        MeetingAppAssembly.handoff(prepared); assertSame(prepared, MeetingAppAssembly.consumePreparedMeeting())
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(prepared, ready(), noProtocols(), Runnable::run).status())
        assertEquals("https://first.example.test/index.html?meetingId=mtg_same&genTime=555", opened.get())
    }
    @Test fun windowFactoryReplacesOnlyAssemblyWithoutCreatingMeetingOrWindow() {
        assertTrue(MeetingAppAssembly.overlayFactory() is OverlayViewFactory)
        val factory = MeetingOverlayFactory { _, _, _, _ -> throw AssertionError("尚未显示窗口") }
        MeetingAppAssembly.registerOverlayFactory(factory)
        assertSame(factory, MeetingAppAssembly.overlayFactory())
        assertEquals(listOf("web"), MeetingAppAssembly.resultActions().map { it.id() })
    }
    @Test fun defaultOnlyWebEvenWhenEmailServiceIsAvailable() {
        assertEquals(listOf("web"), MeetingAppAssembly.resultActions().map { it.id() })
        assertEquals(listOf("web"), MeetingAppAssembly.resultRegistry().available(discovery()).map { it.id() })
        assertTrue(MeetingAppAssembly.resultRegistry().available(MeetingDiscovery(DeploymentOptions(emptySet(), 0, true), emptySet())).isEmpty())
    }
    @Test fun noticeReplacesAssemblyAndUsesSameReadyWithoutProtocols() {
        val shown = AtomicReference<String>()
        val notice = CompletionNoticeAction(shown::set)
        MeetingAppAssembly.registerResultActions(listOf(notice))
        val prepared = MeetingStartConditions.prepare({ MeetingStartCondition.Decision.allow() }, setup("example.notice"), discovery(), notice)
        assertTrue(prepared.allowed())
        val ready = ready()
        val outcome = execute(prepared.meeting()!!, ready, noProtocols(), Runnable::run)
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, outcome.status()); assertEquals("本场结果已就绪：mtg_same", shown.get())
        assertEquals(555L, ready.genTime()); assertNull(ready.content()); assertEquals(MeetingResult.Status.READY, ready.status())
    }
    @Test fun hookFreezesOnceAndServiceConsumesSameObjectAfterPermissionWait() {
        val prepares = AtomicInteger(); val input = AtomicReference("会前A"); val received = AtomicReference<String>()
        val plugin = object : AppResultActionPlugin {
            override fun id() = "partner.notice"
            override fun displayName() = "伙伴提示"
            override fun createSettings(pageContext: android.content.Context) = Settings(null) { this }
            override fun prepare(options: MeetingOptions): Preparation {
                prepares.incrementAndGet()
                val frozen = input.get()
                return Preparation.allow { _, callback -> received.set(frozen); callback.onSuccess(ResultActionOutcome.success(id(), "完成")) }
            }
        }
        MeetingAppAssembly.registerResultActions(listOf(plugin))
        val prepared = MeetingStartConditions.prepare({ MeetingStartCondition.Decision.allow() }, setup(plugin.id()), discovery(), plugin).meeting()!!
        input.set("授权等待后B"); MeetingAppAssembly.handoff(prepared)
        assertSame(prepared, MeetingAppAssembly.consumePreparedMeeting()); assertNull(MeetingAppAssembly.consumePreparedMeeting())
        execute(prepared, ready(), noProtocols(), Runnable::run)
        assertEquals("会前A", received.get()); assertEquals(1, prepares.get())
        MeetingAppAssembly.handoff(prepared); MeetingAppAssembly.clearPreparedMeeting(); assertNull(MeetingAppAssembly.consumePreparedMeeting())
    }
    @Test fun webFrozenUrlMergesQueryAndFragmentAndWaitsForVisibleUi() {
        val pendingUi = mutableListOf<Runnable>(); val opened = AtomicReference<String>()
        val web = WebResultActionPlugin("https://result.example.test/index.html?x=1&meetingId=old&genTime=1#section", opened::set)
        val prepared = MeetingAppAssembly.prepare(setup("web"), discovery(), web).meeting()!!
        val outcome = AtomicReference<ResultActionOutcome>()
        val context = ResultActionContext(ready(), noProtocols(), prepared.serviceCapabilities(), Runnable::run) { command ->
            if (command is AppResultActionPlugin.UiOperation) pendingUi += command else command.run()
        }
        MeetingAppAssembly.resultRegistry().execute(prepared.action()!!, context, callback(outcome))
        assertNull(outcome.get()); assertNull(opened.get()); assertEquals(1, pendingUi.size)
        pendingUi.removeAt(0).run()
        assertEquals("https://result.example.test/index.html?x=1&meetingId=mtg_same&genTime=555#section", opened.get())
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, outcome.get().status())
        assertEquals("https://result.example.test/?meetingId=a%2Fb&genTime=7#part", WebResultActionPlugin.resultUrl("https://result.example.test/#part", "a/b", 7))
    }
    @Test fun emailIsExplicitAndFailureCanRetrySameReadyWithFrozenRecipients() {
        val recipients = mutableListOf("prepared@example.test")
        val email = EmailResultActionPlugin("https://result.example.test/?x=1#section", recipients)
        recipients[0] = "changed@example.test"
        assertFalse(MeetingAppAssembly.prepare(setup("email"), discovery(), email).allowed())
        MeetingAppAssembly.registerResultActions(listOf(email))
        val prepared = MeetingAppAssembly.prepare(setup("email"), discovery(), email).meeting()!!
        val calls = AtomicInteger()
        val access = object : ResultActionAccess {
            override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) { fail("邮件不读正文") }
            override fun sendEmail(emails: List<String>, url: String, callback: MeetingClient.Callback<Void?>) {
                assertEquals(listOf("prepared@example.test"), emails)
                assertEquals("https://result.example.test/?x=1&meetingId=mtg_same&genTime=555#section", url)
                if (calls.incrementAndGet() == 1) callback.onError("受控普通失败") else callback.onSuccess(null)
            }
        }
        val ready = ready()
        assertEquals(ResultActionOutcome.Status.FAILED, execute(prepared, ready, access, Runnable::run).status()); assertEquals(1, calls.get())
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(prepared, ready, access, Runnable::run).status()); assertEquals(2, calls.get())
        assertEquals(MeetingResult.Status.READY, ready.status()); assertEquals(555L, ready.genTime())
    }
    @Test fun optionalPdfContentFailureDoesNotChangeReady() {
        val pdf = LocalPdfResultActionPlugin(); MeetingAppAssembly.registerResultActions(listOf(pdf))
        val prepared = MeetingAppAssembly.prepare(setup(pdf.id()), discovery(), pdf).meeting()!!
        val ready = ready()
        val access = object : ResultActionAccess {
            override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) { callback.onError("本场正文暂不可读") }
            override fun sendEmail(emails: List<String>, url: String, callback: MeetingClient.Callback<Void?>) { fail() }
        }
        assertEquals(ResultActionOutcome.Status.FAILED, execute(prepared, ready, access, Runnable::run).status())
        assertEquals(MeetingResult.Status.READY, ready.status())
    }
    private class PartnerAdapter : ModelAdapter {
        override fun id() = "partner-ws"
        override fun startMessage(grant: ModelSessionGrant) = "start"
        override fun onServerMessage(message: String, listener: TranscriptListener) = ModelSignal.NONE
    }
    private fun setup(id: String) = MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US", false, ResultOptions.action(id), "", emptyList(), false)
    private fun discovery() = MeetingDiscovery(DeploymentOptions(setOf("transcript.realtime"), 600000, true), emptySet())
    private fun ready() = MeetingResult("mtg_same", "本场", 555, MeetingResult.Status.READY, setOf("transcript.final"), null)
    private fun noProtocols() = object : ResultActionAccess {
        override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) { fail("动作不应读取正文") }
        override fun sendEmail(emails: List<String>, url: String, callback: MeetingClient.Callback<Void?>) { fail("动作不应发送邮件") }
    }
    private fun callback(outcome: AtomicReference<ResultActionOutcome>) = object : MeetingClient.Callback<ResultActionOutcome> {
        override fun onSuccess(result: ResultActionOutcome) { outcome.set(result) }
        override fun onError(error: MeetingError) { fail(error.code.name) }
    }
    private fun execute(prepared: MeetingAppAssembly.PreparedMeeting, ready: MeetingResult, access: ResultActionAccess, ui: Executor): ResultActionOutcome {
        val outcome = AtomicReference<ResultActionOutcome>()
        MeetingAppAssembly.resultRegistry().execute(prepared.action()!!,
            ResultActionContext(ready, access, prepared.serviceCapabilities(), Runnable::run, ui), callback(outcome))
        assertNotNull(outcome.get()); return outcome.get()
    }
}
