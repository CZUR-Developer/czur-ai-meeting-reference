package com.czur.aimeeting.reference.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.TranscriptEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** 只核对 P4-1 演示所需的同片段修订与最终态。 */
public final class MockModelAdapterTest {
    @Test
    public void emitsThreeRevisionsOfOneSegment() throws Exception {
        List<TranscriptEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch complete = new CountDownLatch(3);
        ModelSession session = new MockModelAdapter().open(event -> {
            events.add(event);
            complete.countDown();
        });
        try {
            session.start();
            assertTrue("模拟字幕未按时完成", complete.await(5, TimeUnit.SECONDS));
        } finally {
            session.close();
        }

        assertEquals(3, events.size());
        assertEquals(events.get(0).segmentId(), events.get(1).segmentId());
        assertEquals(events.get(1).segmentId(), events.get(2).segmentId());
        assertEquals(1, events.get(0).revision());
        assertEquals(2, events.get(1).revision());
        assertEquals(3, events.get(2).revision());
        assertEquals("zh-CN", events.get(2).language());
        assertEquals(0, events.get(2).startMs());
        assertEquals(0, events.get(2).endMs());
        assertFalse(events.get(0).isFinal());
        assertFalse(events.get(1).isFinal());
        assertTrue(events.get(2).isFinal());
    }
}
