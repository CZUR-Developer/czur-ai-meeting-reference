package com.czur.aimeeting.reference

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 历史会议页必须走集中装配：伙伴替换 IdentityProvider 和后端地址后，这里要跟着变，
 * 不能自己 new 默认 Provider 或直读编译期地址。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class MeetingRecordsAssemblyTest {
    private lateinit var server: MockWebServer
    private val session = IdentitySession("partner-session-token", "partner-user")

    @Before fun prepare() {
        MeetingAppAssembly.initialize(RuntimeEnvironment.getApplication())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path?.startsWith("/v1/meetings") == true) {
                        MockResponse().setResponseCode(200).setBody("""{"items":[],"nextPageToken":null}""")
                    } else {
                        MockResponse().setResponseCode(404)
                    }
            }
            start()
        }
        MeetingAppAssembly.configureBackend(server.url("/").toString())
        MeetingAppAssembly.configureResultWeb("https://result.partner.test/index.html")
        MeetingAppAssembly.registerIdentityProvider(IdentityProvider { IdentityResult.loggedIn(session) })
    }

    @After fun close() {
        MeetingAppAssembly.configureBackend("")
        MeetingAppAssembly.configureResultWeb("")
        server.close()
    }

    @Test fun recordsQueryUsesRegisteredIdentityAndConfiguredBackend() {
        assertEquals(com.czur.aimeeting.client.identity.IdentityResult.Status.LOGGED_IN,
            MeetingAppAssembly.currentIdentity().status())
        assertEquals(server.url("/").toString(), MeetingAppAssembly.serverUrl())
        val activity = Robolectric.buildActivity(MeetingRecordsActivity::class.java).create().get()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var request: RecordedRequest? = null
        while (System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val candidate = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            // 历史页先做匿名发现再拉列表；测试只关心最终列表请求。
            if (candidate.path?.startsWith("/v1/meetings") == true) {
                request = candidate
                break
            }
        }
        assertTrue("历史会议页应使用集中装配后的后端地址，实际 status=" +
            activity.window.decorView.findViewById<android.widget.TextView>(R.id.records_status)?.text,
            request != null)
        assertTrue(request!!.path!!.startsWith("/v1/meetings"))
        assertEquals("Bearer partner-session-token", request.getHeader("Authorization"))
    }
}
