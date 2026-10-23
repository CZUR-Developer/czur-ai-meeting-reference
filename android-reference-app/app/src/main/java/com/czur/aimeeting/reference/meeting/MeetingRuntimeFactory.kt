package com.czur.aimeeting.reference.meeting

import android.content.Context
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.sdk.AiMeetingClient
import com.czur.aimeeting.sdk.FileAudioArtifactSink
import java.io.File

/** 完整 SDK 装配：模型材料、音源和协议由 SDK 处理，App 只依赖公共会议接口。 */
object MeetingRuntimeFactory {
    @JvmStatic fun create(context: Context, config: MeetingClientConfig): MeetingClient =
        AiMeetingClient(context, config, MeetingAppAssembly.modelAdapters(),
            FileAudioArtifactSink.factory(File(context.filesDir, "meeting-audio"),
                FileAudioArtifactSink.Encoding.OPUS_OR_PCM))
    @JvmStatic fun requiresMicrophone() = true
    @JvmStatic fun supportsRetainedAudio() = true
}
