package com.czur.reference.application.realtime;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioStreamContentDigestTest {
    // 由独立的 hashlib + struct 大端编码计算；防止实现和期望共用同一序列化函数。
    private static final String TWO_FRAME_VECTOR = "sha256-v1:"
            + "3a620eb7f18b23e2fd919f28b7b2043e66e37310bc34bab0f52c87a3d1391bfa";

    @Test
    void fixedVersionedBigEndianVectorCoversOrderedFramesAndCompletionEndpoint() {
        AudioStreamContentDigest digest = new AudioStreamContentDigest();
        digest.append(1, 1, new byte[32]);
        byte[] second = new byte[32];
        Arrays.fill(second, (byte) 0xff);
        digest.append(2, 1, second);
        assertEquals(1, AudioStreamContentDigest.VERSION);
        assertEquals(TWO_FRAME_VECTOR, digest.finish(2, 40));
    }

    @Test
    void frameAndEndpointChangesChangeTheWholeInputIdentity() {
        assertNotEquals(digest(new byte[32], 20), digest(fill((byte) 1), 20));
        assertNotEquals(digest(new byte[32], 20), digest(new byte[32], 21));
        AudioStreamContentDigest twoFrames = new AudioStreamContentDigest();
        twoFrames.append(1, 1, new byte[32]);
        twoFrames.append(2, 1, new byte[32]);
        assertNotEquals(digest(new byte[32], 20), twoFrames.finish(2, 20));
    }

    @Test
    void rejectsGapDuplicateWrongVersionAndMalformedDigestWithoutAdvancing() {
        AudioStreamContentDigest digest = new AudioStreamContentDigest();
        assertThrows(IllegalArgumentException.class, () -> digest.append(2, 1, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> digest.append(1, 2, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> digest.append(1, 1, new byte[31]));
        assertThrows(NullPointerException.class, () -> digest.append(1, 1, null));
        digest.append(1, 1, new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> digest.append(1, 1, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> digest.append(3, 1, new byte[32]));
        assertEquals(digest(new byte[32], 20), digest.finish(1, 20));
    }

    @Test
    void completionRequiresFramesAndMatchingPositiveEndpointAndIsOneShot() {
        AudioStreamContentDigest digest = new AudioStreamContentDigest();
        assertThrows(IllegalArgumentException.class, () -> digest.finish(0, 0));
        digest.append(1, 1, new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> digest.finish(2, 20));
        assertThrows(IllegalArgumentException.class, () -> digest.finish(1, 0));
        assertEquals(digest(new byte[32], 20), digest.finish(1, 20));
        assertThrows(IllegalStateException.class, () -> digest.append(2, 1, new byte[32]));
        assertThrows(IllegalStateException.class, () -> digest.finish(1, 20));
    }

    private static String digest(byte[] frameDigest, long lastAudioPositionMs) {
        AudioStreamContentDigest stream = new AudioStreamContentDigest();
        stream.append(1, 1, frameDigest);
        return stream.finish(1, lastAudioPositionMs);
    }

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
