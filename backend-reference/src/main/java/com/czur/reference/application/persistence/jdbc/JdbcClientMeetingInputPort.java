package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.czur.reference.protocol.realtime.RealtimeTranslationEventMapper;

import javax.sql.DataSource;
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
    private final DataSource dataSource;
    private final RealtimeMeetingStartCoordinator startCoordinator;
    private final Clock clock;

    public JdbcClientMeetingInputPort(DataSource dataSource,
            RealtimeMeetingStartCoordinator startCoordinator, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.startCoordinator = Objects.requireNonNull(startCoordinator, "startCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        var first = new JdbcCreateMeetingFactStore(connection)
                .findByMeetingId(meetingId).orElse(null);
        if (first == null || !first.request().requestSnapshot().requestedCapabilities()
                .contains(CapabilityIds.TRANSLATION_REALTIME)) return false;
        return first.response().capabilityDecisions().stream().anyMatch(decision ->
                CapabilityIds.TRANSLATION_REALTIME.equals(decision.capability())
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
