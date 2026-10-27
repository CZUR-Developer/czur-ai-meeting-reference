package com.czur.aimeeting.reference

import android.app.Activity
import android.os.Looper
import android.widget.Button
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.reference.identity.AppLoginEntry
import com.czur.aimeeting.reference.identity.DefaultAppLoginEntry
import com.czur.aimeeting.reference.identity.PersistentReferenceIdentityProvider
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** 只验证登录交互接缝，不连接后端或启动真实外部账号。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLoginEntryTest {
    private lateinit var identity: PersistentReferenceIdentityProvider

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        MeetingAppAssembly.initialize(context)
        identity = PersistentReferenceIdentityProvider(context)
        identity.clearSession()
        MeetingAppAssembly.registerIdentityProvider(identity)
        MeetingAppAssembly.registerLoginEntry(DefaultAppLoginEntry)
        // 已登录分支要先读取身份；部署检查齐全，页面才进入身份刷新而不是停在提示。
        MeetingAppAssembly.configureBackend("https://backend.example.test")
        MeetingAppAssembly.configureResultWeb("https://result.example.test/index.html")
    }

    @After fun restoreDefault() {
        MeetingAppAssembly.registerLoginEntry(DefaultAppLoginEntry)
        MeetingAppAssembly.configureBackend("")
        MeetingAppAssembly.configureResultWeb("")
        identity.clearSession()
        MeetingAppAssembly.registerIdentityProvider(PersistentReferenceIdentityProvider(RuntimeEnvironment.getApplication()))
    }

    @Test fun defaultEntryDelegatesToReferenceFormOnly() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        var opened = 0
        var identityChanged = 0
        DefaultAppLoginEntry.open(activity, { opened++ }, { identityChanged++ })
        assertEquals(1, opened)
        assertEquals(0, identityChanged)
    }

    @Test fun accountButtonInvokesPartnerEntryWithoutOpeningDefaultForm() {
        var opened = 0
        var host: Activity? = null
        MeetingAppAssembly.registerLoginEntry(AppLoginEntry { activity, _, _ ->
            opened++
            host = activity
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        accountButton(activity).performClick()
        assertEquals(1, opened)
        assertSame(activity, host)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        controller.destroy()
    }

    @Test fun loggedInAccountButtonInvokesPartnerSignOutWithoutClearingSession() {
        identity.updateSession(
            IdentitySession("partner-session-fixture", "partner-user"),
            "2099-01-01T00:00:00Z",
        )
        var opens = 0
        var signOuts = 0
        var signOutHost: Activity? = null
        MeetingAppAssembly.registerLoginEntry(object : AppLoginEntry {
            override fun open(activity: Activity, defaultEntry: () -> Unit, onIdentityChanged: () -> Unit) {
                opens++
            }

            override fun signOut(activity: Activity, defaultSignOut: () -> Unit, onIdentityChanged: () -> Unit) {
                signOuts++
                signOutHost = activity
                // 伙伴自己的退出流程接管，不调用 defaultSignOut 清参考会话。
            }
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        refreshIdentity(activity)
        await { accountButton(activity).text.toString() == "退出登录" }

        accountButton(activity).performClick()

        assertEquals(1, signOuts)
        assertEquals(0, opens)
        assertSame(activity, signOutHost)
        // 页面没有绕过插件清理默认会话；伙伴 Provider 的登录态仍由插件决定。
        val current = identity.currentIdentity()
        assertEquals(IdentityResult.Status.LOGGED_IN, current.status())
        assertEquals("partner-session-fixture", current.session()!!.accessToken())
        controller.destroy()
    }

    @Test fun defaultEntrySignsOutTheReferenceSession() {
        identity.updateSession(
            IdentitySession("default-session-fixture", "default-user"),
            "2099-01-01T00:00:00Z",
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        refreshIdentity(activity)
        await { accountButton(activity).text.toString() == "退出登录" }

        accountButton(activity).performClick()

        await { accountButton(activity).text.toString() == "登录" }
        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, identity.currentIdentity().status())
        controller.destroy()
    }

    private fun accountButton(activity: MainActivity): Button = activity.findViewById(R.id.account_button)

    /** 页面只在 onResume 刷新身份；测试直接推进到同一入口，避免真正绑定 Service。 */
    private fun refreshIdentity(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("refreshIdentity", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
    }

    /** 客户端在 IO 队列工作；这里只推进主线程回调，不以任意长等待替代业务断言。 */
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("账号入口未在测试时限内完成")
    }
}
