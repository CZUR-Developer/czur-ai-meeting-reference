package com.czur.aimeeting.reference.meeting

import android.content.Context
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingHistoryClient
import com.czur.aimeeting.client.server.HttpMeetingHistoryClient
import com.czur.aimeeting.sdk.AiMeetingClient
import com.czur.aimeeting.sdk.FileAudioArtifactSink
import java.io.File

/** 完整 SDK 装配：模型材料、音源和协议由 SDK 处理，App 只依赖公共会议接口。 */
object MeetingRuntimeFactory {
    @JvmStatic fun create(context: Context, config: MeetingClientConfig): MeetingClient =
        AiMeetingClient(context, config, MeetingAppAssembly.modelAdapters(),
            FileAudioArtifactSink.factory(File(context.filesDir, "meeting-audio"),
                FileAudioArtifactSink.Encoding.OPUS_OR_PCM))

    /** 只读历史会议入口；与实时会议共用同一 config（地址、冻结身份、回调 executor）。 */
    @JvmStatic fun createHistory(config: MeetingClientConfig): MeetingHistoryClient =
        HttpMeetingHistoryClient(config)

    @JvmStatic fun requiresMicrophone() = true
    @JvmStatic fun supportsRetainedAudio() = true
}
