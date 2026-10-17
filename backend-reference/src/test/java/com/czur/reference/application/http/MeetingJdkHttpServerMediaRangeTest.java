package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.MeetingArtifactMediaPort;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 浏览器读取录音头尾、拖动播放使用的三个正常 Range 形态及完整实体回退。 */
class MeetingJdkHttpServerMediaRangeTest {
    @Test
    void realHttpCopiesCompleteAndHeaderTailRangesAndClosesEachReadHandle() throws Exception {
        byte[] audio = new byte[1000];
        for (int index = 0; index < audio.length; index++) audio[index] = (byte) index;
        String artifactId = "art_client_audio_range_test";
        String checksum = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(audio));
        var spec = new ArtifactStore.WriteIntentSpec(artifactId, "mtg_audio_range_test",
                "audio.original", "audio/ogg", null, 1, audio.length, checksum);
        var descriptor = new ArtifactStore.ArtifactDescriptor(
                new ArtifactStore.WriteIntent("intent-audio-range-test",
                        new ArtifactStore.StorageBinding("opaque-audio-range-test"), spec),
                new ArtifactStore.ContentClaim(audio.length, checksum));
        var reference = new MeetingManifest.ArtifactRef(artifactId, "audio.original",
                "audio/ogg", null, 1, audio.length, checksum,
                MeetingManifest.ArtifactStatus.READY, Instant.parse("2026-10-17T00:00:00Z"),
                null, null);
        AtomicInteger closedHandles = new AtomicInteger();
        CountDownLatch allHandlesClosed = new CountDownLatch(3);
        var unusedInput = new ClientMeetingInputPort() {
            @Override public StartResult start(MeetingCommandContext context, String meetingId) {
                throw new AssertionError("媒体回放不能启动会议");
            }
            @Override public AppendResult append(MeetingCommandContext context, String meetingId,
                    ClientEvent request) {
                throw new AssertionError("媒体回放不能写入事件");
            }
        };
        HttpErrorMapper errors = new HttpErrorMapper();
        var endpoint = new MeetingArtifactHttpEndpoint(unusedInput, request -> {
            // 每个 HTTP 请求拥有独立的 Store 流，不能复用上次 Range 的读取位置。
            var stream = new ByteArrayInputStream(audio) {
                @Override public void close() throws IOException {
                    super.close();
                    closedHandles.incrementAndGet();
                    allHandlesClosed.countDown();
                }
            };
            return new MeetingArtifactMediaPort.Found(reference,
                    new ArtifactStore.ReadHandle(descriptor, stream));
        }, errors);
        MeetingQueryPort query = new MeetingQueryPort() {
            @Override public Result get(GetRequest request) {
                throw new AssertionError("媒体回放不能进入会议查询");
            }
            @Override public ListResult list(ListRequest request) {
                throw new AssertionError("媒体回放不能进入会议列表");
            }
        };
        var runtime = new MeetingHttpRuntime(new MeetingHttpAdapter(query,
                request -> { throw new AssertionError("媒体回放不能删除会议"); },
                (request, context) -> { throw new AssertionError("媒体回放不能解码删除"); },
                errors), errors);
        var dispatcher = new MeetingHttpDispatcher(runtime, errors, null, null, endpoint);
        try (runtime;
             var server = new MeetingJdkHttpServer(new InetSocketAddress("127.0.0.1", 0),
                     dispatcher, () -> true);
             var client = HttpClient.newHttpClient()) {
            server.start();
            URI url = URI.create("http://127.0.0.1:" + server.address().getPort()
                    + "/v1/artifacts/" + artifactId + "?genTime=1792195200000");
            var complete = readMedia(client, url, null);
            assertEquals(200, complete.statusCode());
            assertEquals("bytes", complete.headers().firstValue("Accept-Ranges").orElseThrow());
            assertEquals("1000", complete.headers().firstValue("Content-Length").orElseThrow());
            assertTrue(complete.headers().firstValue("Content-Range").isEmpty());
            assertArrayEquals(audio, complete.body());

            var header = readMedia(client, url, "bytes=0-99");
            assertEquals(206, header.statusCode());
            assertEquals("bytes 0-99/1000", header.headers().firstValue("Content-Range").orElseThrow());
            assertEquals("100", header.headers().firstValue("Content-Length").orElseThrow());
            assertArrayEquals(Arrays.copyOfRange(audio, 0, 100), header.body());

            var tail = readMedia(client, url, "bytes=-100");
            assertEquals(206, tail.statusCode());
            assertEquals("bytes 900-999/1000", tail.headers().firstValue("Content-Range").orElseThrow());
            assertEquals("100", tail.headers().firstValue("Content-Length").orElseThrow());
            assertArrayEquals(Arrays.copyOfRange(audio, 900, 1000), tail.body());
            // 响应字节接收完成与服务器退出 try(found) 存在短暂调度差，等待关闭事实。
            assertTrue(allHandlesClosed.await(5, TimeUnit.SECONDS));
            assertEquals(3, closedHandles.get());
        }
    }

    private static java.net.http.HttpResponse<byte[]> readMedia(
            HttpClient client, URI url, String range) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5));
        if (range != null) request.header("Range", range);
        return client.send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void missingRangeKeepsCompleteEntity() {
        assertEquals(new MeetingJdkHttpServer.MediaRange(0, 1000, 200),
                MeetingJdkHttpServer.MediaRange.select(null, 1000));
    }

    @Test
    void fileHeaderRangeReturnsRequestedBytes() {
        assertEquals(new MeetingJdkHttpServer.MediaRange(0, 100, 206),
                MeetingJdkHttpServer.MediaRange.select("bytes=0-99", 1000));
    }

    @Test
    void openRangeSupportsSeekingIntoRecording() {
        assertEquals(new MeetingJdkHttpServer.MediaRange(400, 600, 206),
                MeetingJdkHttpServer.MediaRange.select("bytes=400-", 1000));
    }

    @Test
    void suffixRangeReadsOggFinalGranuleWithoutDownloadingEverything() {
        assertEquals(new MeetingJdkHttpServer.MediaRange(900, 100, 206),
                MeetingJdkHttpServer.MediaRange.select("bytes=-100", 1000));
    }

    @Test
    void rangeLargerThanRecordingStopsAtActualFileEnd() {
        assertEquals(new MeetingJdkHttpServer.MediaRange(900, 100, 206),
                MeetingJdkHttpServer.MediaRange.select("bytes=900-1200", 1000));
        assertEquals(new MeetingJdkHttpServer.MediaRange(0, 1000, 206),
                MeetingJdkHttpServer.MediaRange.select("bytes=-1200", 1000));
    }

    @Test
    void outsideRangeHasNoPartialEntityAndMultipartFallsBackToFull() {
        assertEquals(new MeetingJdkHttpServer.MediaRange(0, 0, 416),
                MeetingJdkHttpServer.MediaRange.select("bytes=1000-", 1000));
        assertEquals(new MeetingJdkHttpServer.MediaRange(0, 1000, 200),
                MeetingJdkHttpServer.MediaRange.select("bytes=0-9,20-29", 1000));
    }
}
