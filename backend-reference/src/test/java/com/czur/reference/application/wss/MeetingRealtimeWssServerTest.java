package com.czur.reference.application.wss;

import com.czur.aimeeting.contract.realtime.v1.AudioEncoding;
import com.czur.aimeeting.contract.realtime.v1.AudioProfile;
import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.InputComplete;
import com.czur.aimeeting.contract.realtime.v1.MeetingAudioFrame;
import com.czur.aimeeting.contract.realtime.v1.ResumeRequest;
import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.aimeeting.contract.realtime.v1.StreamStart;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.MeetingInputReconciler;
import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.MeetingEventPage;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.MeetingInputClosureContextPort;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.RealtimeEventLogPort;
import com.czur.reference.application.port.RealtimeStartFactsLookup;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.realtime.RealtimeAudioIngress;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.application.realtime.RealtimeTranscriptSessions;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.protocol.realtime.RealtimeAudioWireMapper;
import com.czur.reference.protocol.realtime.RealtimeWireCodec;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.java_websocket.WebSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 TLS loopback 传输门禁；业务端口均为伪实现，不代替第 ③ 步 PostgreSQL 门。 */
class MeetingRealtimeWssServerTest {
    private static final String MEETING = "mtg_wss_connection_test";
    private static final String TOKEN = "test_session_token_000001";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final RealtimeCredentialVerifier.AudioContract AUDIO =
            new RealtimeCredentialVerifier.AudioContract(
                    com.czur.reference.application.port.model.AudioProfile.pcm16kMono(20), 640);
    private static final RealtimeWireCodec CODEC = RealtimeAudioWireMapper.trustedCodec(AUDIO);
    private static final CapabilityDescriptor CAPABILITIES =
            new CapabilityDescriptor(Set.of("asr"));
    private static final ProductProfile PROFILE = new ProductProfile(
            "profile", 1, Set.of("asr"), Set.of(), Map.of(), Map.of(), Map.of());

    @TempDir Path temporaryDirectory;

    @Test
    void readOnlyAndWriteCanResumeWithoutStartingAudioAndReceiveBoundedPages() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        EventLog first = transcriptEvent(1);
        EventLog second = transcriptEvent(2);
        EventLog third = transcriptEvent(3);
        AtomicInteger reads = new AtomicInteger();
        RealtimeEventLogPort events = request -> {
            reads.incrementAndGet();
            assertEquals(32, request.limit());
            assertEquals(MEETING, request.grant().readOwnership().meetingId());
            return request.afterSequence() == 0
                    ? new MeetingEventLogPort.Page(new MeetingEventPage(
                            List.of(first, second), 2, true))
                    : request.afterSequence() == 2
                            ? new MeetingEventLogPort.Page(new MeetingEventPage(
                                    List.of(third), 3, false))
                            : new MeetingEventLogPort.Page(MeetingEventPage.empty(3));
        };
        try (Fixture fixture = new Fixture(tls, verifier, audio, null, null, events);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            for (RealtimeCredentialVerifier.AccessMode mode : List.of(
                    RealtimeCredentialVerifier.AccessMode.READ_ONLY,
                    RealtimeCredentialVerifier.AccessMode.WRITE)) {
                verifier.access = mode;
                Listener listener = new Listener();
                var socket = connect(client, fixture.uri(MEETING), TOKEN,
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
                socket.sendBinary(ByteBuffer.wrap(resume(0).toByteArray()), true).join();
                awaitCount(listener.ackCount, 3);
                List<ServerEnvelope> received = listener.messages();
                assertEquals(List.of(1L, 2L, 0L), received.stream()
                        .map(ServerEnvelope::getEventSequence).toList());
                assertTrue(received.get(0).hasTranscriptUpsert());
                assertTrue(received.get(1).hasTranscriptUpsert());
                assertTrue(received.get(2).hasResumePageEnd());
                assertEquals(2, received.get(2).getResumePageEnd().getLastEventSequence());
                assertTrue(received.get(2).getResumePageEnd().getHasMore());

                socket.sendBinary(ByteBuffer.wrap(resume(2).toByteArray()), true).join();
                awaitCount(listener.ackCount, 5);
                received = listener.messages();
                assertEquals(3, received.get(3).getEventSequence());
                assertTrue(received.get(4).hasResumePageEnd());
                assertEquals(3, received.get(4).getResumePageEnd().getLastEventSequence());
                assertFalse(received.get(4).getResumePageEnd().getHasMore());

                socket.sendBinary(ByteBuffer.wrap(resume(3).toByteArray()), true).join();
                awaitCount(listener.ackCount, 6);
                received = listener.messages();
                assertTrue(received.get(5).hasResumePageEnd());
                assertEquals(3, received.get(5).getResumePageEnd().getLastEventSequence());
                assertFalse(received.get(5).getResumePageEnd().getHasMore());
                socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
                listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
            }
            assertEquals(6, reads.get());
            assertEquals(0, audio.bindCalls.get());
            assertEquals(0, audio.appendCalls.get());
        }
    }

