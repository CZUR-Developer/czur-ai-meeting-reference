package com.czur.aimeeting.reference.tingwu
import com.czur.aimeeting.client.provider.TingwuModelAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSignal
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import java.util.ArrayList
import java.util.List
import java.util.Map
import org.json.JSONObject
import org.junit.Test
/** 用真实回包字段结构重建的无敏感文本样例核对起流门禁与 partial/final 映射。 */
class TingwuModelAdapterTest {
    @Test fun normalizesAllProductLanguagesInSourceAndTranslationEvents() {
        val provider = arrayOf("CN", "EN", "ja", "ko", "de", "fr", "ru", "JP", "KR")
        val standard = arrayOf("zh-CN", "en-US", "ja-JP", "ko-KR", "de-DE", "fr-FR", "ru-RU",
                "ja-JP", "ko-KR")
        for (index in provider.indices) {
            val adapter = TingwuModelAdapter()
            adapter.startMessage(ModelSessionGrant("mtg_languages", TingwuModelAdapter.ID,
                    "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                    mapOf("taskId" to "task-language", "translationEnabled" to "true")))
            val transcripts = ArrayList<TranscriptEvent>()
            val translations = ArrayList<TranslationEvent>()
            adapter.onServerMessage(JSONObject()
                    .put("header", JSONObject().put("name", "SentenceEnd"))
                    .put("payload", JSONObject().put("index", 1).put("time", 1000)
                            .put("result", "source").put("detected_source_lang", provider[index]))
                    .toString(), transcripts::add, translations::add)
            val other = (index + 1) % provider.size
            adapter.onServerMessage(JSONObject()
                    .put("header", JSONObject().put("name", "TranslationResultChanged"))
                    .put("payload", JSONObject().put("source_lang", "multilingual")
                            .put("target_lang", provider[other])
                            .put("translate_result", org.json.JSONArray().put(JSONObject()
                                    .put("index", 1).put("text", "translation").put("partial", false)
                                    .put("detected_source_lang", provider[index]))))
                    .toString(), transcripts::add, translations::add)
            assertEquals(standard[index], transcripts[transcripts.size - 1].language())
            assertTrue(transcripts[transcripts.size - 1].isFinal())
            assertEquals(standard[index], translations[0].sourceLanguage())
            assertEquals(standard[other], translations[0].targetLanguage())
        }
    }

    @Test fun startsOnlyAfterAckAndMapsRevisionsToOneStandardSegment() {
        val adapter = TingwuModelAdapter()
        val grant = ModelSessionGrant("mtg_test", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                mapOf("taskId" to "task-1"))
        val start = JSONObject(adapter.startMessage(grant))
        assertEquals("task-1", start.getJSONObject("header").getString("task_id"))
        assertEquals("StartTranscription", start.getJSONObject("header").getString("name"))
        assertTrue(start.getJSONObject("payload").getBoolean("enable_intermediate_result"))
        assertFalse(start.getJSONObject("payload").getBoolean("enable_translate_result"))
        assertFalse(start.toString().contains("wss://"))
        val events = ArrayList<TranscriptEvent>()
        assertEquals(ModelSignal.AUDIO_READY, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionStarted\"}}", events::add))
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"SentenceBegin\"},"
                        + "\"payload\":{\"index\":0,\"time\":100}}", events::add))
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                        + "\"payload\":{\"index\":0,\"time\":300,"
                        + "\"result\":\"今天\"}}",
                events::add))
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                        + "\"payload\":{\"index\":0,\"time\":600,"
                        + "\"result\":\"今天开会\"}}",
                events::add))
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"SentenceEnd\"},"
                        + "\"payload\":{\"index\":0,\"begin_time\":100,\"time\":620,"
                        + "\"result\":\"今天开会。\","
                        + "\"stash_result\":{\"index\":1,\"text\":\"下一句\"}}}",
                events::add))
        assertEquals(ModelSignal.FINISHED, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionCompleted\"},\"payload\":{}}",
                events::add))
        assertEquals(3, events.size)
        assertEquals(events[0].segmentId(), events[2].segmentId())
        assertEquals(1, events[0].revision())
        assertEquals(3, events[2].revision())
        assertEquals("und", events[2].language())
        assertEquals(100, events[2].startMs())
        assertEquals(620, events[2].endMs())
        assertFalse(events[0].isFinal())
        assertTrue(events[2].isFinal())
        val stop = JSONObject(adapter.stopMessage(grant))
        assertEquals("StopTranscription", stop.getJSONObject("header").getString("name"))
    }

    /** 按国内版字段解析路径重建固定样例；不代表已通过真实听悟互译会话。 */
    @Test fun translationPartialAndFinalLinkToSourceAndUseDetectedLanguage() {
        val adapter = TingwuModelAdapter()
        val grant = ModelSessionGrant("mtg_translation", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                mapOf("taskId" to "task-translation", "translationEnabled" to "true"))
        assertTrue(JSONObject(adapter.startMessage(grant)).getJSONObject("payload")
                .getBoolean("enable_translate_result"))
        val transcripts = ArrayList<TranscriptEvent>()
        val translations = ArrayList<TranslationEvent>()
        adapter.onServerMessage("{\"header\":{\"name\":\"SentenceEnd\"},"
                + "\"payload\":{\"index\":7,\"result\":\"Hello.\","
                + "\"detected_source_lang\":\"EN\"}}", transcripts::add,
                translations::add)
        adapter.onServerMessage("{\"header\":{\"name\":\"TranslationResultChanged\"},"
                + "\"payload\":{\"source_lang\":\"multilingual\",\"target_lang\":\"CN\","
                + "\"translate_result\":[{\"index\":7,\"text\":\"你\","
                + "\"partial\":true,\"detected_source_lang\":\"EN\"}]}}",
                transcripts::add, translations::add)
        adapter.onServerMessage("{\"header\":{\"name\":\"TranslationResultChanged\"},"
                + "\"payload\":{\"source_lang\":\"multilingual\",\"target_lang\":\"CN\","
                + "\"translate_result\":[{\"index\":7,\"text\":\"你好。\","
                + "\"partial\":false,\"detected_source_lang\":\"EN\"}]}}",
                transcripts::add, translations::add)
        assertEquals(2, transcripts.size)
        assertFalse(transcripts[0].isFinal())
        assertTrue(transcripts[1].isFinal())
        assertEquals(2, translations.size)
        assertEquals("tingwu-7", translations[0].sourceSegmentId())
        assertEquals(translations[0].translationId(), translations[1].translationId())
        assertEquals(1, translations[0].revision())
        assertEquals(2, translations[1].revision())
        assertEquals("en-US", translations[1].sourceLanguage())
        assertEquals("zh-CN", translations[1].targetLanguage())
        assertFalse(translations[0].isFinal())
        assertTrue(translations[1].isFinal())
    }

    @Test fun ignoresUnknownAndSameLanguageTranslation() {
        val adapter = TingwuModelAdapter()
        adapter.startMessage(ModelSessionGrant("mtg_translation", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                mapOf("taskId" to "task-translation", "translationEnabled" to "true")))
        val translations = ArrayList<TranslationEvent>()
        adapter.onServerMessage("{\"payload\":{\"source_lang\":\"multilingual\","
                + "\"target_lang\":\"EN\",\"translate_result\":["
                + "{\"index\":1,\"text\":\"unknown\",\"partial\":true},"
                + "{\"index\":2,\"text\":\"same\",\"detected_source_lang\":\"EN\"},"
                + "{\"index\":3,\"text\":\"Good morning.\","
                + "\"detected_source_lang\":\"CN\"}]}}",
                { _ -> }, translations::add)
        assertEquals(1, translations.size)
        assertEquals("tingwu-3", translations[0].sourceSegmentId())
        assertEquals("zh-CN", translations[0].sourceLanguage())
        assertEquals("en-US", translations[0].targetLanguage())
    }

    @Test fun lateSourceLanguageWithSameTextIsNotDiscarded() {
        val adapter = TingwuModelAdapter()
        val events = ArrayList<TranscriptEvent>()
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"Hello\"}}", events::add)
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"Hello\","
                + "\"detected_source_lang\":\"EN\"}}", events::add)
        assertEquals(2, events.size)
        assertEquals("und", events[0].language())
        assertEquals("en-US", events[1].language())
        assertEquals(2, events[1].revision())
    }

    @Test fun lateSessionSpeakerWithSameTextCreatesRevisionAndMissingSpeakerStaysUnknown() {
        val adapter = TingwuModelAdapter()
        val events = ArrayList<TranscriptEvent>()
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"你好\"}}", events::add)
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"你好\",\"SpeakerId\":\"speaker-2\"}}",
                events::add)
        adapter.onServerMessage("{\"header\":{\"name\":\"SentenceEnd\"},"
                + "\"payload\":{\"index\":2,\"result\":\"你好。\"}}", events::add)
        adapter.onServerMessage("{\"header\":{\"name\":\"SentenceEnd\"},"
                + "\"payload\":{\"index\":3,\"result\":\"下一句。\"}}", events::add)
        assertEquals(4, events.size)
        assertNull(events[0].sessionSpeakerId())
        assertEquals(2, events[1].revision())
        assertEquals("speaker-2", events[1].sessionSpeakerId())
        assertEquals("speaker-2", events[2].sessionSpeakerId())
        assertTrue(events[2].isFinal())
        assertNull(events[3].sessionSpeakerId())
    }

    @Test fun singleLanguageGrantDoesNotEmitUnexpectedTranslation() {
        val adapter = TingwuModelAdapter()
        val grant = ModelSessionGrant("mtg_transcript", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                mapOf("taskId" to "task-transcript"))
        assertFalse(JSONObject(adapter.startMessage(grant)).getJSONObject("payload")
                .getBoolean("enable_translate_result"))
        val translations = ArrayList<TranslationEvent>()
        adapter.onServerMessage("{\"payload\":{\"source_lang\":\"EN\",\"target_lang\":\"CN\","
                + "\"translate_result\":[{\"index\":1,\"text\":\"你好\","
                + "\"detected_source_lang\":\"EN\"}]}}",
                { _ -> }, translations::add)
        assertTrue(translations.isEmpty())
    }
}
