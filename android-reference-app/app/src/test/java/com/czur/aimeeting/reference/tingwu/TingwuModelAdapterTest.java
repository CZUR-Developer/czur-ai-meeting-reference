package com.czur.aimeeting.reference.tingwu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** 用真实回包字段结构重建的无敏感文本样例核对起流门禁与 partial/final 映射。 */
public final class TingwuModelAdapterTest {
    @Test
    public void startsOnlyAfterAckAndMapsRevisionsToOneStandardSegment() throws Exception {
        TingwuModelAdapter adapter = new TingwuModelAdapter();
        ModelSessionGrant grant = new ModelSessionGrant("mtg_test", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                Map.of("taskId", "task-1"));
        JSONObject start = new JSONObject(adapter.startMessage(grant));
        assertEquals("task-1", start.getJSONObject("header").getString("task_id"));
        assertEquals("StartTranscription", start.getJSONObject("header").getString("name"));
        assertTrue(start.getJSONObject("payload").getBoolean("enable_intermediate_result"));
        assertFalse(start.getJSONObject("payload").getBoolean("enable_translate_result"));
        assertFalse(start.toString().contains("wss://"));

        List<TranscriptEvent> events = new ArrayList<>();
        assertEquals(ModelSignal.AUDIO_READY, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionStarted\"}}", events::add));
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"SentenceBegin\"},"
                        + "\"payload\":{\"index\":0,\"time\":100}}", events::add));
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                        + "\"payload\":{\"index\":0,\"time\":300,"
                        + "\"result\":\"今天\"}}",
                events::add));
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                        + "\"payload\":{\"index\":0,\"time\":600,"
                        + "\"result\":\"今天开会\"}}",
                events::add));
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(
                "{\"header\":{\"name\":\"SentenceEnd\"},"
                        + "\"payload\":{\"index\":0,\"begin_time\":100,\"time\":620,"
                        + "\"result\":\"今天开会。\","
                        + "\"stash_result\":{\"index\":1,\"text\":\"下一句\"}}}",
                events::add));
        assertEquals(ModelSignal.FINISHED, adapter.onServerMessage(
                "{\"header\":{\"name\":\"TranscriptionCompleted\"},\"payload\":{}}",
                events::add));

        assertEquals(3, events.size());
        assertEquals(events.get(0).segmentId(), events.get(2).segmentId());
        assertEquals(1, events.get(0).revision());
        assertEquals(3, events.get(2).revision());
        assertEquals("und", events.get(2).language());
        assertEquals(100, events.get(2).startMs());
        assertEquals(620, events.get(2).endMs());
        assertFalse(events.get(0).isFinal());
        assertTrue(events.get(2).isFinal());
        JSONObject stop = new JSONObject(adapter.stopMessage(grant));
        assertEquals("StopTranscription", stop.getJSONObject("header").getString("name"));
    }

    /** 按国内版字段解析路径重建固定样例；不代表已通过真实听悟互译会话。 */
    @Test
    public void translationPartialAndFinalLinkToSourceAndUseDetectedLanguage() throws Exception {
        TingwuModelAdapter adapter = new TingwuModelAdapter();
        ModelSessionGrant grant = new ModelSessionGrant("mtg_translation", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                Map.of("taskId", "task-translation", "translationEnabled", "true"));
        assertTrue(new JSONObject(adapter.startMessage(grant)).getJSONObject("payload")
                .getBoolean("enable_translate_result"));
        List<TranscriptEvent> transcripts = new ArrayList<>();
        List<TranslationEvent> translations = new ArrayList<>();
        adapter.onServerMessage("{\"header\":{\"name\":\"SentenceEnd\"},"
                + "\"payload\":{\"index\":7,\"result\":\"Hello.\","
                + "\"detected_source_lang\":\"EN\"}}", transcripts::add,
                translations::add);
        adapter.onServerMessage("{\"header\":{\"name\":\"TranslationResultChanged\"},"
                + "\"payload\":{\"source_lang\":\"multilingual\",\"target_lang\":\"CN\","
                + "\"translate_result\":[{\"index\":7,\"text\":\"你\","
                + "\"partial\":true,\"detected_source_lang\":\"EN\"}]}}",
                transcripts::add, translations::add);
        adapter.onServerMessage("{\"header\":{\"name\":\"TranslationResultChanged\"},"
                + "\"payload\":{\"source_lang\":\"multilingual\",\"target_lang\":\"CN\","
                + "\"translate_result\":[{\"index\":7,\"text\":\"你好。\","
                + "\"partial\":false,\"detected_source_lang\":\"EN\"}]}}",
                transcripts::add, translations::add);
        assertEquals(1, transcripts.size());
        assertEquals(2, translations.size());
        assertEquals("tingwu-7", translations.get(0).sourceSegmentId());
        assertEquals(translations.get(0).translationId(), translations.get(1).translationId());
        assertEquals(1, translations.get(0).revision());
        assertEquals(2, translations.get(1).revision());
        assertEquals("en-US", translations.get(1).sourceLanguage());
        assertEquals("zh-CN", translations.get(1).targetLanguage());
        assertFalse(translations.get(0).isFinal());
        assertTrue(translations.get(1).isFinal());
    }

    @Test
    public void ignoresUnknownAndSameLanguageTranslation() {
        TingwuModelAdapter adapter = new TingwuModelAdapter();
        adapter.startMessage(new ModelSessionGrant("mtg_translation", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                Map.of("taskId", "task-translation", "translationEnabled", "true")));
        List<TranslationEvent> translations = new ArrayList<>();
        adapter.onServerMessage("{\"payload\":{\"source_lang\":\"multilingual\","
                + "\"target_lang\":\"EN\",\"translate_result\":["
                + "{\"index\":1,\"text\":\"unknown\",\"partial\":true},"
                + "{\"index\":2,\"text\":\"same\",\"detected_source_lang\":\"EN\"},"
                + "{\"index\":3,\"text\":\"Good morning.\","
                + "\"detected_source_lang\":\"CN\"}]}}",
                event -> {}, translations::add);
        assertEquals(1, translations.size());
        assertEquals("tingwu-3", translations.get(0).sourceSegmentId());
        assertEquals("zh-CN", translations.get(0).sourceLanguage());
        assertEquals("en-US", translations.get(0).targetLanguage());
    }

    @Test
    public void lateSourceLanguageWithSameTextIsNotDiscarded() {
        TingwuModelAdapter adapter = new TingwuModelAdapter();
        List<TranscriptEvent> events = new ArrayList<>();
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"Hello\"}}", events::add);
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"Hello\","
                + "\"detected_source_lang\":\"EN\"}}", events::add);
        assertEquals(2, events.size());
        assertEquals("und", events.get(0).language());
        assertEquals("en-US", events.get(1).language());
        assertEquals(2, events.get(1).revision());
    }

    @Test
    public void lateSessionSpeakerWithSameTextCreatesRevisionAndMissingSpeakerStaysUnknown() {
        TingwuModelAdapter adapter = new TingwuModelAdapter();
        List<TranscriptEvent> events = new ArrayList<>();
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"你好\"}}", events::add);
        adapter.onServerMessage("{\"header\":{\"name\":\"TranscriptionResultChanged\"},"
                + "\"payload\":{\"index\":2,\"result\":\"你好\",\"SpeakerId\":\"speaker-2\"}}",
                events::add);
        adapter.onServerMessage("{\"header\":{\"name\":\"SentenceEnd\"},"
                + "\"payload\":{\"index\":2,\"result\":\"你好。\"}}", events::add);
        adapter.onServerMessage("{\"header\":{\"name\":\"SentenceEnd\"},"
                + "\"payload\":{\"index\":3,\"result\":\"下一句。\"}}", events::add);
        assertEquals(4, events.size());
        assertNull(events.get(0).sessionSpeakerId());
        assertEquals(2, events.get(1).revision());
        assertEquals("speaker-2", events.get(1).sessionSpeakerId());
        assertEquals("speaker-2", events.get(2).sessionSpeakerId());
        assertTrue(events.get(2).isFinal());
        assertNull(events.get(3).sessionSpeakerId());
    }

    @Test
    public void singleLanguageGrantDoesNotEmitUnexpectedTranslation() throws Exception {
        TingwuModelAdapter adapter = new TingwuModelAdapter();
        ModelSessionGrant grant = new ModelSessionGrant("mtg_transcript", TingwuModelAdapter.ID,
                "wss://example.test/signed", "2030-01-01T00:01:00Z", null,
                Map.of("taskId", "task-transcript"));
        assertFalse(new JSONObject(adapter.startMessage(grant)).getJSONObject("payload")
                .getBoolean("enable_translate_result"));
        List<TranslationEvent> translations = new ArrayList<>();
        adapter.onServerMessage("{\"payload\":{\"source_lang\":\"EN\",\"target_lang\":\"CN\","
                + "\"translate_result\":[{\"index\":1,\"text\":\"你好\","
                + "\"detected_source_lang\":\"EN\"}]}}",
                event -> {}, translations::add);
        assertTrue(translations.isEmpty());
    }
}
