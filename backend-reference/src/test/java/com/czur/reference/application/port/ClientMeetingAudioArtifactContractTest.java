package com.czur.reference.application.port;

import com.czur.reference.application.port.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 录音产物与媒体流端口的值对象契约；不访问数据库、模型或对象存储。 */
class ClientMeetingAudioArtifactContractTest {
    private static final String CHECKSUM = "sha256:" + "a".repeat(64);

    @Test
    void audioArtifactKeepsTheCallerOwnedStreamAndUsesStableMetadata() throws Exception {
        byte[] bytes = {1, 2, 3};
        var input = new ByteArrayInputStream(bytes);
        var artifact = new ClientMeetingInputPort.AudioArtifact(
                "audio/ogg", bytes.length, CHECKSUM, input);

        assertEquals("audio/ogg", artifact.mediaType());
        assertEquals(bytes.length, artifact.contentSize());
        assertEquals(input, artifact.content());
        assertArrayEquals(bytes, artifact.content().readAllBytes());
    }

    @Test
    void audioArtifactRejectsUnsupportedShapeBeforeStorageIsTouched() {
        assertThrows(IllegalArgumentException.class, () -> new ClientMeetingInputPort.AudioArtifact(
                "application/json", 1, CHECKSUM, InputStream.nullInputStream()));
        assertThrows(IllegalArgumentException.class, () -> new ClientMeetingInputPort.AudioArtifact(
                "audio/ogg", 0, CHECKSUM, InputStream.nullInputStream()));
        assertThrows(IllegalArgumentException.class, () -> new ClientMeetingInputPort.AudioArtifact(
                "audio/ogg", 1, "sha256:ABC", InputStream.nullInputStream()));
    }

    @Test
    void mediaFoundExposesStreamAndClosesTheStoreHandle() throws Exception {
        String checksum = "sha256:" + "b".repeat(64);
        var spec = new ArtifactStore.WriteIntentSpec("art_client_audio_1", "mtg_audio",
                "audio.original", "audio/ogg", null, 1, 3, checksum);
        var descriptor = new ArtifactStore.ArtifactDescriptor(
                new ArtifactStore.WriteIntent("intent-1",
                        new ArtifactStore.StorageBinding("opaque"), spec),
                new ArtifactStore.ContentClaim(3, checksum));
        var stream = new TrackingInputStream(new byte[]{4, 5, 6});
        var handle = new ArtifactStore.ReadHandle(descriptor, stream);
        var reference = new MeetingManifest.ArtifactRef("art_client_audio_1", "audio.original",
                "audio/ogg", null, 1, 3, checksum, MeetingManifest.ArtifactStatus.READY,
                java.time.Instant.parse("2026-10-16T00:00:00Z"), null, null);

        try (var found = new MeetingArtifactMediaPort.Found(reference, handle)) {
            assertArrayEquals(new byte[]{4, 5, 6}, found.content().readAllBytes());
            assertEquals(reference, found.reference());
        }
        assertTrue(stream.closed);
    }

    private static final class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean closed;

        private TrackingInputStream(byte[] content) {
            delegate = new ByteArrayInputStream(content);
        }

        @Override public int read() { return delegate.read(); }
        @Override public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }
        @Override public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }
}
