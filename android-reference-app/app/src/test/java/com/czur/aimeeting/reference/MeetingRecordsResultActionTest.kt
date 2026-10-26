package com.czur.aimeeting.reference

import android.content.Intent
import android.os.Looper
import android.widget.LinearLayout
import com.czur.aimeeting.client.identity.IdentityProvider
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 历史记录的结果动作必须和主会议流程一样先注入页面打开器再执行；
 * 只用全局注册的原型准备会得到 openPage=null，点击记录后不会打开结果页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class MeetingRecordsResultActionTest {
    private lateinit var server: MockWebServer

    @Before fun prepare() {
        MeetingAppAssembly.initialize(RuntimeEnvironment.getApplication())
        MeetingAppAssembly.registerResultActions(listOf(WebResultActionPlugin()))
        MeetingAppAssembly.registerIdentityProvider(
            IdentityProvider { IdentityResult.loggedIn(IdentitySession("history-token", "history-user")) })
        MeetingAppAssembly.configureResultWeb("https://result.partner.test/index.html")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/v1/capabilities") -> MockResponse().setResponseCode(200)
                            .setBody("""{"capabilities":["transcript.realtime"],""" +
                                """"resultLinkTtlMs":600000,"resultEmailEnabled":false,""" +
                                """"languages":["zh-CN","en-US"]}""")
                        path.startsWith("/v1/meetings/mtg_history/manifest") -> MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"title":"历史会议","status":"READY","artifacts":[]}""")
                        path.startsWith("/v1/meetings") -> MockResponse().setResponseCode(200)
                            .setBody("""{"items":[{"meetingId":"mtg_history","title":"历史会议",""" +
                                """"state":"READY","manifestState":"READY",""" +
                                """"createdAt":"2026-10-20T00:00:00Z",""" +
                                """"updatedAt":"2026-10-20T00:01:00Z"}],""" +
                                """"nextPageToken":null}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        MeetingAppAssembly.configureBackend(server.url("/").toString())
    }

    @After fun close() {
        MeetingAppAssembly.configureBackend("")
        MeetingAppAssembly.configureResultWeb("")
        server.close()
    }

    @Test fun historyRowUsesInjectedAndroidOpenerAndStartsResultPage() {
        val controller = Robolectric.buildActivity(MeetingRecordsActivity::class.java).create()
        val activity = controller.get()
        try {
            val list = activity.findViewById<LinearLayout>(R.id.records_list)
            waitUntil { list.childCount > 0 }
            assertTrue("历史记录应渲染出可点击的行", list.childCount > 0)

            list.getChildAt(0).performClick()

            var opened: Intent? = null
            waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
                opened != null
            }
            assertNotNull("点击历史记录后应实际调用注入的 Android 打开器", opened)
            val started = requireNotNull(opened)
            assertEquals(ResultActivity::class.java.name, started.component?.className)
            val url = started.getStringExtra(ResultActivity.EXTRA_RESULT_URL).orEmpty()
            assertTrue("应打开本场结果链接，实际=$url",
                url.startsWith("https://result.partner.test/index.html?"))
            assertTrue(url, url.contains("meetingId=mtg_history"))
        } finally {
            controller.destroy()
        }
    }

    /** 网络与回调都在后台线程；主队列只有在测试线程 idle 时才推进。 */
    private fun waitUntil(timeoutNanos: Long = TimeUnit.SECONDS.toNanos(8),
                          condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutNanos
        while (!condition()) {
            if (System.nanoTime() >= deadline) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }
}
