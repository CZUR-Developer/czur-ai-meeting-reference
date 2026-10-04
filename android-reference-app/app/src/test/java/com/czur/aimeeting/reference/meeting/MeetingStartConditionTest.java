package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 合作伙伴条件默认放行；拒绝理由原样交给会前页面展示。 */
public final class MeetingStartConditionTest {
    @Test
    public void defaultReferenceConditionAllowsMeeting() {
        MeetingSetup setup = MeetingSetup.transcript("zh-CN", "https://result.example.test/");
        assertTrue(MeetingStartConditions.create(null).check(setup).allowed());
    }

    @Test
    public void partnerConditionCanBlockWithVisibleReason() {
        MeetingStartCondition partner = setup ->
                MeetingStartCondition.Decision.block("请先完成合作伙伴的会前条件");
        MeetingStartCondition.Decision decision = partner.check(
                MeetingSetup.transcript("zh-CN", "https://result.example.test/"));
        assertFalse(decision.allowed());
        assertEquals("请先完成合作伙伴的会前条件", decision.message());
    }
}
