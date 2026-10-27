package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.MeetingBodyArtifactBudget;
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
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端字幕和译文的公共 JDBC 输入边界，复用 meeting_event 作为唯一标准事件源。
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
    private final JdbcDatabaseEngine engine;
    private final RealtimeMeetingStartCoordinator startCoordinator;
    private final Clock clock;
    private final ArtifactStore artifactStore;
    private final long maxClientArtifactBytes;

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
                // 已落弃场决定的会议不可再活跃：字幕/译文/用量/纪要/截图/录音/Start 全部拒绝。
                if (abandonmentDecided(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                Long previous;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT audio_duration_ms FROM " + table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)
                                + " WHERE meeting_id=?" + currentRead())) {
                    JdbcValues.setText(statement, 1, engine, meetingId);
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
                        SELECT r.version FROM %s m JOIN %s r
                          ON r.reservation_id=m.usage_reservation_id
                         AND r.deployment_scope=m.deployment_scope
                         AND r.subject_scope=m.subject_scope
                        WHERE m.meeting_id=? AND r.bound_meeting_id=m.meeting_id
                          AND r.record_format='V3' AND r.status='RESERVED'
                        """.formatted(table(JdbcDatabaseTable.MEETING),
                                table(JdbcDatabaseTable.USAGE_RESERVATION)) + currentRead())) {
                    JdbcValues.setText(statement, 1, engine, meetingId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            return rollback(connection, new Rejected(Reason.INVALID_STATE));
                        }
                        reservationVersion = rows.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE %s SET audio_duration_ms=?,
                            usage_submitted_at=?, usage_expected_version=? WHERE meeting_id=?
                        """.formatted(table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)))) {
                    statement.setLong(1, audioDurationMs);
                    JdbcValues.setInstant(statement, 2, clock.instant());
                    statement.setLong(3, reservationVersion);
                    JdbcValues.setText(statement, 4, engine, meetingId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("client usage freeze did not update one row");
                    }
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
                // 已落弃场决定的会议不可再活跃：字幕/译文/用量/纪要/截图/录音/Start 全部拒绝。
                if (abandonmentDecided(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                if (!capabilityEnabled(connection, meetingId,
                        CapabilityIds.SUMMARY_STRUCTURED)) {
                    return rollback(connection, new Rejected(Reason.SUMMARY_NOT_GRANTED));
                }
                String previous = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT summary_json FROM " + table(JdbcDatabaseTable.MEETING_CLIENT_SUMMARY)
                                + " WHERE meeting_id=?" + currentRead())) {
                    JdbcValues.setText(statement, 1, engine, meetingId);
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
                        INSERT INTO %s
                            (meeting_id, summary_json, submitted_at) VALUES (?, ?, ?)
                        """.formatted(table(JdbcDatabaseTable.MEETING_CLIENT_SUMMARY)))) {
                    JdbcValues.setText(statement, 1, engine, meetingId);
                    statement.setString(2, content);
                    JdbcValues.setInstant(statement, 3, clock.instant());
                    statement.executeUpdate();
                }
                if (!withinClientBodyBudget(connection, meetingId)) {
                    connection.rollback();
                    LOGGER.log(Level.INFO, "客户端纪要累计超过部署预算，已拒绝");
                    return new Rejected(Reason.CONTENT_TOO_LARGE);
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
        this(dataSource, JdbcDatabaseEngine.MYSQL, startCoordinator, clock, artifactStore,
                MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /** 使用公共唯一池和原 Start 协调器；未装配存储时仅截图不可用。 */
    public JdbcClientMeetingInputPort(JdbcDatabaseContext database,
            RealtimeMeetingStartCoordinator startCoordinator, Clock clock,
            ArtifactStore artifactStore) {
        this(database.dataSource(), database.engine(), startCoordinator, clock, artifactStore,
                MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /** 不需要截图存储的客户端输入兼容入口。 */
    public JdbcClientMeetingInputPort(JdbcDatabaseContext database,
            RealtimeMeetingStartCoordinator startCoordinator, Clock clock) {
        this(database, startCoordinator, clock, null);
    }

    /**
     * 显式注入与发布、读取和 HTTP 响应相同的最终正文预算。
     *
     * <p>append 受理必须使用该部署值累计最终序列化正文，提前拒绝 Stop 必然
     * 超限的会议；旧构造器沿用 2 MiB 兼容下限，生产装配不得使用。</p>
     */
    public JdbcClientMeetingInputPort(JdbcDatabaseContext database,
            RealtimeMeetingStartCoordinator startCoordinator, Clock clock,
            ArtifactStore artifactStore, long maxClientArtifactBytes) {
        this(database.dataSource(), database.engine(), startCoordinator, clock, artifactStore,
                maxClientArtifactBytes);
    }

    private JdbcClientMeetingInputPort(DataSource dataSource, JdbcDatabaseEngine engine,
            RealtimeMeetingStartCoordinator startCoordinator, Clock clock,
            ArtifactStore artifactStore, long maxClientArtifactBytes) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.startCoordinator = Objects.requireNonNull(startCoordinator, "startCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.artifactStore = artifactStore;
        if (maxClientArtifactBytes <= 0) {
            throw new IllegalArgumentException("client artifact budget must be positive");
        }
        this.maxClientArtifactBytes = maxClientArtifactBytes;
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
                // 已落弃场决定的会议不可再活跃：字幕/译文/用量/纪要/截图/录音/Start 全部拒绝。
                if (abandonmentDecided(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                if (!presentationRequested(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.PRESENTATION_NOT_REQUESTED));
                }
                var metadata = new JdbcArtifactReadyMetadataStore(connection, engine);
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

    @Override
    public AudioAppendResult appendAudio(MeetingCommandContext context, String meetingId,
            ClientMeetingInputPort.AudioArtifact request) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(request, "request");
        if (artifactStore == null) return new Rejected(Reason.STORAGE_UNAVAILABLE);
        String artifactId = "art_client_audio_" + sha256((meetingId + "\u0000audio.original")
                .getBytes(StandardCharsets.UTF_8));
        WriteIntentSpec spec = new WriteIntentSpec(artifactId, meetingId,
                "audio.original", request.mediaType(), null, 1,
                request.contentSize(), request.contentChecksum());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MeetingRow meeting = lockMeeting(connection, meetingId);
                Rejected rejected = checkOwner(context, meeting);
                if (rejected != null) return rollback(connection, rejected);
                // 已落弃场决定的会议不可再活跃：字幕/译文/用量/纪要/截图/录音/Start 全部拒绝。
                if (abandonmentDecided(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                if (!clientSourceExists(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INPUT_SOURCE_CONFLICT));
                }
                if (!audioRetentionRequested(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.AUDIO_NOT_REQUESTED));
                }
                var metadata = new JdbcArtifactReadyMetadataStore(connection, engine);
                var previous = metadata.findByArtifactId(artifactId);
                if (previous.isPresent()) {
                    // 重放不读取、不关闭调用方的流；文件可能已经被调用方复用。
                    connection.rollback();
                    return previous.orElseThrow().artifact().intent().spec().equals(spec)
                            ? new AudioAppended(artifactId, request.contentSize(), false)
                            : new Rejected(Reason.EVENT_CONFLICT);
                }
                if (meeting.state() != MeetingState.STARTING
                        && meeting.state() != MeetingState.RUNNING) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
                var prepared = artifactStore.createWriteIntent(spec);
                if (prepared.status() == ArtifactStore.PrepareStatus.CONFLICT) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                var intent = prepared.intent();
                var claim = new ContentClaim(request.contentSize(), request.contentChecksum());
                // ArtifactStore 明确不接管输入流；所有权继续由 SDK/调用方持有。
                artifactStore.writeContent(intent, request.content());
                var descriptor = artifactStore.completeWrite(intent, claim).artifact();
                var persisted = metadata.persistReady(descriptor);
                if (persisted.status() == ArtifactReadyMetadataStore.PersistStatus.CONFLICT) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                connection.commit();
                LOGGER.log(Level.INFO, "客户端原始录音已登记 size={0} created={1}",
                        new Object[]{request.contentSize(),
                                persisted.status() == ArtifactReadyMetadataStore.PersistStatus.APPLIED});
                return new AudioAppended(artifactId, request.contentSize(),
                        persisted.status() == ArtifactReadyMetadataStore.PersistStatus.APPLIED);
            } catch (Exception failure) {
                rollbackQuietly(connection);
                LOGGER.log(Level.WARNING, "客户端原始录音保存失败 reason={0}",
                        failure.getClass().getSimpleName());
                return new Rejected(Reason.STORAGE_UNAVAILABLE);
            }
        } catch (SQLException failure) {
            return new Rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private boolean audioRetentionRequested(Connection connection, String meetingId) {
        var first = new JdbcCreateMeetingFactStore(connection, engine)
                .findByMeetingId(meetingId).orElse(null);
        return first != null && first.request().requestSnapshot().artifactPolicy().retainAudio();
    }

    private boolean presentationRequested(Connection connection, String meetingId) {
        var first = new JdbcCreateMeetingFactStore(connection, engine)
                .findByMeetingId(meetingId).orElse(null);
        return first != null && first.request().requestSnapshot()
                .artifactPolicy().presentationCapture();
    }

    private int lastPresentationSequence(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                engine == JdbcDatabaseEngine.MYSQL
                        ? "SELECT revision FROM " + table(JdbcDatabaseTable.ARTIFACT)
                                + " WHERE meeting_id=? AND artifact_type='presentation.capture'"
                                + " ORDER BY revision DESC LIMIT 1 FOR UPDATE"
                        : "SELECT COALESCE(MAX(revision), 0) FROM artifact "
                                + "WHERE meeting_id=? AND artifact_type='presentation.capture'")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
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
                // 已落弃场决定的会议不可再活跃：字幕/译文/用量/纪要/截图/录音/Start 全部拒绝。
                if (abandonmentDecided(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
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
                            connection, engine, previous.eventId()).orElse(null);
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
                        JdbcMeetingEventLogStore.currentSequence(connection, engine, meetingId), 1);
                String eventId = eventId(meetingId, request.clientEventId());
                EventLog event = createEvent(meetingId, eventId, sequence,
                        clock.instant().truncatedTo(ChronoUnit.MICROS), request);
                EventLogStore.AppendResult appended = JdbcMeetingEventLogStore.append(
                        connection, engine, event);
                if (appended.status() != EventLogStore.AppendStatus.APPENDED) {
                    return rollback(connection, new Rejected(Reason.EVENT_CONFLICT));
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO %s
                            (meeting_id, client_event_id, client_sequence, event_id)
                        VALUES (?, ?, ?, ?)
                        """.formatted(table(JdbcDatabaseTable.MEETING_CLIENT_EVENT)))) {
                    JdbcValues.setText(statement, 1, engine, meetingId);
                    JdbcValues.setText(statement, 2, engine, request.clientEventId());
                    statement.setLong(3, request.clientSequence());
                    JdbcValues.setText(statement, 4, engine, eventId);
                    statement.executeUpdate();
                }
                // 单条请求上限之外还必须按“最终聚合正文”累计：同一 segment 的
                // 后续修订可能让最终产物超过部署预算，Stop 时再拒绝只会把会议卡在
                // STOPPING。这里在受理事务内按发布端同一序列化口径预检并回滚。
                if (!withinClientBodyBudget(connection, meetingId)) {
                    connection.rollback();
                    LOGGER.log(Level.INFO, "客户端正文累计超过部署预算，已拒绝 type={0}",
                            event.eventType());
                    return new Rejected(Reason.CONTENT_TOO_LARGE);
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

    /**
     * 在受理事务内按最终序列化口径核对部署正文预算。
     *
     * <p>与发布、读取和 HTTP 响应共用同一个部署预算；恰好等于预算允许，超出
     * 1 字节即拒绝，让客户端在 Stop 前得到明确错误而不是停止后卡住。</p>
     */
    private boolean withinClientBodyBudget(Connection connection, String meetingId)
            throws SQLException {
        return JdbcClientMeetingResultFinalizer.serializedClientBodyBytes(
                connection, engine, meetingId) <= maxClientArtifactBytes;
    }

    /** 能力以本场首次持久化 Create 决策为准；全局配置变动不改变既有会议。 */
    private boolean translationEnabled(Connection connection, String meetingId) {
        return capabilityEnabled(connection, meetingId, CapabilityIds.TRANSLATION_REALTIME);
    }

    private boolean speakerEnabled(Connection connection, String meetingId) {
        return capabilityEnabled(connection, meetingId,
                CapabilityIds.SPEAKER_DIARIZATION_SESSION);
    }

    private boolean capabilityEnabled(Connection connection, String meetingId,
            String capability) {
        var first = new JdbcCreateMeetingFactStore(connection, engine)
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
                // 已落弃场决定的会议不可再活跃：字幕/译文/用量/纪要/截图/录音/Start 全部拒绝。
                if (abandonmentDecided(connection, meetingId)) {
                    return rollback(connection, new Rejected(Reason.INVALID_STATE));
                }
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
                            INSERT INTO %s (meeting_id, started_at)
                            VALUES (?, ?)
                            """.formatted(table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)))) {
                        JdbcValues.setText(statement, 1, engine, meetingId);
                        JdbcValues.setInstant(statement, 2, clock.instant());
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

    private MeetingRow lockMeeting(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deployment_scope, subject_scope, state FROM %s
                WHERE meeting_id = ? FOR UPDATE
                """.formatted(table(JdbcDatabaseTable.MEETING)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new MeetingRow(JdbcValues.text(rows, "deployment_scope"),
                        JdbcValues.text(rows, "subject_scope"),
                        MeetingState.valueOf(JdbcValues.text(rows, "state")));
            }
        }
    }

    private static Rejected checkOwner(MeetingCommandContext context, MeetingRow meeting) {
        if (meeting == null) return new Rejected(Reason.NOT_FOUND);
        return MeetingAccessGuard.evaluate(context, meeting.deploymentScope(),
                meeting.subjectScope()) == MeetingAccessGuard.Decision.ALLOWED
                ? null : new Rejected(Reason.ACCESS_DENIED);
    }

    private boolean clientSourceExists(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)
                        + " WHERE meeting_id=?" + currentRead())) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    /**
     * 本场是否已落弃场决定；所有写入入口在会议行锁内复核。
     *
     * <p>决定表示扫描器已冻结收口事实，会议不能再接受任何新输入；即使 A 类
     * 决定尚未取消（等待零用量结算），也必须按不可活跃处理。</p>
     */
    private boolean abandonmentDecided(Connection connection, String meetingId)
            throws SQLException {
        return JdbcMeetingAbandonmentStore.exists(connection, engine, meetingId);
    }

    private ClientEventRow findClientEvent(Connection connection, String meetingId,
            String clientEventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT client_sequence, event_id FROM %s
                WHERE meeting_id=? AND client_event_id=?
                """.formatted(table(JdbcDatabaseTable.MEETING_CLIENT_EVENT)) + currentRead())) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            JdbcValues.setText(statement, 2, engine, clientEventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new ClientEventRow(rows.getLong(1),
                        JdbcValues.text(rows, "event_id")) : null;
            }
        }
    }

    private long lastClientSequence(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                engine == JdbcDatabaseEngine.MYSQL
                        ? "SELECT client_sequence FROM " + table(JdbcDatabaseTable.MEETING_CLIENT_EVENT)
                                + " WHERE meeting_id=? ORDER BY client_sequence DESC LIMIT 1 FOR UPDATE"
                        : "SELECT COALESCE(MAX(client_sequence), 0) FROM meeting_client_event WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
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

    private String table(JdbcDatabaseTable table) {
        return table.physicalName(engine);
    }

    /** 本类所有决定性读取都在 Meeting 行锁之后；MySQL 明确使用当前读。 */
    private String currentRead() {
        return engine == JdbcDatabaseEngine.MYSQL ? " FOR UPDATE" : "";
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
