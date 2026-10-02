package com.czur.reference.application.realtime;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.RealtimeStartFactsLookup;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 应用边界只把已确认持久帧转成可发送的音频 ACK 游标。 */
class RealtimeAudioIngressTest {
    private static final String MEETING = "meeting-realtime-audio";
    private static final String OTHER_MEETING = "meeting-realtime-other";
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant STARTED = CREATED.plusSeconds(10);
    private static final ProductProfile PROFILE = new ProductProfile(
            "profile", 1, Set.of("asr"), Set.of(), Map.of(), Map.of(), Map.of());
    private static final CapabilityDescriptor CAPABILITIES =
            new CapabilityDescriptor(Set.of("asr"));

    @Test
    void failedStartDoesNotBindWriter() {
        var audio = new FakeAudio();
        var ingress = ingress(new MeetingStartExecutionUnitOfWork.Failure(
                MeetingStartExecutionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE), audio, null);

        var result = assertInstanceOf(RealtimeAudioIngress.StartFailed.class,
                ingress.start(writeSession(MEETING), DurableAudioInputPort.WriterConnectionId.newConnection()));

        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE, result.code());
        assertEquals(0, audio.bindCalls);
    }

    @Test
    void successfulStartPrecedesBindingButReadyIsNotAnAck() {
        var order = new ArrayList<String>();
        var audio = new FakeAudio();
        var connection = DurableAudioInputPort.WriterConnectionId.newConnection();
        var binding = new DurableAudioInputPort.WriterBinding(MEETING, connection, 2);
        var cursor = new DurableAudioInputPort.Cursor(0, 0, null);
        audio.bindResult = new DurableAudioInputPort.Bound(binding, cursor);
        var ingress = ingress(startSuccess(MEETING), audio, order);
        audio.order = order;

        var ready = assertInstanceOf(RealtimeAudioIngress.Ready.class,
                ingress.start(writeSession(MEETING), connection));

        assertEquals(List.of("start", "bind"), order);
        assertEquals(binding, ready.binding());
        assertEquals(cursor, ready.cursor());
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, ready.startCode());
    }

    @Test
    void unknownBindCannotProduceAnAckOrReusableBinding() {
        var audio = new FakeAudio();
        audio.bindResult = new DurableAudioInputPort.Unknown();
        var ingress = ingress(startSuccess(MEETING), audio, null);

        assertInstanceOf(RealtimeAudioIngress.BindUnknown.class,
                ingress.start(writeSession(MEETING), DurableAudioInputPort.WriterConnectionId.newConnection()));
        assertEquals(1, audio.bindCalls);
        assertEquals(0, audio.appendCalls);
    }

    @Test
    void onlyStoredPositiveCursorProducesDurableAck() {
        var audio = new FakeAudio();
        audio.writeResult = new DurableAudioInputPort.Stored(
                new DurableAudioInputPort.Cursor(1, 20, null), false);

        var ack = assertInstanceOf(RealtimeAudioIngress.DurableAckCursor.class,
                ingress(startSuccess(MEETING), audio, null).append(
                        writeSession(MEETING), binding(MEETING), frame()));

        assertEquals(1, ack.acceptedStreamSequence());
        assertEquals(20, ack.acceptedAudioPositionMs());
        assertEquals(false, ack.replayed());
        assertEquals(1, audio.appendCalls);
    }

    @Test
    void unknownAppendNeverProducesAnAck() {
        var audio = new FakeAudio();
        audio.writeResult = new DurableAudioInputPort.Unknown();

        assertInstanceOf(RealtimeAudioIngress.AppendUnknown.class,
                ingress(startSuccess(MEETING), audio, null).append(
                        writeSession(MEETING), binding(MEETING), frame()));
    }

    @Test
    void fencedWriterRemainsRejectedWithoutAnOldAck() {
        var audio = new FakeAudio();
        audio.writeResult = new DurableAudioInputPort.Rejected(
                DurableAudioInputPort.Rejection.FENCED);

        var rejected = assertInstanceOf(RealtimeAudioIngress.AppendRejected.class,
                ingress(startSuccess(MEETING), audio, null).append(
                        writeSession(MEETING), binding(MEETING), frame()));

        assertEquals(DurableAudioInputPort.Rejection.FENCED, rejected.reason());
    }

    @Test
    void crossMeetingBindingIsRejectedBeforeStorage() {
        var audio = new FakeAudio();

        var rejected = assertInstanceOf(RealtimeAudioIngress.AppendRejected.class,
                ingress(startSuccess(MEETING), audio, null).append(
                        writeSession(MEETING), binding(OTHER_MEETING), frame()));

        assertEquals(DurableAudioInputPort.Rejection.INVALID_INPUT, rejected.reason());
        assertEquals(0, audio.appendCalls);
    }

    @Test
    void zeroOrBehindCursorIsNotAcknowledged() {
        var audio = new FakeAudio();
        var ingress = ingress(startSuccess(MEETING), audio, null);
        audio.writeResult = new DurableAudioInputPort.Stored(
                new DurableAudioInputPort.Cursor(0, 0, null), false);
        var zero = assertInstanceOf(RealtimeAudioIngress.AppendRejected.class,
                ingress.append(writeSession(MEETING), binding(MEETING), frame()));
        assertEquals(DurableAudioInputPort.Rejection.FACT_INCONSISTENT, zero.reason());

        audio.writeResult = new DurableAudioInputPort.Stored(
                new DurableAudioInputPort.Cursor(1, 20, null), false);
        var behind = assertInstanceOf(RealtimeAudioIngress.AppendRejected.class,
                ingress.append(writeSession(MEETING), binding(MEETING), frame(2)));
        assertEquals(DurableAudioInputPort.Rejection.FACT_INCONSISTENT, behind.reason());
    }

    @Test
    void readOnlyGrantCannotBecomeWriteSession() {
        assertThrows(IllegalArgumentException.class, () -> new RealtimeAudioIngress.VerifiedWriteSession(
                MEETING, grant(RealtimeCredentialVerifier.AccessMode.READ_ONLY)));
    }

    private static RealtimeAudioIngress ingress(MeetingStartExecutionUnitOfWork.Result result,
            FakeAudio audio, List<String> order) {
        RealtimeStartFactsLookup facts = (deployment, meetingId) ->
                new RealtimeStartFactsLookup.First(
                        "subject", "title", "profile", 1, Set.of("asr"));
        MeetingStartExecutionUnitOfWork startUnit = new MeetingStartExecutionUnitOfWork() {
            @Override public Result start(StartRequest request) {
                throw new AssertionError("legacy Start must not be called");
            }

            @Override public Result startCompleteCreate(StartRequest request) {
                if (order != null) order.add("start");
                return result;
            }
        };
        var coordinator = new RealtimeMeetingStartCoordinator(
                "deployment", facts, (id, revision) -> Optional.of(PROFILE),
                CAPABILITIES, DeploymentLimits.NONE,
                Clock.fixed(STARTED, ZoneOffset.UTC), startUnit);
        return new RealtimeAudioIngress(coordinator, audio);
    }

    private static MeetingStartExecutionUnitOfWork.Success startSuccess(String meetingId) {
        var plan = new CapabilityPlanner().plan(CAPABILITIES, PROFILE, Set.of("asr"),
                Set.of("asr"), DeploymentLimits.NONE, 1L);
        var fact = FinalizationExecutionFact.freeze(meetingId, STARTED, "title", PROFILE, plan);
        var meeting = MeetingAggregate.owned(
                meetingId, "deployment", "session", "subject", "grant",
                STARTED.plusSeconds(3_600), "reservation", "profile", 1, CREATED);
        meeting.apply(MeetingEvent.AUTHORIZE, STARTED);
        meeting.apply(MeetingEvent.START, STARTED);
        return new MeetingStartExecutionUnitOfWork.Success(
                MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, meeting.snapshot(), fact);
    }

    private static RealtimeAudioIngress.VerifiedWriteSession writeSession(String meetingId) {
        return new RealtimeAudioIngress.VerifiedWriteSession(
                meetingId, grant(RealtimeCredentialVerifier.AccessMode.WRITE));
    }

    private static RealtimeCredentialVerifier.Granted grant(
            RealtimeCredentialVerifier.AccessMode access) {
        return new RealtimeCredentialVerifier.Granted(access, STARTED.plusSeconds(3_600),
                new RealtimeCredentialVerifier.AudioContract(AudioProfile.pcm16kMono(20), 640));
    }

    private static DurableAudioInputPort.WriterBinding binding(String meetingId) {
        return new DurableAudioInputPort.WriterBinding(
                meetingId, DurableAudioInputPort.WriterConnectionId.newConnection(), 1);
    }

    private static MeetingAudioFrame frame() {
        return frame(1);
    }

    private static MeetingAudioFrame frame(long sequence) {
        return new MeetingAudioFrame(sequence, (sequence - 1) * 20, 20,
                new byte[640], null, 0, 0, 0, Set.of());
    }

    private static final class FakeAudio implements DurableAudioInputPort {
        private BindResult bindResult;
        private WriteResult writeResult;
        private List<String> order;
        private int bindCalls;
        private int appendCalls;

        @Override public BindResult bindWriter(String meetingId, WriterConnectionId connectionId) {
            bindCalls++;
            if (order != null) order.add("bind");
            return bindResult;
        }

        @Override public WriteResult append(WriterBinding binding, MeetingAudioFrame frame) {
            appendCalls++;
            return writeResult;
        }

        @Override public CursorResult readCursor(String meetingId) {
            throw new AssertionError("readCursor must not be called");
        }

        @Override public ReplayResult replay(String meetingId, long afterSequence, int limit) {
            throw new AssertionError("replay must not be called");
        }

        @Override public WriteResult complete(WriterBinding binding, Completion declaration) {
            throw new AssertionError("complete must not be called");
        }
    }
}
