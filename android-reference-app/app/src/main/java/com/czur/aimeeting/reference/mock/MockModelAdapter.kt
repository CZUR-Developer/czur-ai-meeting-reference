package com.czur.aimeeting.reference.mock

import com.czur.aimeeting.model.ModelSession
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranscriptListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 固定模拟模型：同一片段依次给出初稿、修订稿与最终文字。 */
class MockModelAdapter {
    fun id() = "reference-mock"
    fun open(listener: TranscriptListener): ModelSession = MockSession(listener)

    private class MockSession(private val listener: TranscriptListener) : ModelSession {
        private val executor = Executors.newSingleThreadScheduledExecutor()
        override fun start() {
            emit(0, 1, "今天我们讨论 AI", false)
            emit(1200, 2, "今天我们讨论 AI 会议", false)
            emit(2400, 3, "今天我们讨论 AI 会议纪要。", true)
        }
        private fun emit(delayMs: Long, revision: Int, text: String, isFinal: Boolean) {
            executor.schedule({ listener.onTranscript(TranscriptEvent("mock-segment-1", revision, 0, 0,
                "zh-CN", text, isFinal)) }, delayMs, TimeUnit.MILLISECONDS)
        }
        override fun close() { executor.shutdownNow() }
        override fun stopInput() = close()
    }
}
