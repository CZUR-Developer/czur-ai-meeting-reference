package com.czur.reference.application.realtime;

import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioFrameContentDigestTest {
    private static final String AUDIO_SHA256 =
            "c2ac39c11d3f4085330cad17e2b5622e83d551c558b8e4989ad70cbb365f638f";
    private static final String FULL_SHA256 =
            "2b7f1b53302e43d064ebcbe538c077bbe69a2da28ce29eb175f988fd541f7efa";

    @Test
    void fixedBigEndianVectorAndOptionalWireChecksum() {
        MeetingAudioFrame withoutChecksum = frame(null, 5, 20);
        MeetingAudioFrame withChecksum = frame(AUDIO_SHA256, 5, 20);
        assertEquals(FULL_SHA256, AudioFrameContentDigest.sha256Hex(withoutChecksum));
        assertEquals(FULL_SHA256, AudioFrameContentDigest.sha256Hex(withChecksum));
        assertEquals(AudioFrameContentDigest.VERSION, 1);
    }

    @Test
    void frameMetadataChangesContentIdentity() {
        assertNotEquals(AudioFrameContentDigest.sha256Hex(frame(null, 5, 20)),
                AudioFrameContentDigest.sha256Hex(frame(null, 6, 20)));
        assertNotEquals(AudioFrameContentDigest.sha256Hex(frame(null, 5, 20)),
                AudioFrameContentDigest.sha256Hex(frame(null, 5, 19)));
        assertThrows(IllegalArgumentException.class,
                () -> AudioFrameContentDigest.sha256(frame("0".repeat(64), 5, 20)));
    }

    @Test
    void portValuesDoNotExposeAudioOrConnectionIdentity() {
        var connection = DurableAudioInputPort.WriterConnectionId.newConnection();
        var binding = new DurableAudioInputPort.WriterBinding("mtg_digest", connection, 1);
        var bound = new DurableAudioInputPort.Bound(binding,
                new DurableAudioInputPort.Cursor(0, 0, null));
        var completion = new DurableAudioInputPort.Completion(1, 20, "", 0, "request-1");
        var page = new DurableAudioInputPort.FramePage(List.of(frame(null, 5, 20)), 1);
        assertEquals("WriterConnectionId[redacted]", connection.toString());
        assertEquals("WriterBinding[redacted]", binding.toString());
        assertFalse(bound.toString().contains(connection.value().toString()));
        assertEquals("Completion[redacted]", completion.toString());
        assertEquals("FramePage[count=1]", page.toString());
        assertThrows(IllegalArgumentException.class,
                () -> new DurableAudioInputPort.Completion(1, 20, AUDIO_SHA256, 0, "request-1"));
    }

    private static MeetingAudioFrame frame(String checksum, long gap, int duration) {
        byte[] audio = new byte[640];
        for (int i = 0; i < audio.length; i++) audio[i] = (byte) i;
        return new MeetingAudioFrame(1, 0, duration, audio, checksum,
                3, 4, gap, Set.of(MeetingAudioFrame.AudioFrameFlag.AFTER_GAP));
    }
}
