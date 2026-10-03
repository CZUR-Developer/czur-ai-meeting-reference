package com.czur.aimeeting.reference.mock;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranscriptListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** P4 使用的固定模拟模型：同一个片段先出初稿、修订稿，再确认最终文字。 */
public final class MockModelAdapter implements ModelAdapter {
    @Override
    public String id() {
        return "reference-mock";
    }

    @Override
    public ModelSession open(TranscriptListener listener) {
        return new MockSession(listener);
    }

    private static final class MockSession implements ModelSession {
        private final TranscriptListener listener;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        private MockSession(TranscriptListener listener) {
            this.listener = listener;
        }

        @Override
        public void start() {
            emit(0, 1, "今天我们讨论 AI", false);
            emit(1200, 2, "今天我们讨论 AI 会议", false);
            emit(2400, 3, "今天我们讨论 AI 会议纪要。", true);
        }

        private void emit(long delayMs, int revision, String text, boolean isFinal) {
            executor.schedule(() -> listener.onTranscript(
                    new TranscriptEvent("mock-segment-1", revision, 0, 0,
                            "zh-CN", text, isFinal)),
                    delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
