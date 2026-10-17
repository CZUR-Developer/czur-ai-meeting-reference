package com.czur.aimeeting.client

import com.czur.aimeeting.client.result.ResultOptions
import org.junit.Assert.*
import org.junit.Test

/** 正常会前快照及原公开构造器，不启动 Android、音源或会议。 */
class MeetingOptionsTest {
    @Test fun allLegacyConstructorsKeepReferenceCreateDefaults() {
        val seven = MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.WEB, "https://result.example.test", emptyList())
        val eightLegacy = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.EMAIL, "https://result.example.test", emptyList(), true)
        val action = ResultOptions.action("partner.notice")
        val eightAction = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US",
            false, action, "https://result.example.test", emptyList(), false)
        val nine = MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.LOCAL, "https://result.example.test", emptyList(), false, action)
        for (options in listOf(seven, eightLegacy, eightAction, nine)) {
            assertEquals("参考会议", options.title()); assertEquals("Asia/Shanghai", options.timezone())
            assertFalse(options.retainAudio())
        }
        assertTrue(seven.summaryEnabled()); assertTrue(eightLegacy.summaryEnabled())
        assertFalse(eightAction.summaryEnabled()); assertTrue(nine.summaryEnabled())
        assertSame(action, nine.resultOptions()); assertEquals(MeetingOptions.ResultDelivery.LOCAL, nine.resultDelivery())
    }
    @Test fun createParameterCopyKeepsTheFullImmutableBusinessSnapshot() {
        val emails = arrayListOf("fixture@example.test")
        val action = ResultOptions.action("partner.notice")
        val original = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "en-US", "zh-CN",
            true, MeetingOptions.ResultDelivery.EMAIL, "https://result.example.test", emails, true, action)
        emails.clear()
        val copied = original.withCreateParameters("客户会议", "Europe/Paris", true)
        assertNotSame(original, copied); assertEquals("参考会议", original.title())
        assertEquals("Asia/Shanghai", original.timezone()); assertFalse(original.retainAudio())
        assertEquals("客户会议", copied.title()); assertEquals("Europe/Paris", copied.timezone()); assertTrue(copied.retainAudio())
        assertEquals(original.mode(), copied.mode()); assertEquals(original.sourceLanguage(), copied.sourceLanguage())
        assertEquals(original.targetLanguage(), copied.targetLanguage()); assertEquals(original.presentationCapture(), copied.presentationCapture())
        assertEquals(original.resultDelivery(), copied.resultDelivery()); assertEquals(original.resultWebUrl(), copied.resultWebUrl())
        assertEquals(listOf("fixture@example.test"), copied.resultEmails())
        assertThrows(UnsupportedOperationException::class.java) { (copied.resultEmails() as MutableList<String>).clear() }
        assertEquals(original.summaryEnabled(), copied.summaryEnabled()); assertSame(action, copied.resultOptions())
        assertEquals(original.requestedCapabilities(), copied.requestedCapabilities()); assertEquals(original.targetLanguages(), copied.targetLanguages())
    }

    @Test fun optionalCapabilitiesFollowTheDiscoveredIntersection() {
        val original = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.WEB, "https://result.example.test", emptyList())
        val transcriptOnly = original.withAvailableCapabilities(setOf(MeetingCapabilityState.TRANSCRIPT))
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT), transcriptOnly.requestedCapabilities())
        val withSpeaker = original.withAvailableCapabilities(setOf(MeetingCapabilityState.TRANSCRIPT,
            MeetingCapabilityState.SPEAKER))
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER),
            withSpeaker.requestedCapabilities())
        // 旧直接调用仍保留默认 speaker 请求，避免破坏已有公开构造与 Server 客户端合同。
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER),
            original.requestedCapabilities())
    }

    @Test fun serverPostMeetingIdentityNeedsExplicitDiscoverySpeakerAndRetainedAudio() {
        val original = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.WEB, "https://result.example.test", emptyList())
        val available = setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER,
            MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING)
        assertFalse(original.withAvailableCapabilities(available).requestedCapabilities()
            .contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
        val retained = original.withCreateParameters("留音会议", "Asia/Shanghai", true)
        assertFalse("旧直接调用不因留音自动请求未发现的会后能力", retained.requestedCapabilities()
            .contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER,
            MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING),
            retained.withAvailableCapabilities(available).requestedCapabilities())
        assertFalse(retained.withAvailableCapabilities(available - MeetingCapabilityState.SPEAKER)
            .requestedCapabilities().contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
    }

    @Test fun disablingRetainedAudioAlsoDropsNegotiatedCrossMeetingIdentity() {
        val negotiated = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.WEB, "https://result.example.test", emptyList())
            .withCreateParameters("留音会议", "Asia/Shanghai", true)
            .withAvailableCapabilities(setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER,
                MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
        val noAudio = negotiated.withCreateParameters("不留音", "Asia/Shanghai", false)
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER),
            noAudio.requestedCapabilities())
        assertFalse(noAudio.withCreateParameters("重新留音", "Asia/Shanghai", true).requestedCapabilities()
            .contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
        assertTrue("复制不改变原场快照", negotiated.requestedCapabilities()
            .contains(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING))
    }

    @Test fun publishedTwelveArgumentJvmSignaturesRemainAvailable() {
        // 直接核对 JVM 描述符，避免“默认参数在 Kotlin 中可调用”掩盖已编译消费者的 ABI 变化。
        val parameters = arrayOf(MeetingOptions.Mode::class.java, String::class.java,
            String::class.java, java.lang.Boolean.TYPE, MeetingOptions.ResultDelivery::class.java,
            String::class.java, List::class.java, java.lang.Boolean.TYPE, ResultOptions::class.java,
            String::class.java, String::class.java, java.lang.Boolean.TYPE)
        assertNotNull(MeetingOptions::class.java.getConstructor(*parameters))
        assertEquals(MeetingOptions::class.java,
            MeetingOptions::class.java.getMethod("copy", *parameters).returnType)
        val defaultParameters = arrayOf(MeetingOptions::class.java, *parameters,
            Integer.TYPE, Any::class.java)
        assertEquals(MeetingOptions::class.java,
            MeetingOptions::class.java.getMethod("copy\$default", *defaultParameters).returnType)
    }

    @Test fun negotiatedCapabilitiesStayFrozenWhenCreateParametersAreCopied() {
        val original = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.WEB, "https://result.example.test", emptyList())
        val available = mutableSetOf(MeetingCapabilityState.TRANSCRIPT)
        val negotiated = original.withAvailableCapabilities(available)
        available.add(MeetingCapabilityState.SPEAKER)
        val copied = negotiated.withCreateParameters("已协商会议", "Asia/Shanghai", true)
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT), negotiated.requestedCapabilities())
        assertEquals(negotiated.requestedCapabilities(), copied.requestedCapabilities())
        assertEquals("已协商会议", copied.title()); assertTrue(copied.retainAudio())
        assertThrows(UnsupportedOperationException::class.java) {
            (copied.requestedCapabilities() as MutableList<String>).clear()
        }
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER),
            original.requestedCapabilities())
    }
}
