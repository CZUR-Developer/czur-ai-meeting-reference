package com.czur.aimeeting.client.provider

import org.junit.Assert.*
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSignal
import com.czur.aimeeting.model.SegmentStartedEvent
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import java.util.ArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** 固定字段来自国内测试快照+当前TingwuProvider默认代码，不代签今日生产配置。 */
class TingwuDomesticSequenceTest {
    @Test fun beginAndPartialReplacementThenFinalAreOneSentence() {
        val f = Fixture(false)
        f.message("SentenceBegin", JSONObject().put("index", 1).put("time", 100))
        f.message("TranscriptionResultChanged", source(1, "今天", false))
        f.message("TranscriptionResultChanged", source(1, "今天开会", false))
        f.message("SentenceEnd", source(1, "今天开会。", true))
        assertEquals("tingwu-1", f.begins.get(0).segmentId())
        assertEquals(100, f.begins.get(0).startMs())
        assertEquals(3, f.transcripts.size)
        assertEquals("今天开会。", f.transcripts.get(2).text())
        assertEquals(3, f.transcripts.get(2).revision())
        assertTrue(f.transcripts.get(2).isFinal()) // 不等三句，不把全量partial累加。
    }

    @Test fun translatedFinalReleasesSourceInOrderAndStashKeepsItsOwnId() {
        val f = Fixture(true)
        val first = source(1, "Hello", true).put("stash_result", JSONObject()
                .put("index", 2).put("text", "Good morning").put("beginTime", 200))
        f.message("SentenceEnd", first)
        assertFalse(f.transcripts.get(0).isFinal())
        f.translation(1, "你好", true, "EN", "CN")
        assertEquals("en-US", f.transcripts.get(f.transcripts.size - 1).language())
        assertFalse(f.transcripts.get(f.transcripts.size - 1).isFinal())
        f.translation(1, "你好。", false, "EN", "CN")
        assertEquals("tingwu-1", f.finals().get(0).segmentId())
        assertEquals("en-US", f.finals().get(0).language())
        assertEquals("tingwu-2", f.transcripts.get(f.transcripts.size - 1).segmentId())
        assertEquals("Good morning", f.transcripts.get(f.transcripts.size - 1).text())
        assertEquals(200, f.transcripts.get(f.transcripts.size - 1).startMs())
        assertEquals("tingwu-2", f.begins.get(0).segmentId())
        f.message("SentenceEnd", source(2, "Good morning.", true))
        f.message("SentenceEnd", source(3, "Thank you.", true))
        f.translation(3, "谢谢。", false, "EN", "CN")
        assertEquals(1, f.finals().size) // 第3句译文早到，仍等待第2句配对。
        f.translation(2, "早上好。", false, "EN", "CN")
        assertEquals(listOf("tingwu-1", "tingwu-2", "tingwu-3"),
                f.finals().stream().map(TranscriptEvent::segmentId).toList())
    }

    @Test fun completedPreservesSourceFinalWhenTranslationHasNotArrived() {
        val f = Fixture(true)
        f.message("SentenceEnd", source(1, "Hello", true))
        assertTrue(f.finals().isEmpty())
        assertEquals(ModelSignal.FINISHED, f.message("TranscriptionCompleted", JSONObject()))
        assertEquals("Hello", f.finals().get(0).text())
        assertEquals("und", f.finals().get(0).language())
    }

    @Test fun disconnectDrainsOnlyConfirmedSourceOnceWithoutFinishingPartial() {
        val f = Fixture(true)
        assertTrue(f.adapter.supportsSessionReconnect())
        f.message("SentenceEnd", source(1, "Hello.", true).put("stash_result", JSONObject()
                .put("index", 2).put("text", "Unfinished").put("beginTime", 400)))
        f.message("TranscriptionResultChanged", source(2, "Still unfinished", false))
        assertTrue("互译原文尚在等待译文配对", f.finals().isEmpty())
        f.adapter.drainConfirmedOnDisconnect(f.transcripts::add)
        assertEquals(listOf("tingwu-1"), f.finals().stream().map(TranscriptEvent::segmentId).toList())
        assertEquals("Hello.", f.finals().get(0).text())
        assertFalse(f.transcripts.stream().filter { event -> event.segmentId() == "tingwu-2" }
                .anyMatch(TranscriptEvent::isFinal))
        f.adapter.drainConfirmedOnDisconnect(f.transcripts::add)
        assertEquals("重复交出不重发已经final的原文", 1, f.finals().size)
        assertTrue("未收到的译文不能伪造", f.translations.isEmpty())
    }