    @Test
    void badStoredEventRejectsWholeResumePageBeforeSendingAnyEvent() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        EventLog good = transcriptEvent(1);
        EventLog second = transcriptEvent(2);
        EventLog bad = new EventLog(second.specVersion(), second.meetingId(),
                second.streamId(), second.eventId(), second.eventSequence(),
                second.emittedAt(), "unknownEvent", second.canonicalPayloadJson());
        RealtimeEventLogPort events = request -> new MeetingEventLogPort.Page(
                new MeetingEventPage(List.of(good, bad), 2, false));
        try (Fixture fixture = new Fixture(tls, new FakeVerifier(), new FakeAudio(),
                     null, null, events);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            Listener listener = new Listener();
            var socket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
            sendForExpectedClose(socket, resume(0).toByteArray());
            assertEquals(1011, listener.closed.orTimeout(5, TimeUnit.SECONDS).join());
            assertEquals(0, listener.ackCount.get());
        }
    }

    @Test
    void trustedHandshakeStartAndStoredFrameProduceOneTransientDurableAck() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        try (Fixture fixture = new Fixture(tls, verifier, audio);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted())
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            fixture.start();
            assertFalse(Logger.getLogger("org.java_websocket.WebSocketImpl")
                    .isLoggable(Level.FINEST));
            Listener listener = new Listener();
            java.net.http.WebSocket socket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
            assertEquals(CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                    socket.getSubprotocol());
            socket.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
            socket.sendBinary(ByteBuffer.wrap(frame().toByteArray()), true).join();

            ServerEnvelope ack = listener.ack.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(MEETING, ack.getMeetingId());
            assertEquals(MEETING, ack.getStreamId());
            assertEquals(0, ack.getEventSequence());
            assertEquals("", ack.getEventId());
            assertTrue(ack.getAudioAck().getDurable());
            assertEquals(1, ack.getAudioAck().getAcceptedStreamSequence());
            assertEquals(20, ack.getAudioAck().getAcceptedAudioPositionMs());
            assertEquals(1, listener.ackCount.get());
            assertEquals(1, audio.bindCalls.get());
            assertEquals(1, audio.appendCalls.get());
            assertTrue(verifier.calls.get() >= 3, "handshake, start and frame each reverify");

            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void transcriptPublicationFailureAfterStoredFrameClosesWithoutFakeAck() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeAudio audio = new FakeAudio();
        RealtimeTranscriptSessions failing = meetingId -> new RealtimeTranscriptSessions.Session() {
            @Override public com.czur.reference.application.port.EventLog acceptStored(
                    com.czur.reference.application.port.model.MeetingAudioFrame frame) {
                throw new IllegalStateException("publication unavailable");
            }
            @Override public void close() { }
        };
        try (Fixture fixture = new Fixture(tls, new FakeVerifier(), audio, failing);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            Listener listener = new Listener();
            var socket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
            socket.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
            sendForExpectedClose(socket, frame().toByteArray());
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, audio.appendCalls.get(), "audio may already be durable");
            assertEquals(0, listener.ackCount.get(), "no ACK before transcript publication");
        }
    }

    @Test
    void declarationBeforeLastFrameTriggersClosureOnlyAfterStoredTranscript() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeAudio audio = new FakeAudio();
        AtomicInteger published = new AtomicInteger();
        AtomicInteger reconciled = new AtomicInteger();
        AtomicInteger publishedAtSecondReconcile = new AtomicInteger(-1);
        RealtimeTranscriptSessions transcripts = meetingId ->
                new RealtimeTranscriptSessions.Session() {
                    @Override public com.czur.reference.application.port.EventLog acceptStored(
                            com.czur.reference.application.port.model.MeetingAudioFrame frame) {
                        published.incrementAndGet();
                        return null;
                    }
                    @Override public void close() { }
                };
        MeetingInputReconciler reconciler = waitingForStopReconciler(
                reconciled, published, publishedAtSecondReconcile);
        try (Fixture fixture = new Fixture(tls, new FakeVerifier(), audio,
                     transcripts, reconciler);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            Listener listener = new Listener();
            var socket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
            socket.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
            awaitBindCount(audio, 1);

            // 完成声明先于两帧持久保存：只触发一次会议级检查，不产生帧 ACK。
            socket.sendBinary(ByteBuffer.wrap(complete(2).toByteArray()), true).join();
            awaitCount(audio.completeCalls, 1);
            awaitCount(reconciled, 1);
            assertEquals(0, listener.ackCount.get());

            socket.sendBinary(ByteBuffer.wrap(frame(1).toByteArray()), true).join();
            awaitCount(listener.ackCount, 1);
            assertEquals(1, reconciled.get(), "普通帧不得触发会议收口");

            socket.sendBinary(ByteBuffer.wrap(frame(2).toByteArray()), true).join();
            awaitCount(listener.ackCount, 2);
            awaitCount(reconciled, 2);
            assertEquals(2, publishedAtSecondReconcile.get(),
                    "末帧字幕处理完成后才能尝试收口");
            assertEquals(2, audio.appendCalls.get());
            assertEquals(2, published.get());
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void rejectedOrUnknownCompletionClosesWithoutAckOrClosure() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeAudio audio = new FakeAudio();
        AtomicInteger reconciled = new AtomicInteger();
        MeetingInputReconciler reconciler = waitingForStopReconciler(
                reconciled, new AtomicInteger(), new AtomicInteger(-1));
        try (Fixture fixture = new Fixture(tls, new FakeVerifier(), audio,
                     null, reconciler);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            for (FakeAudio.Mode mode : List.of(FakeAudio.Mode.UNKNOWN_COMPLETE,
                    FakeAudio.Mode.FENCED_COMPLETE)) {
                audio.completeMode = mode;
                Listener listener = new Listener();
                var socket = connect(client, fixture.uri(MEETING), TOKEN,
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
                socket.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
                sendForExpectedClose(socket, complete(1).toByteArray());
                listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
                assertEquals(0, listener.ackCount.get());
                assertEquals(0, reconciled.get());
            }
            assertEquals(2, audio.completeCalls.get());
        }
    }

    @Test
    void wrongPathBearerSubprotocolAndEnvelopeIdentityNeverReachInput() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        try (Fixture fixture = new Fixture(tls, verifier, audio);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            for (URI uri : List.of(fixture.uri("mtg_other"), fixture.baseUri("/realtime/" + MEETING),
                    fixture.baseUri("/v1/realtime/" + MEETING + "?x=1"))) {
                assertThrows(CompletionException.class, () -> connect(client, uri, TOKEN,
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, new Listener()));
            }
            assertThrows(CompletionException.class, () -> connect(client, fixture.uri(MEETING),
                    "wrong_session_token_0001",
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, new Listener()));
            assertThrows(CompletionException.class, () -> connect(client, fixture.uri(MEETING),
                    TOKEN, "wrong.protocol", new Listener()));
            assertEquals(0, audio.bindCalls.get());
            assertEquals(0, audio.appendCalls.get());

            Listener listener = new Listener();
            var socket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
            sendForExpectedClose(socket, start().toBuilder()
                    .setMeetingId("mtg_other").setStreamId("mtg_other")
                    .build().toByteArray());
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(0, listener.ackCount.get());
            assertEquals(0, audio.bindCalls.get());
        }
    }

    @Test
    void repeatStartUnknownFencedAndRevokedFrameCloseWithoutAck() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        try (Fixture fixture = new Fixture(tls, verifier, audio);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();

            Listener repeat = new Listener();
            var first = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, repeat);
            first.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
            sendForExpectedClose(first, start().toByteArray());
            repeat.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, audio.bindCalls.get());
            assertEquals(0, repeat.ackCount.get());

            audio.bindMode = FakeAudio.Mode.UNKNOWN_BIND;
            Listener unknownBind = new Listener();
            var second = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, unknownBind);
            sendForExpectedClose(second, start().toByteArray());
            unknownBind.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(2, audio.bindCalls.get());
            assertEquals(0, unknownBind.ackCount.get());

            audio.bindMode = FakeAudio.Mode.STORED;
            for (FakeAudio.Mode mode : List.of(FakeAudio.Mode.UNKNOWN_APPEND,
                    FakeAudio.Mode.FENCED_APPEND)) {
                audio.appendMode = mode;
                Listener rejected = new Listener();
                var socket = connect(client, fixture.uri(MEETING), TOKEN,
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, rejected);
                socket.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
                sendForExpectedClose(socket, frame().toByteArray());
                rejected.closed.orTimeout(5, TimeUnit.SECONDS).join();
                assertEquals(0, rejected.ackCount.get());
            }

            audio.appendMode = FakeAudio.Mode.STORED;
            Listener revoked = new Listener();
            int expectedBinds = audio.bindCalls.get() + 1;
            var socket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, revoked);
            socket.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
            awaitBindCount(audio, expectedBinds);
            int before = audio.appendCalls.get();
            verifier.enabled = false;
            sendForExpectedClose(socket, frame().toByteArray());
            revoked.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(before, audio.appendCalls.get());
            assertEquals(0, revoked.ackCount.get());
        }
    }

    @Test
    void expiryAndOversizeCloseBeforeAudioWriteAndActiveCloseIsBounded() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        try (Fixture fixture = new Fixture(tls, verifier, audio);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            Listener expired = new Listener();
            var socket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, expired);
            socket.sendBinary(ByteBuffer.wrap(start().toByteArray()), true).join();
            awaitBindCount(audio, 1);
            fixture.clock.now.set(NOW.plusSeconds(121));
            sendForExpectedClose(socket, frame().toByteArray());
            expired.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(0, audio.appendCalls.get());
            assertEquals(0, expired.ackCount.get());

            fixture.clock.now.set(NOW);
            Listener oversized = new Listener();
            var next = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, oversized);
            int verifiedBeforeOversize = verifier.calls.get();
            sendForExpectedClose(next,
                    new byte[RealtimeWireCodec.MAX_ENVELOPE_BYTES + 1]);
            oversized.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(0, oversized.ackCount.get());
            assertEquals(verifiedBeforeOversize, verifier.calls.get(),
                    "oversized single frame must fail in library before receive callback");

            Listener fragmented = new Listener();
            var fragmentedSocket = connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, fragmented);
            int verifiedBeforeFragments = verifier.calls.get();
            fragmentedSocket.sendBinary(ByteBuffer.wrap(new byte[32_768]), false).join();
            sendForExpectedClose(fragmentedSocket, new byte[32_769]);
            fragmented.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(0, fragmented.ackCount.get());
            assertEquals(verifiedBeforeFragments, verifier.calls.get(),
                    "oversized fragmented message must fail before receive callback");
            assertEquals(0, audio.appendCalls.get());

            Listener active = new Listener();
            connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, active);
            long started = System.nanoTime();
            fixture.close();
            assertTrue(Duration.ofNanos(System.nanoTime() - started)
                    .compareTo(Duration.ofSeconds(5)) < 0);
            active.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(fixture.server.getConnections().isEmpty());
        }
    }

    @Test
    void transportErrorImmediatelyDestroysSessionBeforeCloseCallback() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        try (Fixture fixture = new Fixture(tls, verifier, audio);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            Listener listener = new Listener();
            connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
            var serverConnection = awaitServerConnection(fixture);
            RealtimeWssConnection session = serverConnection.getAttachment();
            assertTrue(session != null);

            // 人工触发 Java-WebSocket 的非空连接故障回调；不能依赖稍后 onClose
            // 才销毁 session 凭据，也不能让故障连接继续调用 Start/音频端口。
            fixture.server.onError(serverConnection, new IOException("test transport error"));
            assertNull(serverConnection.getAttachment());
            assertInstanceOf(RealtimeWssConnection.Terminate.class,
                    session.receive(start().toByteArray()));
            listener.closed.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(0, audio.bindCalls.get());
            assertEquals(0, listener.ackCount.get());
        }
    }

    @Test
    void idleSessionExpiresWithoutAnotherMessageAndDestroysCredentialBeforeClose() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        try (Fixture fixture = new Fixture(tls, verifier, audio);
             HttpClient client = HttpClient.newBuilder().sslContext(tls.trusted()).build()) {
            fixture.start();
            Listener listener = new Listener();
            connect(client, fixture.uri(MEETING), TOKEN,
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, listener);
            var serverConnection = awaitServerConnection(fixture);
            RealtimeWssConnection session = serverConnection.getAttachment();
            assertTrue(session != null);

            fixture.clock.now.set(NOW.plusSeconds(121));
            assertEquals(1008, listener.closed.orTimeout(5, TimeUnit.SECONDS).join());
            assertNull(serverConnection.getAttachment());
            assertInstanceOf(RealtimeWssConnection.Terminate.class,
                    session.receive(start().toByteArray()));
            assertEquals(1, verifier.calls.get(), "no inbound message after handshake");
            assertEquals(0, audio.bindCalls.get());
            assertEquals(0, audio.appendCalls.get());
            assertEquals(0, listener.ackCount.get());
        }
    }

    @Test
    void pendingOutboundBytesNeverQueueAnotherDurableAck() throws Exception {
        TestTls tls = TestTls.create(temporaryDirectory);
        FakeVerifier verifier = new FakeVerifier();
        FakeAudio audio = new FakeAudio();
        try (Fixture fixture = new Fixture(tls, verifier, audio)) {
            RealtimeWssConnection session = RealtimeWssConnection.open(MEETING,
                    SecretCredential.copyOf(TOKEN.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                    verifier, ingress(audio), fixture.clock);
            assertInstanceOf(RealtimeWssConnection.NoReply.class,
                    session.receive(start().toByteArray()));
            AtomicReference<Object> attachment = new AtomicReference<>(session);
            AtomicInteger sends = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            WebSocket slowConnection = (WebSocket) Proxy.newProxyInstance(
                    WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAttachment" -> attachment.get();
                        case "setAttachment" -> {
                            attachment.set(args[0]);
                            yield null;
                        }
                        case "isOpen", "hasBufferedData" -> true;
                        case "send" -> {
                            sends.incrementAndGet();
                            yield null;
                        }
                        case "close" -> {
                            closes.incrementAndGet();
                            yield null;
                        }
                        default -> throw new AssertionError("unexpected WebSocket method: "
                                + method.getName());
                    });

            fixture.server.onMessage(slowConnection, ByteBuffer.wrap(frame().toByteArray()));

            assertEquals(0, audio.appendCalls.get(),
                    "pending bytes at onMessage entry reject before another append");
            assertEquals(0, sends.get(), "no ACK enters unbounded outQueue");
            assertEquals(1, closes.get());
            assertNull(attachment.get());
            assertInstanceOf(RealtimeWssConnection.Terminate.class,
                    session.receive(frame().toByteArray()));
        }
    }

    private static ClientEnvelope start() {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(MEETING).setStreamId(MEETING).setClientMessageId("start-1")
                .setStreamStart(StreamStart.newBuilder().setAudioProfile(AudioProfile.newBuilder()
                        .setEncoding(AudioEncoding.AUDIO_ENCODING_PCM_S16LE)
                        .setSampleRateHz(16_000).setChannelCount(1).setFrameDurationMs(20)))
                .build();
    }

    private static ClientEnvelope frame() {
        return frame(1);
    }

    private static ClientEnvelope frame(long sequence) {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(MEETING).setStreamId(MEETING)
                .setClientMessageId("frame-" + sequence)
                .setAudioFrame(MeetingAudioFrame.newBuilder().setStreamSequence(sequence)
                        .setStartPositionMs((sequence - 1) * 20).setDurationMs(20)
                        .setAudio(ByteString.copyFrom(new byte[640])))
                .build();
    }

    private static ClientEnvelope complete(long lastSequence) {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(MEETING).setStreamId(MEETING).setClientMessageId("complete-1")
                .setInputComplete(InputComplete.newBuilder()
                        .setLastStreamSequence(lastSequence)
                        .setLastAudioPositionMs(lastSequence * 20)
                        .setRequestId("complete-1"))
                .build();
    }

    private static ClientEnvelope resume(long afterSequence) {
        return ClientEnvelope.newBuilder().setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(MEETING).setStreamId(MEETING)
                .setClientMessageId("resume-" + afterSequence)
                .setResumeRequest(ResumeRequest.newBuilder()
                        .setAfterEventSequence(afterSequence)).build();
    }

    private static EventLog transcriptEvent(long sequence) {
        return RealtimeTranscriptEventMapper.event(MEETING, "evt-resume-" + sequence,
                sequence, NOW, new CanonicalTranscriptEvent("segment-" + sequence, 1,
                        CanonicalTranscriptEvent.Operation.UPSERT, 0, 20,
                        "zh-CN", "模拟字幕 " + sequence, true, null, 1.0,
                        List.of(), List.of()));
    }

    private static java.net.http.WebSocket connect(HttpClient client, URI uri, String token,
            String protocol, Listener listener) {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token).subprotocols(protocol)
                .buildAsync(uri, listener).orTimeout(5, TimeUnit.SECONDS).join();
    }

    /** 服务端可能先关闭输出再完成客户端发送 Future；关闭回调是此类负测的判据。 */
    private static void sendForExpectedClose(java.net.http.WebSocket socket, byte[] wire) {
        socket.sendBinary(ByteBuffer.wrap(wire), true)
                .handle((ignored, transportClose) -> null).join();
    }

    private static void awaitBindCount(FakeAudio audio, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (audio.bindCalls.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, audio.bindCalls.get(), "start must bind before revocation");
    }

    private static void awaitCount(AtomicInteger counter, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counter.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, counter.get());
    }

    /** 仅观察网络入口何时触发收口；Stop 未提交时不进入其它领域端口。 */
    private static MeetingInputReconciler waitingForStopReconciler(
            AtomicInteger calls, AtomicInteger published,
            AtomicInteger publishedAtSecondReconcile) {
        FinalizationAdmissionUnitOfWork unusedAdmission =
                new FinalizationAdmissionUnitOfWork() {
                    @Override public Result admit(AdmissionRequest request) {
                        throw new AssertionError("admission must wait for Stop");
                    }
                    @Override public Result recover(AdmissionRequest request) {
                        throw new AssertionError("recovery must wait for Stop");
                    }
                };
        return new MeetingInputReconciler(meetingId -> {
            if (!MEETING.equals(meetingId)) {
                throw new IllegalArgumentException("wrong meeting");
            }
            if (calls.incrementAndGet() == 2) {
                publishedAtSecondReconcile.set(published.get());
            }
            return new MeetingInputClosureContextPort.WaitingForStop();
        }, meetingId -> { throw new AssertionError("unexpected input lookup"); },
                meetingId -> { throw new AssertionError("unexpected audio readiness"); },
                unusedAdmission, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static org.java_websocket.WebSocket awaitServerConnection(Fixture fixture)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.server.getConnections().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, fixture.server.getConnections().size());
        return fixture.server.getConnections().iterator().next();
    }

    private static final class Listener implements java.net.http.WebSocket.Listener {
        private final CompletableFuture<ServerEnvelope> ack = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final AtomicInteger ackCount = new AtomicInteger();
        private final List<ServerEnvelope> received = java.util.Collections.synchronizedList(
                new ArrayList<>());

        private List<ServerEnvelope> messages() {
            synchronized (received) { return List.copyOf(received); }
        }

        @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }

        @Override
        public CompletionStage<?> onBinary(java.net.http.WebSocket socket,
                ByteBuffer data, boolean last) {
            if (last) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                ServerEnvelope decoded = CODEC.decodeServer(bytes);
                received.add(decoded);
                ackCount.incrementAndGet();
                ack.complete(decoded);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(java.net.http.WebSocket socket, int code, String reason) {
            closed.complete(code);
            return null;
        }

        @Override
        public void onError(java.net.http.WebSocket socket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }

    private static final class FakeVerifier implements RealtimeCredentialVerifier {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean enabled = true;
        private volatile AccessMode access = AccessMode.WRITE;

        @Override public Result verify(Request request) {
            calls.incrementAndGet();
            byte[] expected = TOKEN.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            boolean matched = request.credential().useBytes(bytes ->
                    MessageDigest.isEqual(bytes, expected));
            return enabled && matched && MEETING.equals(request.meetingId())
                    && MEETING.equals(request.streamId())
                    ? new Granted(access, NOW.plusSeconds(120), AUDIO,
                            new ReadOwnership(MEETING, "deployment", "subject"))
                    : new Denied();
        }
    }

    private static final class FakeAudio implements DurableAudioInputPort {
        private enum Mode { STORED, UNKNOWN_BIND, UNKNOWN_APPEND, FENCED_APPEND,
            UNKNOWN_COMPLETE, FENCED_COMPLETE }
        private final AtomicInteger bindCalls = new AtomicInteger();
        private final AtomicInteger appendCalls = new AtomicInteger();
        private final AtomicInteger completeCalls = new AtomicInteger();
        private volatile Mode bindMode = Mode.STORED;
        private volatile Mode appendMode = Mode.STORED;
        private volatile Mode completeMode = Mode.STORED;
        private volatile Completion completion;

        @Override public BindResult bindWriter(String meetingId, WriterConnectionId id) {
            bindCalls.incrementAndGet();
            return bindMode == Mode.UNKNOWN_BIND ? new Unknown()
                    : new Bound(new WriterBinding(meetingId, id, bindCalls.get()),
                            new Cursor(0, 0, null));
        }

        @Override public WriteResult append(WriterBinding binding,
                com.czur.reference.application.port.model.MeetingAudioFrame frame) {
            appendCalls.incrementAndGet();
            return switch (appendMode) {
                case UNKNOWN_APPEND -> new Unknown();
                case FENCED_APPEND -> new Rejected(Rejection.FENCED);
                default -> new Stored(new Cursor(frame.streamSequence(),
                        frame.startPositionMs() + frame.durationMs(), completion), false);
            };
        }

        @Override public CursorResult readCursor(String meetingId) {
            throw new AssertionError("unexpected readCursor");
        }
        @Override public ReplayResult replay(String meetingId, long after, int limit) {
            throw new AssertionError("unexpected replay");
        }
        @Override public WriteResult complete(WriterBinding binding, Completion completion) {
            completeCalls.incrementAndGet();
            return switch (completeMode) {
                case UNKNOWN_COMPLETE -> new Unknown();
                case FENCED_COMPLETE -> new Rejected(Rejection.FENCED);
                default -> {
                    this.completion = completion;
                    yield new Stored(new Cursor(0, 0, completion), false);
                }
            };
        }
    }

    private static RealtimeAudioIngress ingress(FakeAudio audio) {
        RealtimeStartFactsLookup facts = (deployment, meetingId) ->
                new RealtimeStartFactsLookup.First("subject", "title", "profile", 1,
                        Set.of("asr"));
        MeetingStartExecutionUnitOfWork unit = new MeetingStartExecutionUnitOfWork() {
            @Override public Result start(StartRequest request) {
                throw new AssertionError("legacy Start must not be called");
            }
            @Override public Result startCompleteCreate(StartRequest request) {
                return startSuccess(request.meetingId());
            }
        };
        var start = new RealtimeMeetingStartCoordinator("deployment", facts,
                (id, revision) -> Optional.of(PROFILE), CAPABILITIES,
                DeploymentLimits.NONE, Clock.fixed(NOW, ZoneOffset.UTC), unit);
        return new RealtimeAudioIngress(start, audio);
    }

    private static MeetingStartExecutionUnitOfWork.Success startSuccess(String meetingId) {
        var plan = new CapabilityPlanner().plan(CAPABILITIES, PROFILE, Set.of("asr"),
                Set.of("asr"), DeploymentLimits.NONE, 1L);
        var fact = FinalizationExecutionFact.freeze(meetingId, NOW, "title", PROFILE, plan);
        var meeting = MeetingAggregate.owned(meetingId, "deployment", "session", "subject",
                "grant", NOW.plusSeconds(3_600), "reservation", "profile", 1,
                NOW.minusSeconds(1));
        meeting.apply(MeetingEvent.AUTHORIZE, NOW);
        meeting.apply(MeetingEvent.START, NOW);
        return new MeetingStartExecutionUnitOfWork.Success(
                MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, meeting.snapshot(), fact);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static final class Fixture implements AutoCloseable {
        private final MutableClock clock = new MutableClock();
        private final MeetingRealtimeWssServer server;
        private boolean closed;

        private Fixture(TestTls tls, FakeVerifier verifier, FakeAudio audio) throws Exception {
            this(tls, verifier, audio, null);
        }

        private Fixture(TestTls tls, FakeVerifier verifier, FakeAudio audio,
                RealtimeTranscriptSessions transcripts) throws Exception {
            this(tls, verifier, audio, transcripts, null);
        }

        private Fixture(TestTls tls, FakeVerifier verifier, FakeAudio audio,
                RealtimeTranscriptSessions transcripts,
                MeetingInputReconciler inputReconciler) throws Exception {
            this(tls, verifier, audio, transcripts, inputReconciler, null);
        }

        private Fixture(TestTls tls, FakeVerifier verifier, FakeAudio audio,
                RealtimeTranscriptSessions transcripts,
                MeetingInputReconciler inputReconciler,
                RealtimeEventLogPort eventLog) throws Exception {
            server = new MeetingRealtimeWssServer(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                    tls.server(), verifier, ingress(audio), transcripts,
                    inputReconciler, eventLog, clock);
        }

        private void start() throws Exception {
            server.start();
            server.awaitStarted(Duration.ofSeconds(5));
        }

        private URI uri(String meetingId) {
            return baseUri("/v1/realtime/" + meetingId);
        }

        private URI baseUri(String path) {
            return URI.create("wss://127.0.0.1:" + server.getPort() + path);
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                server.close();
            }
        }
    }

    private record TestTls(SSLContext server, SSLContext trusted) {
        private static TestTls create(Path directory) throws Exception {
            Path keystore = directory.resolve("loopback-test.p12");
            char[] password = ("temporary-" + UUID.randomUUID()).toCharArray();
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            Process process = new ProcessBuilder(keytool.toString(), "-genkeypair",
                    "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "1", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-storetype", "PKCS12", "-keystore", keystore.toString(),
                    "-storepass", new String(password), "-keypass", new String(password),
                    "-noprompt").redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("test certificate generation timed out");
            }
            assertEquals(0, process.exitValue());
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keystore)) {
                keys.load(input, password);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keys);
            SSLContext server = SSLContext.getInstance("TLS");
            server.init(keyManagers.getKeyManagers(), null, null);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, trust.getTrustManagers(), null);
            Arrays.fill(password, '\0');
            return new TestTls(server, client);
        }
    }
}
