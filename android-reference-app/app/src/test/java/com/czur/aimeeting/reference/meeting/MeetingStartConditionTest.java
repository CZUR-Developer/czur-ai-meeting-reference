package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import com.czur.aimeeting.client.MeetingDiscovery;
import com.czur.aimeeting.client.server.DeploymentOptions;
import com.czur.aimeeting.reference.WebResultActionPlugin;
import java.util.Set;

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

    @Test public void composedHookKeepsPartnerReasonAndBlocksUnpreparedWebBeforeHandoff() {
        var discovery = new MeetingDiscovery(new DeploymentOptions(Set.of(), 600000, false), Set.of());
        var setup = MeetingSetup.transcript("zh-CN", "");
        var web = new WebResultActionPlugin();
        var partnerBlocked = MeetingStartConditions.prepare(ignored ->
                MeetingStartCondition.Decision.block("请先完成伙伴准备"), setup, discovery, web);
        assertFalse(partnerBlocked.allowed());
        assertEquals("请先完成伙伴准备", partnerBlocked.message());
        var actionBlocked = MeetingStartConditions.prepare(MeetingStartConditions.create(null), setup, discovery, web);
        assertFalse(actionBlocked.allowed());
        assertEquals("请填写有效的网页结果地址", actionBlocked.message());
        assertEquals(null, MeetingAppAssembly.consumePreparedMeeting());
    }
}
