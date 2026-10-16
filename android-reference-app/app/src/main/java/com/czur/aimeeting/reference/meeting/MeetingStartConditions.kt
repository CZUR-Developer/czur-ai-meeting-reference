package com.czur.aimeeting.reference.meeting

import android.content.Context
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.result.ResultActionPlugin

/**
 * 伙伴的会前条件装配点。替换 create 返回的实现即可，不改页面、模型运行时或事件链。
 * 参考版不提供扫码接收流程，也不增加额外开会条件。
 */
object MeetingStartConditions {
    /** 在主线程读取伙伴提前准备的本地状态，禁止在此同步网络等待。 */
    @JvmStatic fun create(appContext: Context?): MeetingStartCondition = MeetingStartCondition {
        MeetingStartCondition.Decision.allow()
    }

    /** 在屏采授权、Service 和 Create 前只准备一次，授权返回不重复准备。 */
    @JvmStatic fun prepare(partner: MeetingStartCondition, setup: MeetingSetup,
        discovery: MeetingDiscovery, configuredPlugin: ResultActionPlugin?
    ): MeetingAppAssembly.StartPreparation {
        val decision = partner.check(setup)
        return if (!decision.allowed()) MeetingAppAssembly.StartPreparation(null, decision.message())
        else MeetingAppAssembly.prepare(setup, discovery, configuredPlugin)
    }
}
