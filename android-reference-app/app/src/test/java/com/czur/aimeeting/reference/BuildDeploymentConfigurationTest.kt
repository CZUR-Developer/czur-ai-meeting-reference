package com.czur.aimeeting.reference

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.reference.identity.PersistentReferenceIdentityProvider
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.ReferenceDeployment
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
            .putString("backend_url", ReferenceDeployment.BACKEND_URL + "/different-old-backend")
            .putString("result_web_url", "https://old-result.example.test/ignored.html")
            .commit()
        saveLogin()

        MeetingAppAssembly.initialize(context)

        assertEquals(ReferenceDeployment.BACKEND_URL, MeetingAppAssembly.serverUrl())
        assertEquals(ReferenceDeployment.RESULT_WEB_URL, MeetingAppAssembly.resultWebUrl())
        assertEquals(ReferenceDeployment.BACKEND_URL, settings.getString("backend_url", null))
        assertEquals(IdentityResult.Status.LOGIN_REQUIRED, restoredIdentity().currentIdentity().status())
    }

    @Test fun sameBackendKeepsValidLoginWhenOnlyTheWebAddressOrTrailingSlashChanges() {
        settings.edit()
            .putString("backend_url", ReferenceDeployment.BACKEND_URL.trimEnd('/') + "/")
            .putString("result_web_url", "https://old-result.example.test/ignored.html")
            .commit()
        saveLogin()

        MeetingAppAssembly.initialize(context)

        assertEquals(ReferenceDeployment.BACKEND_URL, MeetingAppAssembly.serverUrl())
        assertEquals(ReferenceDeployment.RESULT_WEB_URL, MeetingAppAssembly.resultWebUrl())
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

            assertEquals("部署地址未配置：请按接入文档填写本地部署配置后重新构建。", activity.findViewById<TextView>(R.id.status).text.toString())
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

    @Test fun placeholderBackendIsTreatedAsNotConfigured() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            // 仓内默认地址是占位值；伙伴忘了填本地配置时，页面必须给出可执行的提示。
            MeetingAppAssembly.configureBackend("https://replace-with-your-backend.example")
            MainActivity::class.java.getDeclaredMethod("refreshIdentity", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false)

            assertEquals("部署地址未配置：请按接入文档填写本地部署配置后重新构建。",
                activity.findViewById<TextView>(R.id.status).text.toString())
            assertFalse(activity.findViewById<View>(R.id.start_meeting).isEnabled)
        } finally {
            controller.destroy()
        }
    }

    @Test fun missingResultWebShowsItsOwnHintAndBlocksStart() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            MeetingAppAssembly.configureBackend("https://backend.example.test:8443")
            MeetingAppAssembly.configureResultWeb("")
            MainActivity::class.java.getDeclaredMethod("refreshIdentity", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false)

            assertEquals("结果页地址未配置：请在本地部署配置里填写 resultWebUrl 后重新构建。",
                activity.findViewById<TextView>(R.id.status).text.toString())
            assertFalse(activity.findViewById<View>(R.id.start_meeting).isEnabled)
            assertEquals(MeetingAppAssembly.DeploymentIssue.RESULT_WEB,
                MeetingAppAssembly.deploymentIssue())
        } finally {
            MeetingAppAssembly.configureResultWeb("")
            controller.destroy()
        }
    }

    @Test fun idleServiceBindingKeepsTheActivityLoginAndDeploymentMessages() {
        val activityController = Robolectric.buildActivity(MainActivity::class.java).create()
        val serviceController = Robolectric.buildService(MeetingService::class.java).create()
        try {
            val activity = activityController.get()
            val status = activity.findViewById<TextView>(R.id.status)
            val connection = MainActivity::class.java.getDeclaredField("serviceConnection")
                .apply { isAccessible = true }.get(activity) as ServiceConnection
            val service = serviceController.get()
            fun bindIdleService() = connection.onServiceConnected(
                ComponentName(activity, MeetingService::class.java), service.onBind(Intent())
            )

            // 身份交互在 Activity 中完成；此处只验证随后空闲绑定不篡改其已显示的提示。
            status.text = "登录成功，可开始会议"
            bindIdleService()
            assertEquals("登录成功，可开始会议", status.text.toString())

            MeetingAppAssembly.configureBackend("")
            MainActivity::class.java.getDeclaredMethod("refreshIdentity", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false)
            bindIdleService()
            assertEquals("部署地址未配置：请按接入文档填写本地部署配置后重新构建。", status.text.toString())
        } finally {
            activityController.destroy()
            serviceController.destroy()
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
