package com.czur.reference.application.port;

import com.czur.reference.application.realtime.AudioStreamContentDigest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletedAudioReadinessPortTest {
    @Test
    void readyContainsOnlyVerifiedAudioValuesAndRedactsDigest() {
        AudioStreamContentDigest digest = new AudioStreamContentDigest();
        digest.append(1, 1, new byte[32]);
        String contentDigest = digest.finish(1, 20);
        var ready = new CompletedAudioReadinessPort.Ready(
                "mtg_audio_ready", 1, 20, contentDigest);
        assertEquals(1, ready.lastAcceptedSequence());
        assertEquals(20, ready.audioDurationMs());
        assertFalse(ready.toString().contains(contentDigest));
        assertFalse(ready.toString().contains("mtg_audio_ready"));
    }

    @Test
    void rejectsIncompleteOrUnversionedFacts() {
        String digest = "sha256-v1:" + "0".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new CompletedAudioReadinessPort.Ready("mtg_audio_ready", 0, 20, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new CompletedAudioReadinessPort.Ready("mtg_audio_ready", 1, 0, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new CompletedAudioReadinessPort.Ready("mtg_audio_ready", 1, 20,
                        "sha256:" + "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new CompletedAudioReadinessPort.Ready("mtg_audio_ready", 1, 20,
                        "sha256-v1:" + "A".repeat(64)));
    }
}