    @Test fun waitingTranslationKeepsConfirmedTextAgainstLatePartialAndStash() {
        val f = Fixture(true)
        f.message("SentenceEnd", source(1, "Confirmed sentence.", true).put("begin_time", 100))
        // 原文已确认但译文尚未到达：旧 partial 只允许补语种、发言人，不能改正文或时间。
        f.message("TranscriptionResultChanged", source(1, "Late unfinished partial", false)
                .put("begin_time", 900).put("time", 1000)
                .put("detected_source_lang", "EN").put("speaker_id", "speaker-2"))
        val waiting = f.transcripts.get(f.transcripts.size - 1)
        assertEquals("Confirmed sentence.", waiting.text())
        assertEquals(100, waiting.startMs())
        assertEquals(300, waiting.endMs())
        assertEquals("en-US", waiting.language())
        assertEquals("speaker-2", waiting.sessionSpeakerId())
        assertTrue("译文未到，暂不提前结束显示片段", f.finals().isEmpty())
        // 延迟消息携带的 stash 同样不是 SentenceEnd，不得覆盖等待配对的确认原文。
        f.message("TranscriptionResultChanged", source(2, "Other unfinished sentence", false)
                .put("stash_result", JSONObject().put("index", 1)
                        .put("text", "Stale stash text").put("beginTime", 900)))
        f.adapter.drainConfirmedOnDisconnect(f.transcripts::add)
        assertEquals(1, f.finals().size)
        val confirmed = f.finals().get(0)
        assertEquals("tingwu-1", confirmed.segmentId())
        assertEquals("Confirmed sentence.", confirmed.text())
        assertEquals(100, confirmed.startMs())
        assertEquals(300, confirmed.endMs())
        assertEquals("en-US", confirmed.language())
        assertEquals("speaker-2", confirmed.sessionSpeakerId())
        assertTrue("断线交出不生成不存在的译文", f.translations.isEmpty())
    }

    @Test fun lateLanguageCorrectsFinalFactsWithoutReopeningOrReplacingFinalText() {
        val f = Fixture(false)
        f.message("SentenceEnd", source(1, "Hello.", true))
        f.message("TranscriptionResultChanged", source(1, "旧的短partial", false)
                .put("detected_source_lang", "EN"))
        assertEquals(2, f.transcripts.size)
        val corrected = f.transcripts.get(1)
        assertTrue(corrected.isFinal())
        assertEquals(2, corrected.revision())
        assertEquals("Hello.", corrected.text())
        assertEquals("en-US", corrected.language())
    }

    @Test fun sameLanguageTranslationDoesNotFinishSourceButEmptyOppositeFinalDoes() {
        val f = Fixture(true)
        f.message("SentenceEnd", source(1, "你好", true))
        f.translation(1, "你好", false, "CN", "CN")
        assertTrue(f.finals().isEmpty())
        f.translation(1, "", false, "CN", "EN")
        assertEquals(1, f.finals().size)
        assertEquals("zh-CN", f.finals().get(0).language())
        assertTrue(f.translations.isEmpty())
    }

    @Test fun providerFailureReportsOnlyMatchedEventAndNumericStatus() {
        val logger = Logger.getLogger(TingwuModelAdapter::class.java.name)
        val diagnostics = ArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { diagnostics.add(record.message) }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            for (name in listOf("TaskFailed", "TranscriptionFailed")) {
                val f = Fixture(false)
                val message = JSONObject().put("header", JSONObject().put("name", name)
                        .put("status", 40000001).put("status_text", "fixture-private-detail"))
                        .put("payload", JSONObject().put("result", "fixture-private-transcript")).toString()
                assertThrows(IllegalStateException::class.java) { f.adapter.onServerMessage(message, f.transcripts::add) }
                assertTrue(f.transcripts.isEmpty())
            }
            assertEquals(listOf("听悟失败事件 name=TaskFailed status=40000001",
                    "听悟失败事件 name=TranscriptionFailed status=40000001"), diagnostics)
        } finally {
            logger.removeHandler(handler)
        }
    }

    private fun source(index: Int, text: String, end: Boolean): JSONObject {
        return JSONObject().put("index", index).put("result", text).put("time", 300)
    }
    private class Fixture(translation: Boolean) {
        val adapter = TingwuModelAdapter()
        val transcripts = ArrayList<TranscriptEvent>()
        val translations = ArrayList<TranslationEvent>()
        val begins = ArrayList<SegmentStartedEvent>()
        init {
            adapter.startMessage(ModelSessionGrant("one", TingwuModelAdapter.ID,
                    "wss://example.test/model", "2030-01-01T00:00:00Z", null,
                    mapOf("taskId" to "one", "translationEnabled" to translation.toString())))
        }
        fun message(name: String, payload: JSONObject): ModelSignal {
            return adapter.onServerMessage(JSONObject().put("header", JSONObject().put("name", name))
                    .put("payload", payload).toString(), transcripts::add, translations::add, begins::add)
        }
        fun translation(index: Int, text: String, partial: Boolean, source: String, target: String) {
            message("TranslationResultChanged", JSONObject().put("source_lang", "multilingual")
                    .put("target_lang", target).put("translate_result", JSONArray().put(JSONObject()
                            .put("index", index).put("text", text).put("partial", partial)
                            .put("detected_source_lang", source))))
        }
        fun finals(): List<TranscriptEvent> { return transcripts.stream().filter(TranscriptEvent::isFinal).toList(); }
    }
}
