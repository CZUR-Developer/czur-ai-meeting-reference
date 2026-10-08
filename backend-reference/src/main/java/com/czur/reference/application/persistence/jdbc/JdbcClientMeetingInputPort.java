package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.czur.reference.protocol.realtime.RealtimeTranslationEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端字幕和译文的 PostgreSQL 输入边界，复用 meeting_event 作为唯一标准事件源。
 *
 * <p>存在 meeting_client_input 行表示本场由 App 提交事件；缺行表示旧后端音频链路。
 * 首次 start 先持久登记输入来源，再复用现有 Start 协调器冻结执行事实，避免把
 * WSS 音频帧伪装成客户端事件。每次 append 锁同一 meeting 行，分配会议全局序号，
 * 同事务保存完整 ServerEnvelope 和客户端事件对应关系。只记录状态/数量，不记录正文。</p>
 */
public final class JdbcClientMeetingInputPort implements ClientMeetingInputPort {
    private static final Logger LOGGER = Logger.getLogger(JdbcClientMeetingInputPort.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SUMMARY_JSON_BYTES = 256 * 1024;
    private final DataSource dataSource;
    private final RealtimeMeetingStartCoordinator startCoordinator;
    private final Clock clock;
    private final ArtifactStore artifactStore;

    @Override
    public UsageSubmitResult submitUsage(MeetingCommandContext context,
            String meetingId, long audioDurationMs) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        if (audioDurationMs < 0) throw new IllegalArgumentException("audio duration is negative");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MeetingRow meeting = lockMeeting(connection, meetingId);
                Rejected rejected = checkOwner(context, meeting);
                if (rejected != null) return rollback(connection, rejected);
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                Long previous;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT audio_duration_ms FROM meeting_client_input WHERE meeting_id=?")) {
                    statement.setString(1, meetingId);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        previous = rows.getObject(1, Long.class);
                    }
                }
                // 同场Stop完成后仍可重放原值；不能更新已冻结计费事实。
                if (previous != null) {
                    connection.rollback();
                    LOGGER.log(Level.FINE, "客户端采音用量重放，原值保持");
                    return previous == audioDurationMs ? new UsageSubmitted(previous, false)
                            : new Rejected(Reason.EVENT_CONFLICT);
                }
                if (meeting.state() != MeetingState.STARTING
                        && meeting.state() != MeetingState.RUNNING) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                long reservationVersion;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT r.version FROM meeting m JOIN usage_reservation r
                          ON r.reservation_id=m.usage_reservation_id
                         AND r.deployment_scope=m.deployment_scope
                         AND r.subject_scope=m.subject_scope
                        WHERE m.meeting_id=? AND r.bound_meeting_id=m.meeting_id
                          AND r.record_format='V3' AND r.status='RESERVED'
                        """)) {
                    statement.setString(1, meetingId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            return rollback(connection, new Rejected(Reason.INVALID_STATE));
                        }
                        reservationVersion = rows.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE meeting_client_input SET audio_duration_ms=?,
                            usage_submitted_at=?, usage_expected_version=? WHERE meeting_id=?
                        """)) {
                    statement.setLong(1, audioDurationMs);
                    statement.setTimestamp(2, Timestamp.from(
                            clock.instant().truncatedTo(ChronoUnit.MICROS)));
                    statement.setLong(3, reservationVersion);
                    statement.setString(4, meetingId);
                    statement.executeUpdate();
                }
                connection.commit();
                LOGGER.log(Level.INFO, "客户端采音用量已冻结 durationMs={0}", audioDurationMs);
                return new UsageSubmitted(audioDurationMs, true);
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                LOGGER.log(Level.WARNING, "客户端采音用量保存未完成");
                return new Rejected(Reason.STORAGE_UNAVAILABLE);
            }
        } catch (SQLException failure) {
            return new Rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public SummarySubmitResult submitSummary(MeetingCommandContext context,
            String meetingId, StructuredSummary summary) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(summary, "summary");
        final String content;
        try {
            content = JSON.writeValueAsString(summary);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("summary serialization unavailable", impossible);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_JSON_BYTES) {
            throw new IllegalArgumentException("summary is too large");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MeetingRow meeting = lockMeeting(connection, meetingId);
                Rejected rejected = checkOwner(context, meeting);
                if (rejected != null) return rollback(connection, rejected);
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                if (!capabilityEnabled(connection, meetingId,
                        CapabilityIds.SUMMARY_STRUCTURED)) {
                    return rollback(connection, new Rejected(Reason.SUMMARY_NOT_GRANTED));
                }
                String previous = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT summary_json FROM meeting_client_summary WHERE meeting_id=?")) {
                    statement.setString(1, meetingId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) previous = rows.getString(1);
                    }
                }
                if (previous != null) {
                    connection.rollback();
                    return previous.equals(content) ? new SummarySubmitted(false)
                            : new Rejected(Reason.EVENT_CONFLICT);
                }
                if (meeting.state() != MeetingState.STARTING
                        && meeting.state() != MeetingState.RUNNING) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO meeting_client_summary
                            (meeting_id, summary_json, submitted_at) VALUES (?, ?, ?)
                        """)) {
                    statement.setString(1, meetingId);
                    statement.setString(2, content);
                    statement.setTimestamp(3, Timestamp.from(
                            clock.instant().truncatedTo(ChronoUnit.MICROS)));
                    statement.executeUpdate();
                }
                connection.commit();
                LOGGER.log(Level.FINE, "客户端标准纪要已保存");
                return new SummarySubmitted(true);
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                LOGGER.log(Level.WARNING, "客户端标准纪要保存失败 reason={0}",
                        failure.getClass().getSimpleName());
                return new Rejected(Reason.STORAGE_UNAVAILABLE);
            }
        } catch (SQLException failure) {
            return new Rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    public JdbcClientMeetingInputPort(DataSource dataSource,
            RealtimeMeetingStartCoordinator startCoordinator, Clock clock) {
        this(dataSource, startCoordinator, clock, null);
    }

    public JdbcClientMeetingInputPort(DataSource dataSource,
            RealtimeMeetingStartCoordinator startCoordinator, Clock clock,
            ArtifactStore artifactStore) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.startCoordinator = Objects.requireNonNull(startCoordinator, "startCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.artifactStore = artifactStore;
    }

    @Override
    public CaptureAppendResult appendPresentation(MeetingCommandContext context,
            String meetingId, PresentationChunk request) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(request, "request");
        if (artifactStore == null) return new Rejected(Reason.STORAGE_UNAVAILABLE);
        byte[] content = request.content();
        String checksum = "sha256:" + sha256(content);
        String artifactId = "art_client_presentation_" + sha256((meetingId + "\u0000"
                + request.chunkSequence()).getBytes(StandardCharsets.UTF_8));
        WriteIntentSpec spec = new WriteIntentSpec(artifactId, meetingId,
                "presentation.capture", "application/json", null,
                request.chunkSequence(), content.length, checksum);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MeetingRow meeting = lockMeeting(connection, meetingId);
                Rejected rejected = checkOwner(context, meeting);
                if (rejected != null) return rollback(connection, rejected);
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                if (!presentationRequested(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.PRESENTATION_NOT_REQUESTED));
                }
                var metadata = new JdbcArtifactReadyMetadataStore(connection);
                var previous = metadata.findByArtifactId(artifactId);
                if (previous.isPresent()) {
                    connection.rollback();
                    return previous.orElseThrow().artifact().intent().spec().equals(spec)
                            ? new CaptureAppended(artifactId, request.chunkSequence(),
                                    request.frameCount(), false)
                            : new Rejected(Reason.EVENT_CONFLICT);
                }
                if (meeting.state() != MeetingState.STARTING
                        && meeting.state() != MeetingState.RUNNING) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                if (request.chunkSequence() != lastPresentationSequence(connection, meetingId) + 1) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                var prepared = artifactStore.createWriteIntent(spec);
                if (prepared.status() == ArtifactStore.PrepareStatus.CONFLICT) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                var intent = prepared.intent();
                var claim = new ContentClaim(content.length, checksum);
                artifactStore.writeContent(intent, new ByteArrayInputStream(content));
                var descriptor = artifactStore.completeWrite(intent, claim).artifact();
                var persisted = metadata.persistReady(descriptor);
                if (persisted.status() == ArtifactReadyMetadataStore.PersistStatus.CONFLICT) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                connection.commit();
                return new CaptureAppended(artifactId, request.chunkSequence(),
                        request.frameCount(), persisted.status()
                        == ArtifactReadyMetadataStore.PersistStatus.APPLIED);
            } catch (Exception failure) {
                rollbackQuietly(connection);
                LOGGER.log(Level.WARNING, "演示截图包保存失败 reason={0}",
                        failure.getClass().getSimpleName());
                return new Rejected(Reason.STORAGE_UNAVAILABLE);
            }
        } catch (SQLException failure) {
            return new Rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static boolean presentationRequested(Connection connection, String meetingId) {
        var first = new JdbcCreateMeetingFactStore(connection)
                .findByMeetingId(meetingId).orElse(null);
        return first != null && first.request().requestSnapshot()
                .artifactPolicy().presentationCapture();
    }

    private static int lastPresentationSequence(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(revision), 0) FROM artifact "
                + "WHERE meeting_id=? AND artifact_type='presentation.capture'")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public StartResult start(MeetingCommandContext context, String meetingId) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        Rejected source = markClientSource(context, meetingId);
        if (source != null) return source;
        MeetingStartExecutionUnitOfWork.Result result = startCoordinator.start(meetingId);
        if (result instanceof MeetingStartExecutionUnitOfWork.Success success) {
            MeetingState state = success.meeting().state();
            LOGGER.log(Level.INFO, "客户端会议启动 state={0}", state);
            return new Started(state);
        }
        MeetingStartExecutionUnitOfWork.ErrorCode code =
                ((MeetingStartExecutionUnitOfWork.Failure) result).code();
        return new Rejected(switch (code) {
            case MEETING_NOT_FOUND -> Reason.NOT_FOUND;
            case ACCESS_DENIED -> Reason.ACCESS_DENIED;
            case INVALID_MEETING_STATE -> Reason.INVALID_STATE;
            case STORAGE_UNAVAILABLE, OUTCOME_UNKNOWN -> Reason.STORAGE_UNAVAILABLE;
            default -> Reason.INPUT_SOURCE_CONFLICT;
        });
    }

    @Override
    public AppendResult append(MeetingCommandContext context, String meetingId,
            ClientEvent request) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(request, "request");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MeetingRow meeting = lockMeeting(connection, meetingId);
                Rejected rejected = checkOwner(context, meeting);
                if (rejected != null) return rollback(connection, rejected);
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                if (meeting.state() != MeetingState.STARTING
                        && meeting.state() != MeetingState.RUNNING) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                if (request instanceof ClientTranslation
                        && !translationEnabled(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.CAPABILITY_NOT_GRANTED));
                }
                if (request instanceof ClientTranscript transcript
                        && transcript.transcript().speaker() != null
                        && !speakerEnabled(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.SPEAKER_NOT_GRANTED));
                }

                ClientEventRow previous = findClientEvent(connection, meetingId,
                        request.clientEventId());
                if (previous != null) {
                    EventLog winner = JdbcMeetingEventLogStore.findByEventId(
                            connection, previous.eventId()).orElse(null);
                    if (winner == null || previous.clientSequence() != request.clientSequence()
                            || !sameEvent(winner, request)) {
                        return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                    }
                    connection.rollback();
                    return new Appended(winner, false);
                }
                if (request.clientSequence() != lastClientSequence(connection, meetingId) + 1) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                long sequence = Math.addExact(
                        JdbcMeetingEventLogStore.currentSequence(connection, meetingId), 1);
                String eventId = eventId(meetingId, request.clientEventId());
                EventLog event = createEvent(meetingId, eventId, sequence,
                        clock.instant().truncatedTo(ChronoUnit.MICROS), request);
                EventLogStore.AppendResult appended = JdbcMeetingEventLogStore.append(
                        connection, event);
                if (appended.status() != EventLogStore.AppendStatus.APPENDED) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO meeting_client_event
                            (meeting_id, client_event_id, client_sequence, event_id)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setString(1, meetingId);
                    statement.setString(2, request.clientEventId());
                    statement.setLong(3, request.clientSequence());
                    statement.setString(4, eventId);
                    statement.executeUpdate();
                }
                connection.commit();
                LOGGER.log(Level.FINE, "客户端标准事件已保存 type={0}", event.eventType());
                return new Appended(event, true);
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                LOGGER.log(Level.WARNING, "客户端标准事件写入失败 reason={0}",
                        failure.getClass().getSimpleName());
                return new Rejected(Reason.STORAGE_UNAVAILABLE);
            }
        } catch (SQLException failure) {
            return new Rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    /** 能力以本场首次持久化 Create 决策为准；全局配置变动不改变既有会议。 */
    private static boolean translationEnabled(Connection connection, String meetingId) {
        return capabilityEnabled(connection, meetingId, CapabilityIds.TRANSLATION_REALTIME);
    }

    private static boolean speakerEnabled(Connection connection, String meetingId) {
        return capabilityEnabled(connection, meetingId,
                CapabilityIds.SPEAKER_DIARIZATION_SESSION);
    }

    private static boolean capabilityEnabled(Connection connection, String meetingId,
            String capability) {
        var first = new JdbcCreateMeetingFactStore(connection)
                .findByMeetingId(meetingId).orElse(null);
        if (first == null || !first.request().requestSnapshot().requestedCapabilities()
                .contains(capability)) return false;
        return first.response().capabilityDecisions().stream().anyMatch(decision ->
                capability.equals(decision.capability())
                && decision.action() == CapabilityDecision.Action.ENABLE);
    }

    private static boolean sameEvent(EventLog winner, ClientEvent request) {
        return switch (request) {
            case ClientTranscript transcript -> RealtimeTranscriptEventMapper.sameTranscript(
                    winner, transcript.transcript());
            case ClientTranslation translation -> RealtimeTranslationEventMapper.sameTranslation(
                    winner, translation.translation());
        };
    }

    private static EventLog createEvent(String meetingId, String eventId, long sequence,
            java.time.Instant at, ClientEvent request) {
        return switch (request) {
            case ClientTranscript transcript -> RealtimeTranscriptEventMapper.event(
                    meetingId, eventId, sequence, at, transcript.transcript());
            case ClientTranslation translation -> RealtimeTranslationEventMapper.event(
                    meetingId, eventId, sequence, at, translation.translation());
        };
    }

    private Rejected markClientSource(MeetingCommandContext context, String meetingId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MeetingRow meeting = lockMeeting(connection, meetingId);
                Rejected rejected = checkOwner(context, meeting);
                if (rejected != null) return rollback(connection, rejected);
                boolean alreadyClient = clientSourceExists(connection, meetingId);
                if (!alreadyClient && meeting.state() != MeetingState.CREATED
                        && meeting.state() != MeetingState.AUTHORIZED) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                if (alreadyClient && meeting.state() != MeetingState.CREATED
                        && meeting.state() != MeetingState.AUTHORIZED
                        && meeting.state() != MeetingState.STARTING
                        && meeting.state() != MeetingState.RUNNING) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                if (!alreadyClient) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO meeting_client_input (meeting_id, started_at)
                            VALUES (?, ?)
                            """)) {
                        statement.setString(1, meetingId);
                        statement.setTimestamp(2, Timestamp.from(
                                clock.instant().truncatedTo(ChronoUnit.MICROS)));
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return null;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                LOGGER.log(Level.WARNING, "客户端会议启动标记失败 reason={0}",
                        failure.getClass().getSimpleName());
                return new Rejected(Reason.STORAGE_UNAVAILABLE);
            }
        } catch (SQLException failure) {
            return new Rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static MeetingRow lockMeeting(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deployment_scope, subject_scope, state FROM meeting
                WHERE meeting_id = ? FOR UPDATE
                """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new MeetingRow(rows.getString(1), rows.getString(2),
                        MeetingState.valueOf(rows.getString(3)));
            }
        }
    }

    private static Rejected checkOwner(MeetingCommandContext context, MeetingRow meeting) {
        if (meeting == null) return new Rejected(Reason.NOT_FOUND);
        return MeetingAccessGuard.evaluate(context, meeting.deploymentScope(),
                meeting.subjectScope()) == MeetingAccessGuard.Decision.ALLOWED
                ? null : new Rejected(Reason.ACCESS_DENIED);
    }

    private static boolean clientSourceExists(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM meeting_client_input WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static ClientEventRow findClientEvent(Connection connection, String meetingId,
            String clientEventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT client_sequence, event_id FROM meeting_client_event
                WHERE meeting_id=? AND client_event_id=?
                """)) {
            statement.setString(1, meetingId);
            statement.setString(2, clientEventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new ClientEventRow(rows.getLong(1), rows.getString(2)) : null;
            }
        }
    }

    private static long lastClientSequence(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(client_sequence), 0) FROM meeting_client_event WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String eventId(String meetingId, String clientEventId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(meetingId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(clientEventId.getBytes(StandardCharsets.UTF_8));
            return "evt_client_" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static Rejected rollback(Connection connection, Rejected rejected)
            throws SQLException {
        connection.rollback();
        return rejected;
    }

    private static void rollbackQuietly(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    private record MeetingRow(String deploymentScope, String subjectScope,
            MeetingState state) { }
    private record ClientEventRow(long clientSequence, String eventId) { }
}
