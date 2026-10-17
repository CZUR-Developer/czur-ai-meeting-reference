package com.czur.aimeeting.reference

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.reference.identity.PersistentReferenceIdentityProvider
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** 只验证 APK 地址与已保存身份的正常装配，不联网、不操作设备、不增生产配置旁路。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class BuildDeploymentConfigurationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val settings get() = context.getSharedPreferences("czur_reference_settings", Context.MODE_PRIVATE)

    @Before fun prepareFreshProcess() {
        settings.edit().clear().commit()
        PersistentReferenceIdentityProvider(context).clearSession()
        allowNextInitialization()
    }

    @After fun restoreAssembly() {
        MeetingAppAssembly.clearReferenceSession()
        MeetingAppAssembly.configureBackend("")
        MeetingAppAssembly.configureResultWeb("")
        allowNextInitialization()
    }

    @Test fun compiledEndpointsReplaceOldInputsAndDoNotReuseAnotherBackendIdentity() {
        settings.edit()
            .putString("backend_url", BuildConfig.REFERENCE_BACKEND_URL + "/different-old-backend")
            .putString("result_web_url", "https://old-result.example.test/ignored.html")
            .commit()
        saveLogin()

        MeetingAppAssembly.initialize(context)

        assertEquals(BuildConfig.REFERENCE_BACKEND_URL, MeetingAppAssembly.serverUrl())
        assertEquals(BuildConfig.REFERENCE_RESULT_WEB_URL, MeetingAppAssembly.resultWebUrl())
        assertEquals(BuildConfig.REFERENCE_BACKEND_URL, settings.getString("backend_url", null))
        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, restoredIdentity().currentIdentity().status())
    }

    @Test fun sameBackendKeepsValidLoginWhenOnlyTheWebAddressOrTrailingSlashChanges() {
        settings.edit()
            .putString("backend_url", BuildConfig.REFERENCE_BACKEND_URL.trimEnd('/') + "/")
            .putString("result_web_url", "https://old-result.example.test/ignored.html")
            .commit()
        saveLogin()

        MeetingAppAssembly.initialize(context)

        assertEquals(BuildConfig.REFERENCE_BACKEND_URL, MeetingAppAssembly.serverUrl())
        assertEquals(BuildConfig.REFERENCE_RESULT_WEB_URL, MeetingAppAssembly.resultWebUrl())
        val identity = restoredIdentity().currentIdentity()
        assertEquals(IdentityResult.Status.LOGGED_IN, identity.status())
        assertEquals("saved-session-fixture", identity.session()!!.accessToken())
    }

    @Test fun missingDeploymentShowsProviderHintWithoutAUserAddressEntry() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            // 开发者装配接口也用于本机测试；不为测试另造终端用户的地址输入入口。
            MeetingAppAssembly.configureBackend("")
            MainActivity::class.java.getDeclaredMethod("refreshIdentity", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false)
            activity.findViewById<View>(R.id.account_button).performClick()

            assertEquals("部署尚未配置，请联系应用提供方。", activity.findViewById<TextView>(R.id.status).text.toString())
            assertFalse(activity.findViewById<View>(R.id.start_meeting).isEnabled)
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            for (name in listOf("settings_button", "backend_url", "web_url"))
                assertEquals(0, activity.resources.getIdentifier(name, "id", activity.packageName))
            val activities = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_ACTIVITIES).activities
            assertTrue(activities.orEmpty().none { it.name.endsWith(".ConnectionSettingsActivity") })
        } finally {
            controller.destroy()
        }
    }

    private fun saveLogin() {
        restoredIdentity().updateSession(IdentitySession("saved-session-fixture", "saved-user"), "2099-01-01T00:00:00Z")
    }

    private fun restoredIdentity() = PersistentReferenceIdentityProvider(context)

    /** 模拟进程首次装配；仅测试复位一次性标记，不给 App 增加运行时配置或重载 API。 */
    private fun allowNextInitialization() {
        MeetingAppAssembly::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .setBoolean(null, false)
    }
}
