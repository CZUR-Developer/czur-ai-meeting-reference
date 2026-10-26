package com.czur.aimeeting.reference

import com.czur.aimeeting.client.result.ResultOptions
import com.czur.aimeeting.reference.meeting.MeetingSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 历史页不挂载邮件插件的两个输入框；空白输入应回落到调用方冻结的地址和收件人，
 * 不能把主会议流程原本的校验顺序或报错文案一起改掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class EmailResultActionPluginTest {
    @Test fun blankPageFieldsUseOptionsFrozenForHistory() {
        val options = setup("https://result.example.test/index.html",
            listOf("prepared@example.test")).toOptions()
        val preparation = EmailResultActionPlugin().createSettings(RuntimeEnvironment.getApplication())
            .freeze().get().prepare(options)
        assertTrue(preparation.message(), preparation.allowed())
    }

    @Test fun blankPageFieldsStillBlockWithoutFrozenRecipients() {
        val preparation = EmailResultActionPlugin().createSettings(RuntimeEnvironment.getApplication())
            .freeze().get().prepare(setup("https://result.example.test/index.html", emptyList()).toOptions())
        assertEquals("请填写1～10个不重复的有效收件邮箱后开始会议", preparation.message())
    }

    private fun setup(url: String, emails: List<String>) = MeetingSetup(
        MeetingSetup.Mode.MEETING_MINUTES, "zh-CN", "en-US", false,
        ResultOptions.action("email"), url, emails, true
    )
}
