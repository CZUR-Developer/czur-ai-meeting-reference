package com.czur.aimeeting.reference

import android.app.AlertDialog
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.reference.identity.DefaultAppLoginEntry
import com.czur.aimeeting.reference.identity.PersistentReferenceIdentityProvider
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.client.identity.IdentitySession
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

/**
 * 用真实页面监听、默认身份存储及客户端验证账号正常流程。
 *
 * HTTP 仅由测试进程内的 MockWebServer 返回固定会话／能力，不连接部署服务、数据库
 * 或模型，也不为生产代码增加登录旁路。关闭页面后重建 Provider 核对持久化事实。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class DefaultAccountEntryTest {
    private lateinit var server: MockWebServer
    private lateinit var identity: PersistentReferenceIdentityProvider
    private var controller: ActivityController<MainActivity>? = null

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        MeetingAppAssembly.initialize(context)
        identity = PersistentReferenceIdentityProvider(context)
        identity.clearSession()
        MeetingAppAssembly.registerIdentityProvider(identity)
        MeetingAppAssembly.registerLoginEntry(DefaultAppLoginEntry)
        MeetingAppAssembly.registerResultActions(listOf(WebResultActionPlugin()))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/v1/reference/session:login" -> {
                        val username = JSONObject(request.body.readUtf8()).getString("username")
                        if (username == "bad-user") MockResponse().setResponseCode(401).setBody("{}")
                        else MockResponse().setResponseCode(200).setBody(
                            """{"accessToken":"new-session-fixture","expiresAt":"2099-01-01T00:00:00Z"}"""
                        )
                    }
                    "/v1/capabilities" -> MockResponse().setResponseCode(200).setBody(
                        """{"capabilities":["transcript.realtime"],"languages":["zh-CN"],"resultLinkTtlMs":86400000}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        MeetingAppAssembly.configureBackend(server.url("/").toString())
        MeetingAppAssembly.configureResultWeb("https://result.example.test/index.html")
    }

    @After fun close() {
        controller?.destroy()
        MeetingAppAssembly.configureBackend("")
        identity.clearSession()
        MeetingAppAssembly.configureResultWeb("")
        server.close()
    }

    @Test fun firstLoginStoresTheSuccessfulSessionAndEnablesStart() {
        val activity = openActivity(loggedIn = false)
        page(activity).account.performClick()
        val form = latestDialog()
        assertNotNull(form.findViewById<EditText>(R.id.login_username))

        submit(form, "new-user")
        await { !form.isShowing && page(activity).start.isEnabled }

        assertSession("new-session-fixture", "new-user")
        assertEquals("退出登录", page(activity).account.text.toString())
        assertEquals("new-user", page(activity).accountName.text.toString())
        assertEquals(android.view.View.VISIBLE, page(activity).accountName.visibility)
    }

    @Test fun failedLoginKeepsLoggedOutStateAndStartDisabled() {
        val activity = openActivity(loggedIn = false)
        val form = openLoginForm(activity)
        submit(form, "bad-user")
        await { form.findViewById<TextView>(R.id.login_message).text.toString().contains("登录失败") }

        assertTrue(form.isShowing)
        assertFalse(page(activity).start.isEnabled)
        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, identity.currentIdentity().status())
        assertEquals("登录", page(activity).account.text.toString())
        form.cancel()
        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, identity.currentIdentity().status())
    }

    @Test fun explicitLogoutClearsTheSavedSessionAndDisablesStart() {
        val activity = openActivity(loggedIn = true)
        // 已登录时账号按钮只有一个动作：退出登录，不再弹出账号菜单。
        page(activity).account.performClick()
        await { page(activity).account.text.toString() == "登录" }

        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, identity.currentIdentity().status())
        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, restoredIdentity().currentIdentity().status())
        assertEquals("登录", page(activity).account.text.toString())
        assertFalse(page(activity).start.isEnabled)
        assertEquals(android.view.View.GONE, page(activity).accountName.visibility)
    }

    private fun openActivity(loggedIn: Boolean): MainActivity {
        if (loggedIn) identity.updateSession(IdentitySession("old-session-fixture", "old-user"), "2099-01-01T00:00:00Z")
        val host = Robolectric.buildActivity(MainActivity::class.java).create()
        controller = host
        val activity = host.get()
        MainActivity::class.java.getDeclaredMethod("refreshIdentity", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
        await { if (loggedIn) page(activity).start.isEnabled else page(activity).account.text.toString() == "登录" }
        return activity
    }

    private fun openLoginForm(activity: MainActivity): AlertDialog {
        page(activity).account.performClick()
        return latestDialog().also { assertNotNull(it.findViewById<EditText>(R.id.login_username)) }
    }

    private fun submit(form: AlertDialog, username: String) {
        form.findViewById<EditText>(R.id.login_username).setText(username)
        form.findViewById<EditText>(R.id.login_password).setText("fixture-password")
        form.findViewById<android.view.View>(R.id.login_submit).performClick()
    }

    private fun assertSession(token: String, account: String) {
        for (provider in listOf(identity, restoredIdentity())) {
            val current = provider.currentIdentity()
            assertEquals(IdentityResult.Status.LOGGED_IN, current.status())
            assertEquals(token, current.session()!!.accessToken())
            assertEquals(account, current.session()!!.displayName())
        }
    }

    private fun restoredIdentity() = PersistentReferenceIdentityProvider(RuntimeEnvironment.getApplication())
    private fun page(activity: MainActivity) = field(activity, "page") as MeetingPageViews
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(activity)
    }
    private fun latestDialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()

    /** 客户端在 IO 队列工作；这里只推进主线程回调，不以任意长等待替代业务断言。 */
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("账号交互未在测试时限内完成")
    }
}
