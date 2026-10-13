package com.czur.aimeeting.client.provider;

import static org.junit.Assert.*;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.SegmentStartedEvent;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 固定字段来自国内测试快照+当前TingwuProvider默认代码，不代签今日生产配置。 */
public final class TingwuDomesticSequenceTest {
    @Test public void beginAndPartialReplacementThenFinalAreOneSentence() throws Exception {
        Fixture f = new Fixture(false);
        f.message("SentenceBegin", new JSONObject().put("index", 1).put("time", 100));
        f.message("TranscriptionResultChanged", source(1, "今天", false));
        f.message("TranscriptionResultChanged", source(1, "今天开会", false));
        f.message("SentenceEnd", source(1, "今天开会。", true));
        assertEquals("tingwu-1", f.begins.get(0).segmentId());
        assertEquals(100, f.begins.get(0).startMs());
        assertEquals(3, f.transcripts.size());
        assertEquals("今天开会。", f.transcripts.get(2).text());
        assertEquals(3, f.transcripts.get(2).revision());
        assertTrue(f.transcripts.get(2).isFinal()); // 不等三句，不把全量partial累加。
    }

    @Test public void translatedFinalReleasesSourceInOrderAndStashKeepsItsOwnId() throws Exception {
        Fixture f = new Fixture(true);
        JSONObject first = source(1, "Hello", true).put("stash_result", new JSONObject()
                .put("index", 2).put("text", "Good morning").put("beginTime", 200));
        f.message("SentenceEnd", first);
        assertFalse(f.transcripts.get(0).isFinal());
        f.translation(1, "你好", true, "EN", "CN");
        assertEquals("en-US", f.transcripts.get(f.transcripts.size() - 1).language());
        assertFalse(f.transcripts.get(f.transcripts.size() - 1).isFinal());
        f.translation(1, "你好。", false, "EN", "CN");
        assertEquals("tingwu-1", f.finals().get(0).segmentId());
        assertEquals("en-US", f.finals().get(0).language());
        assertEquals("tingwu-2", f.transcripts.get(f.transcripts.size() - 1).segmentId());
        assertEquals("Good morning", f.transcripts.get(f.transcripts.size() - 1).text());
        assertEquals(200, f.transcripts.get(f.transcripts.size() - 1).startMs());
        assertEquals("tingwu-2", f.begins.get(0).segmentId());
        f.message("SentenceEnd", source(2, "Good morning.", true));
        f.message("SentenceEnd", source(3, "Thank you.", true));
        f.translation(3, "谢谢。", false, "EN", "CN");
        assertEquals(1, f.finals().size()); // 第3句译文早到，仍等待第2句配对。
        f.translation(2, "早上好。", false, "EN", "CN");
        assertEquals(List.of("tingwu-1", "tingwu-2", "tingwu-3"),
                f.finals().stream().map(TranscriptEvent::segmentId).toList());
    }

    @Test public void completedPreservesSourceFinalWhenTranslationHasNotArrived() throws Exception {
        Fixture f = new Fixture(true);
        f.message("SentenceEnd", source(1, "Hello", true));
        assertTrue(f.finals().isEmpty());
        assertEquals(ModelSignal.FINISHED, f.message("TranscriptionCompleted", new JSONObject()));
        assertEquals("Hello", f.finals().get(0).text());
        assertEquals("und", f.finals().get(0).language());
    }

    @Test public void lateLanguageCorrectsFinalFactsWithoutReopeningOrReplacingFinalText() throws Exception {
        Fixture f = new Fixture(false);
        f.message("SentenceEnd", source(1, "Hello.", true));
        f.message("TranscriptionResultChanged", source(1, "旧的短partial", false)
                .put("detected_source_lang", "EN"));
        assertEquals(2, f.transcripts.size());
        TranscriptEvent corrected = f.transcripts.get(1);
        assertTrue(corrected.isFinal());
        assertEquals(2, corrected.revision());
        assertEquals("Hello.", corrected.text());
        assertEquals("en-US", corrected.language());
    }

    @Test public void sameLanguageTranslationDoesNotFinishSourceButEmptyOppositeFinalDoes() throws Exception {
        Fixture f = new Fixture(true);
        f.message("SentenceEnd", source(1, "你好", true));
        f.translation(1, "你好", false, "CN", "CN");
        assertTrue(f.finals().isEmpty());
        f.translation(1, "", false, "CN", "EN");
        assertEquals(1, f.finals().size());
        assertEquals("zh-CN", f.finals().get(0).language());
        assertTrue(f.translations.isEmpty());
    }

    @Test public void providerFailureReportsOnlyMatchedEventAndNumericStatus() throws Exception {
        Logger logger = Logger.getLogger(TingwuModelAdapter.class.getName());
        List<String> diagnostics = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { diagnostics.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            for (String name : List.of("TaskFailed", "TranscriptionFailed")) {
                Fixture f = new Fixture(false);
                String message = new JSONObject().put("header", new JSONObject().put("name", name)
                        .put("status", 40000001).put("status_text", "fixture-private-detail"))
                        .put("payload", new JSONObject().put("result", "fixture-private-transcript")).toString();
                assertThrows(IllegalStateException.class,
                        () -> f.adapter.onServerMessage(message, f.transcripts::add));
                assertTrue(f.transcripts.isEmpty());
            }
            assertEquals(List.of("听悟失败事件 name=TaskFailed status=40000001",
                    "听悟失败事件 name=TranscriptionFailed status=40000001"), diagnostics);
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static JSONObject source(int index, String text, boolean end) throws Exception {
        return new JSONObject().put("index", index).put("result", text).put("time", 300);
    }
    private static final class Fixture {
        final TingwuModelAdapter adapter = new TingwuModelAdapter();
        final List<TranscriptEvent> transcripts = new ArrayList<>();
        final List<TranslationEvent> translations = new ArrayList<>();
        final List<SegmentStartedEvent> begins = new ArrayList<>();
        Fixture(boolean translation) {
            adapter.startMessage(new ModelSessionGrant("one", TingwuModelAdapter.ID,
                    "wss://example.test/model", "2030-01-01T00:00:00Z", null,
                    Map.of("taskId", "one", "translationEnabled", Boolean.toString(translation))));
        }
        ModelSignal message(String name, JSONObject payload) throws Exception {
            return adapter.onServerMessage(new JSONObject().put("header", new JSONObject().put("name", name))
                    .put("payload", payload).toString(), transcripts::add, translations::add, begins::add);
        }
        void translation(int index, String text, boolean partial, String source, String target) throws Exception {
            message("TranslationResultChanged", new JSONObject().put("source_lang", "multilingual")
                    .put("target_lang", target).put("translate_result", new JSONArray().put(new JSONObject()
                            .put("index", index).put("text", text).put("partial", partial)
                            .put("detected_source_lang", source))));
        }
        List<TranscriptEvent> finals() { return transcripts.stream().filter(TranscriptEvent::isFinal).toList(); }
    }
}
