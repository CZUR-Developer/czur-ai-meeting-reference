package com.czur.aimeeting.reference.meeting

import org.junit.Assert.*
import org.junit.Test
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.server.DeploymentOptions
import com.czur.aimeeting.reference.WebResultActionPlugin

class MeetingStartConditionTest {
    @Test fun defaultReferenceConditionAllowsMeeting() {
        val setup = MeetingSetup.transcript("zh-CN", "https://result.example.test/")
        assertTrue(MeetingStartConditions.create(null).check(setup).allowed())
    }
    @Test fun partnerConditionCanBlockWithVisibleReason() {
        val partner = MeetingStartCondition { MeetingStartCondition.Decision.block("请先完成合作伙伴的会前条件") }
        val decision = partner.check(MeetingSetup.transcript("zh-CN", "https://result.example.test/"))
        assertFalse(decision.allowed()); assertEquals("请先完成合作伙伴的会前条件", decision.message())
    }
    @Test fun composedHookKeepsPartnerReasonAndBlocksUnpreparedWebBeforeHandoff() {
        val discovery = MeetingDiscovery(DeploymentOptions(emptySet(), 600000, false), emptySet())
        val setup = MeetingSetup.transcript("zh-CN", ""); val web = WebResultActionPlugin()
        val blocked = MeetingStartConditions.prepare({ MeetingStartCondition.Decision.block("请先完成伙伴准备") }, setup, discovery, web)
        assertFalse(blocked.allowed()); assertEquals("请先完成伙伴准备", blocked.message())
        val action = MeetingStartConditions.prepare(MeetingStartConditions.create(null), setup, discovery, web)
        assertFalse(action.allowed()); assertEquals("请填写有效的网页结果地址", action.message())
        assertNull(MeetingAppAssembly.consumePreparedMeeting())
    }
}
