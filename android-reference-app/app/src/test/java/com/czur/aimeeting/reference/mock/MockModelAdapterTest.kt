package com.czur.aimeeting.reference.mock

import org.junit.Assert.*
import org.junit.Test
import com.czur.aimeeting.model.TranscriptEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MockModelAdapterTest {
    @Test fun emitsThreeRevisionsOfOneSegment() {
        val events = CopyOnWriteArrayList<TranscriptEvent>()
        val complete = CountDownLatch(3)
        val session = MockModelAdapter().open { event -> events += event; complete.countDown() }
        try { session.start(); assertTrue("模拟字幕未按时完成", complete.await(5, TimeUnit.SECONDS)) }
        finally { session.close() }
        assertEquals(3, events.size)
        assertEquals(events[0].segmentId(), events[1].segmentId()); assertEquals(events[1].segmentId(), events[2].segmentId())
        assertEquals(1, events[0].revision()); assertEquals(2, events[1].revision()); assertEquals(3, events[2].revision())
        assertEquals("zh-CN", events[2].language()); assertEquals(0L, events[2].startMs()); assertEquals(0L, events[2].endMs())
        assertFalse(events[0].isFinal()); assertFalse(events[1].isFinal()); assertTrue(events[2].isFinal())
    }
}
