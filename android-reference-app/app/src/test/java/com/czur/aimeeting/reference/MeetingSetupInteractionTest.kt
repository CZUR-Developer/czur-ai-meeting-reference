package com.czur.aimeeting.reference

import android.content.Context
import android.os.Looper
import android.widget.Spinner
import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.server.DeploymentOptions
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.reference.meeting.MeetingSetupPreferenceStore
import com.czur.aimeeting.reference.meeting.MeetingSetupSelection
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

/** 真实页面监听和 App 偏好接线；不联网、不建会，也不请求 SDK 音源。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class MeetingSetupInteractionTest {
    @Before fun resetBefore() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("czur_reference_meeting_setup", Context.MODE_PRIVATE).edit().clear().commit()
        MeetingAppAssembly.initialize(context)
        MeetingAppAssembly.configureBackend("")
        // 默认 Web 动作需要部署结果页地址；页面测试使用一个固定的有效地址，
        // 不改变被测的开始规则，也不依赖测试执行顺序。
        MeetingAppAssembly.configureResultWeb("https://result.reference.test/index.html")
        MeetingAppAssembly.registerResultActions(listOf(WebResultActionPlugin()))
    }

    @After fun resetAfter() {
        RuntimeEnvironment.getApplication().getSharedPreferences("czur_reference_meeting_setup", Context.MODE_PRIVATE)
            .edit().clear().commit()
        MeetingAppAssembly.configureBackend("")
        MeetingAppAssembly.configureResultWeb("")
    }

    @Test fun languageSwapAndIndependentModePreferencesSurvivePageRecreation() {
        val first = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = first.get()
            discover(activity, FULL_CAPABILITIES, LANGUAGES)
            val page = field(activity, "page") as MeetingPageViews
            page.translationMode.performClick()
            choose(page.sourceLanguage, 1)
            page.summary.performClick()
            assertEquals(MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "de-DE", "fr-FR", true), selection(activity))
            assertEquals(1, page.sourceLanguage.selectedItemPosition)
            assertEquals(0, page.targetLanguage.selectedItemPosition)
            assertTrue(page.start.isEnabled)

            page.transcriptMode.performClick()
            choose(page.sourceLanguage, 2)
            assertEquals("ja-JP", selection(activity).sourceLanguage)
            assertFalse(page.summary.isChecked)
            page.translationMode.performClick()
            assertEquals(MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "de-DE", "fr-FR", true), selection(activity))
            assertTrue(page.summary.isChecked)

            // 同一开始规则既控制按钮，也在真实会前入口拒绝相同的两种语言。
            set(activity, "setupSelection", selection(activity).copy(targetLanguage = "de-DE"))
            invoke(activity, "updateStartAvailability")
            assertFalse(page.start.isEnabled)
            invoke(activity, "continueStartMeeting")
            assertEquals("请选择两种不同的会议语言", page.status.text.toString())
        } finally { first.destroy() }

        val second = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            discover(second.get(), FULL_CAPABILITIES, LANGUAGES)
            assertEquals(MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "de-DE", "fr-FR", true), selection(second.get()))
            val store = MeetingSetupPreferenceStore(second.get())
            assertEquals("ja-JP", store.read(MeetingSetup.Mode.LIVE_TRANSCRIPT).sourceLanguage)
            assertFalse(store.read(MeetingSetup.Mode.LIVE_TRANSCRIPT).summaryEnabled)
        } finally { second.destroy() }
    }

    @Test fun deploymentCapabilityFallbackDoesNotRewriteTheUsersSavedModeOrSummary() {
        val context = RuntimeEnvironment.getApplication()
        val saved = MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "ja-JP", "de-DE", true)
        MeetingSetupPreferenceStore(context).save(saved)
        val host = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = host.get()
            discover(activity, setOf(MeetingCapabilityState.TRANSCRIPT), listOf("fr-FR"))
            val page = field(activity, "page") as MeetingPageViews
            assertEquals(MeetingSetup.Mode.LIVE_TRANSCRIPT, selection(activity).mode)
            assertEquals("fr-FR", selection(activity).sourceLanguage)
            assertFalse(selection(activity).summaryEnabled)
            assertTrue(page.start.isEnabled)
            // 只有一个语种时，隐藏的目标 Spinner 仍会选中第0项并可能延后回调。
            // 经过真实监听和主队列后，它不能把空目标／保存的互译模式改写掉。
            choose(page.targetLanguage, 0)
            assertEquals("", selection(activity).targetLanguage)
            assertEquals(saved, MeetingSetupPreferenceStore(context).read(MeetingSetup.Mode.LIVE_TRANSLATION))
            assertEquals(MeetingSetup.Mode.LIVE_TRANSLATION, MeetingSetupPreferenceStore(context).mode())

            discover(activity, FULL_CAPABILITIES, LANGUAGES)
            assertEquals(saved, selection(activity))
            assertTrue(page.translationMode.isChecked)
            assertTrue(page.summary.isChecked)
            assertTrue(page.start.isEnabled)
        } finally { host.destroy() }
    }

    /** 用发现结果驱动真实重绘；Provider／网络调用不属于这组页面交互测试。 */
    private fun discover(activity: MainActivity, capabilities: Set<String>, languages: List<String>) {
        set(activity, "displayedIdentity", IdentitySession("reference-ui-fixture", "参考测试"))
        set(activity, "deploymentCapabilities", capabilities)
        set(activity, "discovery", MeetingDiscovery(DeploymentOptions(capabilities, 60_000L, false, languages), capabilities))
        MainActivity::class.java.getDeclaredMethod("refreshLanguageOptions", List::class.java)
            .apply { isAccessible = true }.invoke(activity, languages)
        invoke(activity, "refreshResultAvailability")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun choose(spinner: Spinner, position: Int) {
        spinner.setSelection(position)
        spinner.onItemSelectedListener!!.onItemSelected(spinner, spinner.selectedView, position, spinner.selectedItemId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun selection(activity: MainActivity) = field(activity, "setupSelection") as MeetingSetupSelection
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun invoke(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }

    companion object {
        private val FULL_CAPABILITIES = setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION,
            MeetingCapabilityState.SUMMARY_SERVER, MeetingCapabilityState.SUMMARY)
        private val LANGUAGES = listOf("fr-FR", "de-DE", "ja-JP")
    }
}
