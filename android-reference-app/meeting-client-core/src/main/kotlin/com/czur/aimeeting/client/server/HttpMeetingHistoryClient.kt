package com.czur.aimeeting.client.server

import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.MeetingErrorCode
import com.czur.aimeeting.client.MeetingHistoryClient
import com.czur.aimeeting.client.MeetingHistoryPage
import com.czur.aimeeting.client.MeetingResult
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 只读历史会议客户端：复用 [HttpMeetingServerClient] 的地址、鉴权、超时和 JSON 解析，
 * 不向 Activity 暴露 HTTP/OkHttp/token。回调用配置里的串行 executor 派发。
 */
class HttpMeetingHistoryClient(config: MeetingClientConfig) : MeetingHistoryClient {
    private val server = HttpMeetingServerClient(config)
    private val callbacks: Executor = config.callbackExecutor()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "meeting-history").apply { isDaemon = true }
    }

    override fun discover(callback: MeetingClient.Callback<DeploymentOptions>) =
        execute(callback, MeetingErrorCode.DISCOVERY_FAILED) { server.discover() }

    override fun list(pageToken: String?, pageSize: Int,
                      callback: MeetingClient.Callback<MeetingHistoryPage>) =
        execute(callback, MeetingErrorCode.STEP_FAILED) { server.listMeetings(pageToken, pageSize) }

    override fun readResult(meetingId: String, genTime: Long, includeContent: Boolean,
                            callback: MeetingClient.Callback<MeetingResult>) =
        execute(callback, MeetingErrorCode.RESULT_NOT_READY) {
            server.readResult(meetingId, genTime, includeContent)
        }

    override fun sendEmail(meetingId: String, recipients: List<String>, resultUrl: String,
                           callback: MeetingClient.Callback<Void?>) {
        execute<Void?>(callback, MeetingErrorCode.RESULT_ACTION_FAILED) {
            server.sendResultEmail(meetingId, recipients, resultUrl)
            null
        }
    }

    private fun <T> execute(callback: MeetingClient.Callback<T>, errorCode: MeetingErrorCode,
                            block: () -> T) {
        io.execute {
            try {
                val value = block()
                callbacks.execute { callback.onSuccess(value) }
            } catch (failure: IOException) {
                callbacks.execute { callback.onError(MeetingError(errorCode)) }
            } catch (failure: RuntimeException) {
                callbacks.execute { callback.onError(MeetingError(errorCode)) }
            }
        }
    }

    override fun close() {
        io.shutdownNow()
    }
}
