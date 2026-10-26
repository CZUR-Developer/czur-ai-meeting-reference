package com.czur.aimeeting.reference

import android.content.Intent
import android.webkit.WebView
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.ReferenceDeployment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 进程被回收后从最近任务恢复结果页：页面必须先自行装配再校验地址。
 *
 * <p>合法链接不关闭并加载原链接；换了主机的结果页地址仍然拒绝。两条断言都不
 * 依赖主页面先跑过 `MeetingAppAssembly.initialize`。</p>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class ResultActivityTest {
    @Before fun simulateColdProcess() {
        // 模拟进程首次装配；仅测试复位一次性标记，不给 App 增加运行时重载 API。
        MeetingAppAssembly::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .setBoolean(null, false)
    }

    @Test fun allowedLinkLoadsWhenResultPageItselfInitializesAssembly() {
        val url = WebResultActionPlugin.resultUrl(ReferenceDeployment.RESULT_WEB_URL,
            "mtg_restored", 555L)
        val controller = Robolectric.buildActivity(ResultActivity::class.java, intent(url)).create()
        try {
            val activity = controller.get()
            assertFalse("合法链接不应被关闭", activity.isFinishing)
            assertEquals(url, shadowOf(activity.findViewById<WebView>(R.id.result_web)).lastLoadedUrl)
        } finally {
            controller.destroy()
        }
    }

    @Test fun foreignHostIsStillRejectedAfterInitialization() {
        val controller = Robolectric.buildActivity(ResultActivity::class.java,
            intent("https://result.example.invalid/index.html")).create()
        try {
            val activity = controller.get()
            assertTrue("非法链接仍应关闭", activity.isFinishing)
            assertNull(shadowOf(activity.findViewById<WebView>(R.id.result_web)).lastLoadedUrl)
        } finally {
            controller.destroy()
        }
    }

    private fun intent(url: String) = Intent(RuntimeEnvironment.getApplication(),
        ResultActivity::class.java).putExtra(ResultActivity.EXTRA_RESULT_URL, url)
}
