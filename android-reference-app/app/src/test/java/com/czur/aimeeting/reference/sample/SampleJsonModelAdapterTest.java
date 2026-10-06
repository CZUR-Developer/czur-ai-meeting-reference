package com.czur.aimeeting.reference.sample;

import com.czur.aimeeting.client.provider.SampleJsonModelAdapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.TranscriptEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 第二格式样例从模型原始 JSON 到标准字幕的正常路径。 */
public final class SampleJsonModelAdapterTest {
    @Test
    public void mapsPartialRevisionFinalAndFinish() {
        SampleJsonModelAdapter adapter = new SampleJsonModelAdapter();
        ModelSessionGrant grant = new ModelSessionGrant("mtg_sample", adapter.id(),
                "ws://127.0.0.1:9000/model", "2030-01-01T00:00:00Z", null, Map.of());
        List<TranscriptEvent> events = new ArrayList<>();
        assertEquals("{\"type\":\"start\"}", adapter.startMessage(grant));
        assertEquals(ModelSignal.AUDIO_READY,
                adapter.onServerMessage("{\"type\":\"ready\"}", events::add));
        assertEquals(ModelSignal.NONE, adapter.onServerMessage(transcript(1, "你好", false),
                events::add));
        adapter.onServerMessage(transcript(2, "你好，会议", false), events::add);
        adapter.onServerMessage(transcript(3, "你好，会议。", true), events::add);
        assertEquals(3, events.size());
        assertEquals("s1", events.get(0).segmentId());
        assertEquals(3, events.get(2).revision());
        assertEquals("你好，会议。", events.get(2).text());
        assertFalse(events.get(1).isFinal());
        assertTrue(events.get(2).isFinal());
        assertEquals("{\"type\":\"stop\"}", adapter.stopMessage(grant));
        assertEquals(ModelSignal.FINISHED,
                adapter.onServerMessage("{\"type\":\"finished\"}", events::add));
    }

    private static String transcript(int revision, String text, boolean isFinal) {
        return "{\"type\":\"transcript\",\"segment\":\"s1\",\"revision\":" + revision
                + ",\"text\":\"" + text + "\",\"final\":" + isFinal
                + ",\"startMs\":0,\"endMs\":500,\"language\":\"zh-CN\"}";
    }
}
