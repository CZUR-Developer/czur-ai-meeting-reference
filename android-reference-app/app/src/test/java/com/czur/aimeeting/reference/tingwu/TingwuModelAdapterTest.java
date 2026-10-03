package com.czur.aimeeting.reference.tingwu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.TranscriptEvent;
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
        assertEquals("zh-CN", events.get(2).language());
        assertEquals(100, events.get(2).startMs());
        assertEquals(620, events.get(2).endMs());
        assertFalse(events.get(0).isFinal());
        assertTrue(events.get(2).isFinal());
        JSONObject stop = new JSONObject(adapter.stopMessage(grant));
        assertEquals("StopTranscription", stop.getJSONObject("header").getString("name"));
    }
}
