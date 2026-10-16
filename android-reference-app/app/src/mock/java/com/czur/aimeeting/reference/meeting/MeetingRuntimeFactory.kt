package com.czur.aimeeting.reference.meeting

import android.content.Context
import android.os.SystemClock
import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingCoordinator
import com.czur.aimeeting.client.MeetingRuntimePort
import com.czur.aimeeting.model.*
import com.czur.aimeeting.reference.mock.MockModelAdapter
import com.czur.aimeeting.reference.mock.MockSummaryAdapter
import java.io.IOException

/**
 * Mock 消费同一装配注册表和协调器；运行端口固定演示字幕、纪要，不领取真实模型材料。
 * 权限与前台服务类型由 App 根据 requiresMicrophone 选择。
 */
object MeetingRuntimeFactory {
    @JvmStatic fun create(context: Context, config: MeetingClientConfig): MeetingClient =
        MeetingCoordinator(config, MeetingAppAssembly.modelAdapters(), object : MeetingRuntimePort {
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
            override fun usesServerModelMaterials() = false
            override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcriptListener: TranscriptListener,
                sessionListener: ModelSessionListener): ModelSession = MockModelAdapter().open(transcriptListener)
            override fun waitsForModelFinish() = false
            override fun localCapabilities() = setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY)
            @Throws(IOException::class)
            override fun summarize(grant: SummarySessionGrant?, adapter: SummaryAdapter?, input: SummaryInput): SummaryResult =
                MockSummaryAdapter().parseResponse("{\"title\":\"参考会议纪要\",\"overview\":\"本场讨论了 AI 会议纪要参考流程。\","
                    + "\"keyPoints\":[\"标准字幕已形成最终结果\"],\"actionItems\":[\"检查会后纪要结果\"]}", input.language())
        })
    @JvmStatic fun requiresMicrophone() = false
    @JvmStatic fun supportsRetainedAudio() = false
}
