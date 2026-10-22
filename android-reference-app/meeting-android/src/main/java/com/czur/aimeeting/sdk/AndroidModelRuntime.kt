package com.czur.aimeeting.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.czur.aimeeting.client.AudioMixOptions
import com.czur.aimeeting.model.AudioDirectionSample
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.AudioInputMode
import com.czur.aimeeting.model.AudioInputLevel
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelRuntime
import com.czur.aimeeting.model.ModelSession
import com.czur.aimeeting.model.ModelSessionConnection
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.ModelSessionReconnector
import com.czur.aimeeting.model.ModelSignal
import com.czur.aimeeting.model.SealedAudioArtifact
import com.czur.aimeeting.model.SegmentStartedEvent
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranscriptListener
import com.czur.aimeeting.model.TranslationEvent
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Optional
import java.util.OptionalLong
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Android 模型运行时，SDK 控制音源与 WebSocket，Adapter 只负责协议和标准事件。
 * 完整地址和启动参数仅留本场，应用收到字幕、译文及必要的技术元数据。
 */
class AndroidModelRuntime @JvmOverloads constructor(context: Context,
    private val artifactSinks: AudioArtifactSink.Factory? = null) : ModelRuntime, AudioMixAwareRuntime {
    private val context = context.applicationContext
    override fun supportsRetainedAudio(): Boolean = artifactSinks != null
    override fun open(grant: ModelSessionGrant, adapter: ModelAdapter,
        transcriptListener: TranscriptListener, sessionListener: ModelSessionListener): ModelSession =
        open(grant, adapter, transcriptListener, sessionListener, false)
    override fun open(grant: ModelSessionGrant, adapter: ModelAdapter,
        transcriptListener: TranscriptListener, sessionListener: ModelSessionListener,
        retainAudio: Boolean): ModelSession =
        open(grant, adapter, transcriptListener, sessionListener, retainAudio, null)

    /** 只替换模型连接，续领端口由协调器冻结本场身份与会议 ID。 */
    override fun open(grant: ModelSessionGrant, adapter: ModelAdapter,
        transcriptListener: TranscriptListener, sessionListener: ModelSessionListener,
        retainAudio: Boolean, reconnector: ModelSessionReconnector?): ModelSession =
        openWithAudioMix(grant, adapter, transcriptListener, sessionListener, retainAudio,
            reconnector, AudioMixOptions())

    /** 本场混流选项交给音源；标准 MIC 或 WAV 调试输入忽略该参数。 */
    override fun openWithAudioMix(grant: ModelSessionGrant, adapter: ModelAdapter,
        transcriptListener: TranscriptListener, sessionListener: ModelSessionListener,
        retainAudio: Boolean, reconnector: ModelSessionReconnector?,
        audioMix: AudioMixOptions): ModelSession {
        check(!retainAudio || artifactSinks != null) { "同源录音尚未装配" }
        require(grant.adapterId() == adapter.id()) { "Adapter 与本场模型授权不匹配" }
        val sink = if (retainAudio) {
            try { artifactSinks!!.create(grant.meetingId()) }
            catch (failure: IOException) { throw IllegalStateException("无法准备本场录音保存", failure) }
        } else null
        return WebSocketModelSession(grant, adapter, transcriptListener, sessionListener,
            SdkAudioSourceFactory.create(context, audioMix), LongSupplier { SystemClock.elapsedRealtime() }, sink,
            reconnector, HTTP)
    }

    internal class WebSocketModelSession @JvmOverloads constructor(
        private val grant: ModelSessionGrant, adapter: ModelAdapter,
        private val transcriptListener: TranscriptListener, private val sessionListener: ModelSessionListener,
        private val audio: PcmSource,
        private val elapsedRealtimeMs: LongSupplier = LongSupplier { SystemClock.elapsedRealtime() },
        private val artifactSink: AudioArtifactSink? = null,
        reconnector: ModelSessionReconnector? = null,
        private val sockets: WebSocket.Factory = HTTP,
    ) : ModelSession, MicPcmSource.FrameSink {
        private val callbacks = SessionCallbackQueue()
        private val levelMeter = Pcm16RmsLevelMeter()
        private val artifactIo = artifactSink?.let { Executors.newSingleThreadExecutor() }
        private val reconnector = if (adapter.supportsSessionReconnect()) reconnector else null
        private val reconnectIo = this.reconnector?.let { Executors.newSingleThreadScheduledExecutor() }
        @Volatile private var artifactFailure: IOException? = null
        private var sealedAudio: SealedAudioArtifact? = null
        private val maxPendingAudioBytes = if (this.reconnector == null)
            MAX_ARTIFACT_PCM_BYTES else MAX_RECONNECT_PCM_BYTES
        private val pendingAudio = ArrayDeque<PcmFrame>()
        private var connection = Connection(0, grant, adapter)
        private var reconnecting = false
        private var reconnectTask: Future<*>? = null
        private var started = false
        private var audioStarting = false
        private var pumping = false
        private var pendingAudioBytes = 0
        private var pendingArtifactPcmBytes = 0
        private var stopRequested = false
        private var inputStopped = false
        private var finished = false
        private var closed = false
        // 模型失败只关模型事件门；取消/真实音源失败才放弃未封口录音。
        private var artifactCancelled = false
        private var closeRequested = false
        // 唯一计数门接纳清理尾帧，发送/网络补发不重复计量或写录音。
        private var capturedPcmBytes = 0L
        private var audioUsageFrozen = false
        private var audioStopping = false
        private var pendingTerminalCallback: Runnable? = null

        /** 每代连接独立解析状态；解析锁内只排队，不调用宿主或执行 HTTP。 */
        private class Connection(val generation: Int, val grant: ModelSessionGrant, val adapter: ModelAdapter) {
            val parsing = Any()
            var socket: WebSocket? = null
            var startMessageSent = false
            var audioReady = false
            var stopMessageSent = false
            var retired = false
            var firstAudioByteOffset = -1L
        }
        private class PcmFrame(pcm: ByteArray, val byteOffset: Long) {
            val bytes: ByteString = pcm.toByteString()
        }

        override fun start() {
            synchronized(this) {
                if (started || closed) return
                started = true
            }
            try { audio.checkPermission() } catch (denied: RuntimeException) {
                failAudio(AudioInputException.codeOf(denied), AudioInputException.messageOf(denied), denied)
                return
            }
            val request = try { request(grant, connection.adapter) } catch (invalidGrant: RuntimeException) {
                fail("模型连接地址无效", invalidGrant)
                return
            }
            // 先采音和方向，连接前完整帧留本场 FIFO；READY 仅打开发送门。
            if (!startAudioBeforeConnect()) return
            connect(connection, request)
        }

        private fun active(current: Connection): Boolean =
            connection === current && !current.retired && !closed && !finished

        private fun connect(current: Connection, request: Request) {
            synchronized(this) { if (!active(current)) return }
            Log.i(TAG, "连接模型 adapter=" + current.adapter.id() + " generation=" + current.generation)
            val connecting = try {
                sockets.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = sendStart(current, webSocket)
                    override fun onMessage(webSocket: WebSocket, text: String) = handleServerMessage(current, text)
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                        connectionLost(current, "模型连接已关闭", null)
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                        connectionLost(current, "模型连接中断", t)
                })
            } catch (failure: RuntimeException) {
                connectionLost(current, "模型连接创建失败", failure)
                return
            }
            val cancel = synchronized(this) {
                (!active(current)).also { if (!it) current.socket = connecting }
            }
            if (cancel) connecting.cancel()
        }

        private fun sendStart(current: Connection, webSocket: WebSocket) {
            val cancel = synchronized(this) { (!active(current)).also { if (!it) current.socket = webSocket } }
            if (cancel) { webSocket.cancel(); return }
            val message = try { synchronized(current.parsing) { current.adapter.startMessage(current.grant) } }
                catch (invalidMessage: RuntimeException) {
                    failConnection(current, "模型启动消息生成失败", invalidMessage)
                    return
                }
            if (!webSocket.send(message)) { connectionLost(current, "模型启动消息发送失败", null); return }
            synchronized(this) {
                if (!active(current)) return
                current.startMessageSent = true
            }
            Log.d(TAG, "模型启动消息已发送，等待音频就绪确认")
            pumpAudio()
        }

        fun handleServerMessage(text: String) {
            val current = synchronized(this) { connection }
            handleServerMessage(current, text)
        }
        private fun handleServerMessage(current: Connection, text: String) {
            val signal: ModelSignal
            try {
                synchronized(current.parsing) {
                    synchronized(this) { if (!active(current)) return }
                    signal = current.adapter.onServerMessage(text,
                        { event -> queueTranscript(current, event) },
                        { event -> queueTranslation(current, event) },
                        { event -> queueSegmentStarted(current, event) })
                }
                callbacks.drain()
            } catch (parseFailure: RuntimeException) {
                // Adapter 已给出的有效事件先交付，不能被失败后的文件封口压住。
                try { callbacks.drain() } finally { failConnection(current, "模型响应解析失败", parseFailure) }
                return
            }
            if (signal == ModelSignal.AUDIO_READY) {
                synchronized(this) {
                    if (!active(current)) return
                    current.audioReady = true
                }
                Log.d(TAG, "模型音频已就绪 generation=" + current.generation)
                pumpAudio()
            } else if (signal == ModelSignal.FINISHED) finish(current)
        }

        /** 状态检查与事件入队共用短锁；宿主回调锁外串行，已接纳事件先于终止通知。 */
        private fun queueTranscript(current: Connection, event: TranscriptEvent) {
            synchronized(this) {
                if (!active(current)) return
                val offset = Math.max(0, current.firstAudioByteOffset) / 32
                val mapped = TranscriptEvent(id(current, event.segmentId()), event.revision(),
                    offset + event.startMs(), offset + event.endMs(), event.language(), event.text(),
                    event.isFinal(), event.sessionSpeakerId()?.let { id(current, it) })
                callbacks.enqueue { transcriptListener.onTranscript(mapped) }
            }
        }
        private fun queueTranslation(current: Connection, event: TranslationEvent) {
            synchronized(this) {
                if (!active(current)) return
                val mapped = TranslationEvent(id(current, event.translationId()), id(current, event.sourceSegmentId()),
                    event.revision(), event.sourceLanguage(), event.targetLanguage(), event.text(), event.isFinal())
                callbacks.enqueue { sessionListener.onTranslation(mapped) }
            }
        }
        private fun queueSegmentStarted(current: Connection, event: SegmentStartedEvent) {
            synchronized(this) {
                if (!active(current)) return
                val mapped = SegmentStartedEvent(id(current, event.segmentId()),
                    Math.max(0, current.firstAudioByteOffset) / 32 + event.startMs())
                callbacks.enqueue { sessionListener.onSegmentStarted(mapped) }
            }
        }

        /** 仅网络传输错误续接；解析、权限或真实音频错误继续原终止路径。 */
        private fun connectionLost(current: Connection, safeMessage: String, failure: Throwable?) {
            val resume: Boolean
            try {
                synchronized(current.parsing) {
                    synchronized(this) { if (!active(current)) return }
                    reconnector?.let { current.adapter.drainConfirmedOnDisconnect { queueTranscript(current, it) } }
                    synchronized(this) {
                        if (!active(current)) return
                        current.retired = true
                        current.audioReady = false
                        resume = reconnector != null && !stopRequested
                        reconnecting = resume
                    }
                }
            } catch (invalidDrain: RuntimeException) { fail("模型确认原文交出失败", invalidDrain); return }
            current.socket?.cancel()
            if (resume) {
                Log.w(TAG, "模型传输中断，保留本场音源并续领材料 generation=" + current.generation +
                    " type=" + (failure?.javaClass?.simpleName ?: "none"))
                scheduleReconnect(if (current.generation == 0) 0 else 2000)
                callbacks.drain()
            } else fail(safeMessage, failure)
        }
        private fun scheduleReconnect(delayMs: Long) {
            synchronized(this) {
                if (closed || finished || stopRequested || !reconnecting) return
                reconnectTask = reconnectIo!!.schedule({ requestReconnect() }, delayMs, TimeUnit.MILLISECONDS)
            }
        }
        private fun requestReconnect() {
            synchronized(this) {
                reconnectTask = null
                if (closed || finished || stopRequested || !reconnecting) return
            }
            val next: ModelSessionConnection
            val request: Request
            try {
                // 此同步 HTTP 只在独立续接线程；Stop 不 join，不等待它返回。
                next = reconnector!!.request()
                require(grant.meetingId() == next.grant().meetingId() &&
                    next.grant().adapterId() == next.adapter().id() && next.adapter().supportsSessionReconnect()) {
                    "模型续接材料与原场或Adapter不一致"
                }
                request = request(next.grant(), next.adapter())
            } catch (unavailable: IOException) {
                synchronized(this) { if (closed || finished || stopRequested) return }
                Log.w(TAG, "本场模型材料暂未取得，稍后续领 type=" + unavailable.javaClass.simpleName)
                scheduleReconnect(2000)
                return
            } catch (invalidMaterials: RuntimeException) { fail("本场模型续接材料无效", invalidMaterials); return }
            val current = synchronized(this) {
                if (closed || finished || stopRequested || !reconnecting) return
                Connection(connection.generation + 1, next.grant(), next.adapter()).also {
                    connection = it
                    reconnecting = false
                }
            }
            Log.i(TAG, "本场模型材料已续领 generation=" + current.generation)
            connect(current, request)
        }
        /** 只取消任务不等 HTTP，忽略中断的迟到结果由状态门丢弃。 */
        private fun cancelReconnect() {
            val io = reconnectIo ?: return
            synchronized(this) { reconnectTask?.cancel(true); reconnectTask = null }
            io.shutdownNow()
        }

        private fun startAudioBeforeConnect(): Boolean {
            synchronized(this) {
                if (closed || finished) return false
                if (inputStopped) return true
                audioStarting = true
            }
            try { audio.start(this) } catch (captureFailure: RuntimeException) {
                synchronized(this) { audioStarting = false }
                failAudio(AudioInputException.codeOf(captureFailure),
                    AudioInputException.messageOf(captureFailure), captureFailure)
                return false
            }
            val stop = synchronized(this) { audioStarting = false; stopRequested || closed || finished }
            if (stop) stopAudioAndSealInput()
            else Log.i(TAG, "模型连接前已采音，输入源=" + audio.label() + "，16kHz mono PCM16")
            return !isCompleteOrClosed()
        }

        override fun onFrame(pcm: ByteArray) {
            val overflow: Boolean
            var droppedBytes = 0
            synchronized(this) {
                val byteOffset = capturedPcmBytes
                if (!audioUsageFrozen) {
                    capturedPcmBytes += pcm.size
                    if (artifactIo != null && artifactFailure == null) {
                        // 唯一计数门内复制入队；媒体 I/O 在会话锁和采音线程之外。
                        if (pcm.size > MAX_ARTIFACT_PCM_BYTES - pendingArtifactPcmBytes) {
                            artifactFailure = IOException("本场录音保存队列已满")
                            artifactIo.execute { Log.w(TAG, "同源录音队列已满，封口不可确认") }
                        } else {
                            pendingArtifactPcmBytes += pcm.size
                            val saved = pcm.clone()
                            artifactIo.execute {
                                try { writeArtifact(saved) }
                                finally { synchronized(this) { pendingArtifactPcmBytes -= saved.size } }
                            }
                        }
                    }
                }
                if (closed || finished || inputStopped) return
                if (reconnector != null) {
                    while (pcm.size > maxPendingAudioBytes - pendingAudioBytes && pendingAudio.isNotEmpty()) {
                        val dropped = pendingAudio.removeFirst()
                        pendingAudioBytes -= dropped.bytes.size
                        droppedBytes += dropped.bytes.size
                    }
                }
                overflow = pcm.size > maxPendingAudioBytes - pendingAudioBytes
                if (!overflow) {
                    pendingAudio.addLast(PcmFrame(pcm, byteOffset))
                    pendingAudioBytes += pcm.size
                    if (!stopRequested) {
                        val level = levelMeter.onFrame(pcm, elapsedRealtimeMs.asLong)
                        if (level != null) callbacks.enqueue { deliverAudioLevel(level) }
                    }
                }
            }
            if (droppedBytes > 0) Log.d(TAG, "网络PCM已达五分钟，只丢最早待发帧 bytes=$droppedBytes")
            if (overflow) fail("模型连接等待过久，音频缓冲已满") else { pumpAudio(); callbacks.drain() }
        }

        private fun deliverAudioLevel(level: AudioInputLevel) {
            synchronized(this) { if (closed || finished || inputStopped || stopRequested) return }
            sessionListener.onAudioLevel(level)
        }
        override fun onCaptureError(message: String) = failAudio(AudioInputErrorCode.READ_FAILED, message)
        override fun onAudioInputMode(mode: AudioInputMode) {
            synchronized(this) {
                if (closed || finished || inputStopped) return
                callbacks.enqueue { sessionListener.onAudioInputMode(mode) }
            }
            callbacks.drain()
        }
        override fun onAudioDirection(sample: AudioDirectionSample) {
            synchronized(this) {
                if (closed || finished || inputStopped) return
                callbacks.enqueue { sessionListener.onAudioDirection(sample) }
            }
            callbacks.drain()
        }
        override fun onInputFinished() { Log.i(TAG, "有限调试音频已读完，停止模型输入"); stopInput() }

        override fun stopInput() {
            val disconnected: Connection?
            val waitForAudioStart: Boolean
            synchronized(this) {
                if (closed || finished || stopRequested) return
                stopRequested = true
                disconnected = if (reconnecting || connection.retired ||
                    connection.generation > 0 && !connection.audioReady) connection else null
                // 初始化同步回调里的停止由 start 返回后接力，不抢关未完成资源。
                waitForAudioStart = audioStarting
            }
            cancelReconnect()
            if (waitForAudioStart) return
            stopAudioAndSealInput()
            if (disconnected != null) {
                try {
                    synchronized(disconnected.parsing) {
                        val shouldDrain = synchronized(this) { active(disconnected) }
                        if (reconnector != null && shouldDrain)
                            disconnected.adapter.drainConfirmedOnDisconnect { queueTranscript(disconnected, it) }
                    }
                } catch (invalidDrain: RuntimeException) { fail("模型确认原文交出失败", invalidDrain); return }
                fail("模型连接已中断，本场输入已停止")
            }
        }

        private fun stopAudioAndSealInput() {
            val stopAudio: Boolean
            synchronized(this) {
                if (audioStopping) return
                stopAudio = !audioUsageFrozen
                if (stopAudio) audioStopping = true
            }
            // 唯一停流者锁外等待读取退出，期间完整尾帧继续计数。
            if (stopAudio) audio.stop()
            val bytes = synchronized(this) {
                audioUsageFrozen = true
                inputStopped = true
                capturedPcmBytes
            }
            pumpAudio()
            // EOF 不等文件封口；终止通知仍由实际停流者在 EOS/fsync 后派发。
            if (stopAudio) finishArtifact(bytes)
            synchronized(this) {
                audioStopping = false
                pendingTerminalCallback?.let { callbacks.enqueue(it); pendingTerminalCallback = null }
            }
            if (stopAudio) Log.i(TAG, "本场采音用量已冻结 bytes=$bytes durationMs=" + bytes / 32)
            callbacks.drain()
            pumpAudio()
        }

        @Synchronized override fun frozenAudioDurationMs(): OptionalLong =
            if (audioUsageFrozen) OptionalLong.of(capturedPcmBytes / 32) else OptionalLong.empty()
        @Synchronized @Throws(IOException::class)
        override fun sealedAudioArtifact(): Optional<SealedAudioArtifact> {
            artifactFailure?.let { throw it }
            return Optional.ofNullable(sealedAudio)
        }
        /** 采音起点即可给出本场录音文件位置；它不是封口事实，恢复时仍需重新读取时长。 */
        @Synchronized override fun audioArtifactFile(): Optional<File> =
            Optional.ofNullable(artifactSink?.file())
        private fun writeArtifact(pcm: ByteArray) {
            if (artifactFailure != null) return
            try { artifactSink!!.write(pcm) } catch (failure: IOException) { rememberArtifactFailure(failure) }
            catch (failure: RuntimeException) { rememberArtifactFailure(failure) }
        }
        private fun rememberArtifactFailure(failure: Exception) {
            val remembered = synchronized(this) {
                (artifactFailure == null).also {
                    if (it) artifactFailure = IOException("本场同源录音未完成封口", failure)
                }
            }
            if (remembered) Log.w(TAG, "同源录音保存未确认 type=" + failure.javaClass.simpleName)
        }
        private fun finishArtifact(bytes: Long) {
            val io = artifactIo ?: return
            val completion = io.submit {
                val cancel = synchronized(this) { artifactCancelled }
                try {
                    if (cancel || artifactFailure != null) artifactSink!!.cancel() else {
                        val result = artifactSink!!.seal(bytes)
                        if (grant.meetingId() != result.meetingId() || result.capturedPcmBytes() != bytes)
                            throw IOException("封口产物与本场实交PCM不一致")
                        synchronized(this) { if (!artifactCancelled) sealedAudio = result }
                    }
                } catch (failure: IOException) { rememberArtifactFailure(failure); artifactSink!!.cancel() }
                catch (failure: RuntimeException) { rememberArtifactFailure(failure); artifactSink!!.cancel() }
            }
            var interrupted = false
            try {
                while (true) {
                    try { completion.get(); break }
                    catch (interruption: InterruptedException) { interrupted = true }
                    catch (failure: ExecutionException) { rememberArtifactFailure(failure); break }
                }
            } finally { io.shutdown(); if (interrupted) Thread.currentThread().interrupt() }
        }

        /** binary/EOF 共用发送者，WebSocket.send 只本地非阻塞入队。 */
        private fun pumpAudio() {
            synchronized(this) { if (pumping) return; pumping = true }
            while (true) {
                val current: Connection
                val frame: PcmFrame?
                synchronized(this) {
                    current = connection
                    if (!active(current) || !current.startMessageSent || !current.audioReady || current.socket == null) {
                        pumping = false
                        return
                    }
                    frame = pendingAudio.peekFirst()
                    if (frame != null) {
                        if (current.firstAudioByteOffset < 0) current.firstAudioByteOffset = frame.byteOffset
                    } else if (inputStopped && !current.stopMessageSent) current.stopMessageSent = true
                    else { pumping = false; return }
                }
                if (frame != null) {
                    if (!current.socket!!.send(frame.bytes)) {
                        connectionLost(current, "模型音频发送失败", null)
                        synchronized(this) { pumping = false }
                        pumpAudio() // 新连接可能同步 READY，补派发防丢唤醒。
                        return
                    }
                    synchronized(this) {
                        if (pendingAudio.peekFirst() === frame) {
                            pendingAudio.removeFirst()
                            pendingAudioBytes -= frame.bytes.size
                        }
                    }
                    continue
                }
                val message = try { synchronized(current.parsing) { current.adapter.stopMessage(current.grant) } }
                    catch (invalidMessage: RuntimeException) {
                        synchronized(this) { pumping = false }
                        failConnection(current, "模型结束消息生成失败", invalidMessage)
                        return
                    }
                if (message != null && !current.socket!!.send(message)) {
                    synchronized(this) { pumping = false }
                    connectionLost(current, "模型结束消息发送失败", null)
                    return
                }
                Log.d(TAG, "模型输入已停止，完整音频已排空，等待最终结果")
            }
        }

        private fun finish(expected: Connection) {
            synchronized(this) {
                if (!active(expected)) return
                finished = true
                inputStopped = true
                pendingAudio.clear()
                pendingAudioBytes = 0
                val current = connection.socket
                pendingTerminalCallback = Runnable {
                    current?.close(1000, null)
                    Log.i(TAG, "模型已结束，最终字幕已回调")
                    sessionListener.onFinished()
                }
            }
            cancelReconnect()
            stopAudioAndSealInput()
        }
        @Synchronized private fun isCompleteOrClosed(): Boolean = finished || closed
        private fun fail(safeMessage: String) = fail(null, safeMessage, null, null)
        private fun fail(safeMessage: String, failure: Throwable?) = fail(null, safeMessage, null, failure)
        private fun failConnection(expected: Connection, safeMessage: String, failure: Throwable?) =
            fail(expected, safeMessage, null, failure)
        private fun failAudio(code: AudioInputErrorCode, safeMessage: String, failure: Throwable? = null) =
            fail(null, safeMessage, code, failure)

        private fun fail(expected: Connection?, safeMessage: String, audioCode: AudioInputErrorCode?, failure: Throwable?) {
            synchronized(this) {
                if (expected != null && !active(expected)) return
                if (audioCode != null && !audioUsageFrozen) artifactCancelled = true
                if (closed || finished) return
                closed = true
                pendingAudio.clear()
                pendingAudioBytes = 0
                val current = connection.socket
                val diagnostic = safeMessage + " type=" + (failure?.javaClass?.simpleName ?: "none") +
                    " causeType=" + (failure?.cause?.javaClass?.simpleName ?: "none") +
                    " audioCode=" + audioCode + " started=" + started +
                    " startSent=" + connection.startMessageSent + " ready=" + connection.audioReady +
                    " generation=" + connection.generation + " reconnecting=" + reconnecting +
                    " stopRequested=" + stopRequested + " inputStopped=" + inputStopped +
                    " audioFrozen=" + audioUsageFrozen + " artifactCancelled=" + artifactCancelled
                pendingTerminalCallback = Runnable {
                    current?.cancel()
                    Log.w(TAG, diagnostic)
                    if (audioCode == null) sessionListener.onError(safeMessage)
                    else sessionListener.onAudioError(audioCode, safeMessage)
                }
            }
            cancelReconnect()
            stopAudioAndSealInput()
        }

        override fun close() {
            val current = synchronized(this) {
                if (closeRequested) return
                closeRequested = true
                artifactCancelled = true
                closed = true
                callbacks.cancelPending()
                pendingTerminalCallback = null
                pendingAudio.clear()
                pendingAudioBytes = 0
                connection.socket
            }
            cancelReconnect()
            stopAudioAndSealInput()
            current?.cancel()
            Log.d(TAG, "模型会话资源已释放")
        }

        private companion object {
            const val MAX_ARTIFACT_PCM_BYTES = 16_000 * 2 * 30
            const val MAX_RECONNECT_PCM_BYTES = 16_000 * 2 * 300
            fun request(grant: ModelSessionGrant, adapter: ModelAdapter): Request =
                ModelHttpRequests.build(grant.endpoint(), adapter.connectionRequest(grant))
            fun id(current: Connection, id: String): String =
                if (current.generation == 0) id else "reconnect-" + current.generation + ":" + id
        }
    }
    private companion object {
        const val TAG = "AiMeetingSdk"
        // 持续模型流读超时无限，连接超时有限。
        val HTTP = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }
}
