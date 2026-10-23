package com.czur.aimeeting.reference

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.FrameLayout
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.MeetingResultContent
import com.czur.aimeeting.client.result.ResultActionAccess
import com.czur.aimeeting.client.result.ResultActionContext
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.result.ResultActionPlugin
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingSetup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 默认 Web 插件保留原链接；回看不依赖仍存活的 Service 或未消费的 UI 操作。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class WebResultActionPluginTest {
    @After fun clearWebConfiguration() = MeetingAppAssembly.configureResultWeb("")

    @Test fun remembersReadyLinkBeforeUiAndNewSettingsOpenOriginalMeetingWithoutSession() {
        val app = RuntimeEnvironment.getApplication()
        MeetingAppAssembly.configureResultWeb("https://result.example.test/index.html?x=1#section")
        val settings = WebResultActionPlugin().createSettings(app)
        assertFalse(settings.view()!!.isEnabled)
        val execution = settings.freeze().get().prepare(MeetingSetup.transcript("zh-CN", "").toOptions()).execution()!!
        MeetingAppAssembly.configureResultWeb("https://another.example.test/")
        val pending = execute(execution, "mtg_original", 555)
        assertEquals(1, pending.size)
        assertNull("后台动作没有强拉页面", shadowOf(app).nextStartedActivity)
        val expected = "https://result.example.test/index.html?x=1&meetingId=mtg_original&genTime=555#section"
        assertEquals(mapOf("last_ready_link" to expected),
            app.getSharedPreferences("czur_reference_web_results", Context.MODE_PRIVATE).all)

        // 丢弃尚未消费的 UI 操作，用新插件与新设置模拟页面／进程重新装配。
        pending.clear()
        val recreated = WebResultActionPlugin().createSettings(app).view()!!
        assertTrue(recreated.isEnabled)
        recreated.performClick()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(ResultActivity::class.java.name, opened.component!!.className)
        assertEquals(expected, opened.getStringExtra(ResultActivity.EXTRA_RESULT_URL))
    }

    @Test fun attachedSettingsRefreshAfterReadyAndAgainWhenTheSameViewReturns() {
        val host = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val activity = host.get()
        MeetingAppAssembly.configureResultWeb("https://result.example.test/")
        val settings = WebResultActionPlugin().createSettings(activity)
        val previous = settings.view()!! as Button
        activity.setContentView(previous)
        val execution = settings.freeze().get().prepare(MeetingSetup.transcript("zh-CN", "").toOptions()).execution()!!
        try {
            assertFalse(previous.isEnabled)
            execute(execution, "mtg_first", 111)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("同一个会前设置页收到READY链接后启用回看", previous.isEnabled)
            previous.performClick()
            assertEquals("https://result.example.test/?meetingId=mtg_first&genTime=111",
                shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.getStringExtra(ResultActivity.EXTRA_RESULT_URL))

            activity.setContentView(FrameLayout(activity))
            execute(execution, "mtg_latest", 222)
            activity.setContentView(previous)
            previous.performClick()
            assertEquals("https://result.example.test/?meetingId=mtg_latest&genTime=222",
                shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.getStringExtra(ResultActivity.EXTRA_RESULT_URL))
        } finally { host.pause().stop().destroy() }
    }

    /** 保留真实 UiOperation 队列，不执行它；本测试没有 Service／Session 或网络正文。 */
    private fun execute(execution: ResultActionPlugin.Execution, meetingId: String, genTime: Long): MutableList<Runnable> {
        val pending = mutableListOf<Runnable>()
        val access = object : ResultActionAccess {
            override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) = fail("Web回看不读取正文")
            override fun sendEmail(recipients: List<String>, resultUrl: String, callback: MeetingClient.Callback<Void?>) = fail("Web回看不发送邮件")
        }
        val ready = MeetingResult(meetingId, "本场标题不会持久化", genTime, MeetingResult.Status.READY, emptySet(), null)
        execution.execute(ResultActionContext(ready, access, emptySet(), { it.run() }, { pending += it }),
            object : MeetingClient.Callback<ResultActionOutcome> {
                override fun onSuccess(result: ResultActionOutcome) = fail("尚未消费UI操作，不提前报告动作完成")
                override fun onError(error: MeetingError) = fail(error.code.name)
            })
        return pending
    }
}
