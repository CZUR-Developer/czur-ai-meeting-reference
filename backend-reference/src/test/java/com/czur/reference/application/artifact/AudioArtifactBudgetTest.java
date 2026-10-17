package com.czur.reference.application.artifact;

import com.czur.reference.application.http.BinaryHttpRequest;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只验证长度元数据，不为一小时／五小时用例分配相应的大文件或内存。 */
class AudioArtifactBudgetTest {
    @Test
    void pcmForDeclaredOneAndFiveHourMeetingsFitsExplicitHttpBudget() throws Exception {
        for (long durationMs : new long[]{3_600_000L, 18_000_000L}) {
            long pcmBytes = durationMs * 32L;
            long budget = AudioArtifactBudget.forMaxDurationMs(durationMs);
            assertTrue(budget > pcmBytes);
            try (var request = new BinaryHttpRequest("POST", "/v1/meetings/mtg_budget/audio-original",
                    Map.of(), Map.of(), pcmBytes, InputStream.nullInputStream(), budget)) {
                assertEquals(pcmBytes, request.contentLength());
            }
            assertThrows(IllegalArgumentException.class, () -> new BinaryHttpRequest("POST",
                    "/v1/meetings/mtg_budget/audio-original", Map.of(), Map.of(), budget + 1,
                    InputStream.nullInputStream(), budget));
        }
    }

    @Test
    void shortMeetingsAndLegacyHttpConstructorKeepOriginalBudget() throws Exception {
        assertEquals(BinaryHttpRequest.MAX_CONTENT_BYTES,
                AudioArtifactBudget.forMaxDurationMs(60_000));
        try (var request = new BinaryHttpRequest("POST", "/v1/meetings/mtg_budget/audio-original",
                Map.of(), Map.of(), BinaryHttpRequest.MAX_CONTENT_BYTES, InputStream.nullInputStream())) {
            assertEquals(64L * 1024 * 1024, request.contentLength());
        }
        assertThrows(IllegalArgumentException.class, () -> new BinaryHttpRequest("POST",
                "/v1/meetings/mtg_budget/audio-original", Map.of(), Map.of(), 3_600_000L * 32,
                InputStream.nullInputStream()));
    }
}
