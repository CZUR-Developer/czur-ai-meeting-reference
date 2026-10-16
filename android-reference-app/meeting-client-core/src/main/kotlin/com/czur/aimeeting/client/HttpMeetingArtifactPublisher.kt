package com.czur.aimeeting.client

import com.czur.aimeeting.client.server.HttpMeetingServerClient
import java.io.IOException

/**
 * 参考客户端的默认录音发布器。它只在会议正常收尾时接收 SDK 已封口的文件，
 * 通过公共 Server HTTP 端口登记原始二进制；不读取音频到内存、不创建旁路状态，
 * 也不把文件路径、访问凭据或对象存储地址写入日志。
 */
class HttpMeetingArtifactPublisher : MeetingArtifactPublisher {
    override fun supportsAudioPublication(): Boolean = true

    @Throws(IOException::class)
    override fun publish(context: MeetingArtifactPublisher.Context) {
        HttpMeetingServerClient(context.serverUrl(), context.accessToken())
            .publishAudio(context.meetingId(), context.audio())
    }
}
