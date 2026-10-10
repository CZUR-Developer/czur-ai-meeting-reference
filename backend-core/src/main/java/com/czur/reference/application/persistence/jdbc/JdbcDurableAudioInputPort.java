package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.AudioFrameContentDigest;
import com.czur.reference.application.realtime.RealtimeAccessPolicy;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V015 单会议单流的 JDBC 写端与已验收只读端组合。
 *
 * <p>每次写入固定按 meeting 行、V013 首次 Create 赢家、V015 当前写者行的
 * 顺序读取，且整个操作在一个显式事务内完成。会议行锁串行化 Start、Stop、
 * bind 与帧写入；epoch 和服务端连接 ID 在事务中栅栏旧连接。CREATED 仅允许
 * bind，首次 append/complete 必须等待 v2 Start 已提交。读取由单快照 Reader
 * 完成，不从内存推断持久 ACK。</p>
 *
 * <p>调用方负责实时 Token、会议归属及过期核验，以及同连接只调用一次 bind。
 * 本类不接管连接池、网络 ACK、模型或最终收口。提交调用开始后任一异常均返回
 * Unknown，调用方不能据此发送 durable ACK；绑定未知时必须关闭原连接。
 * 日志只记录阶段和安全 SQLState，不记录会议/连接 ID、音频、Token 或摘要。</p>
 */
public final class JdbcDurableAudioInputPort implements DurableAudioInputPort {
    private static final Logger LOGGER = Logger.getLogger(JdbcDurableAudioInputPort.class.getName());
    private static final String PIN = "SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE)";
    private static final String MEETING = """
            SELECT deployment_scope, subject_scope, client_session_id, profile_id,
                   profile_revision, created_at, state, input_state
              FROM meeting WHERE meeting_id = ? FOR UPDATE
            """;
    private static final String INPUT = """
            SELECT writer_id, writer_epoch FROM meeting_audio_input
             WHERE meeting_id = ? FOR UPDATE
            """;
    private static final String FRAME = """
            SELECT stream_sequence, start_position_ms, duration_ms, audio,
                   source_generation, format_revision, gap_duration_ms_before,
                   flags, digest_version, content_digest
              FROM meeting_audio_frame
             WHERE meeting_id = ? AND stream_sequence = ?
            """;
    private final DataSource dataSource;
    private final String deploymentScope;
    private final JdbcAudioInputReader reader;

    /**
     * @param dataSource 已完成 V013/V015 迁移的唯一数据库连接源；生命周期由运行时持有
     * @param deploymentScope 服务端可信部署范围，不得取自客户端输入
     */
    public JdbcDurableAudioInputPort(DataSource dataSource, String deploymentScope) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope is required");
        }
        this.deploymentScope = deploymentScope;
        this.reader = new JdbcAudioInputReader(dataSource);
    }

    /** 原子替换当前写者并递增 epoch；提交未知时不能复用该连接 ID。 */
    @Override
    public BindResult bindWriter(String meetingId, WriterConnectionId connectionId) {
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(connectionId, "connectionId");
        boolean commitStarted = false;
        try (Connection connection = begin()) {
            MeetingFact meeting = meeting(connection, meetingId);
            if (meeting == null) return rejectBind(connection, Rejection.DENIED);
            CreateMeetingResponseFact first = firstCreate(connection, meetingId, meeting);
            if (first == null) return rejectBind(connection, Rejection.DENIED);
            Rejection access = access(meeting, false);
            if (access != null) return rejectBind(connection, access);
            InputFact input = input(connection, meetingId);
            Cursor cursor = input == null ? new Cursor(0, 0, null)
                    : checkedCursor(connection, meetingId);
            requireCursorWithinLimits(cursor, first);
            long epoch;
            if (input == null) {
                epoch = 1;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO meeting_audio_input (meeting_id, writer_id, writer_epoch)
                        VALUES (?, ?, 1)
                        """)) {
                    statement.setString(1, meetingId);
                    statement.setObject(2, connectionId.value());
                    requireOne(statement.executeUpdate());
                }
            } else {
                if (input.epoch() == Long.MAX_VALUE) throw new BadFact();
                epoch = input.epoch() + 1;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE meeting_audio_input SET writer_id = ?, writer_epoch = ?
                         WHERE meeting_id = ?
                        """)) {
                    statement.setObject(1, connectionId.value());
                    statement.setLong(2, epoch);
                    statement.setString(3, meetingId);
                    requireOne(statement.executeUpdate());
                }
            }
            commitStarted = true;
            connection.commit();
            LOGGER.log(Level.INFO, "音频写者绑定已提交 module=audio-input");
            return new Bound(new WriterBinding(meetingId, connectionId, epoch), cursor);
        } catch (BadFact | JdbcAudioInputReader.CorruptFact failure) {
            return commitStarted ? new Unknown() : new Rejected(Rejection.FACT_INCONSISTENT);
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            return commitStarted ? new Unknown() : storedFactFailureBind(failure);
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "音频写者绑定存储故障 module=audio-input sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return commitStarted ? new Unknown() : sqlFailureBind(failure);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "音频写者绑定故障 module=audio-input category=INTERNAL");
            return commitStarted ? new Unknown() : new Unavailable(false);
        }
    }

    /** 仅在当前 writer/epoch 与可写会议状态均仍有效时追加或重放完整帧事实。 */
    @Override
    public WriteResult append(WriterBinding binding, MeetingAudioFrame frame) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(frame, "frame");
        boolean commitStarted = false;
        try (Connection connection = begin()) {
            MeetingFact meeting = meeting(connection, binding.meetingId());
            if (meeting == null) return rejectWrite(connection, Rejection.DENIED);
            CreateMeetingResponseFact first = firstCreate(connection, binding.meetingId(), meeting);
            if (first == null) return rejectWrite(connection, Rejection.DENIED);
            InputFact input = input(connection, binding.meetingId());
            if (input == null || !input.matches(binding)) {
                return rejectWrite(connection, Rejection.FENCED);
            }
            Rejection access = access(meeting, true);
            if (access != null) return rejectWrite(connection, access);
            Cursor cursor = checkedCursor(connection, binding.meetingId());
            requireCursorWithinLimits(cursor, first);
            byte[] digest;
            try {
                digest = AudioFrameContentDigest.sha256(frame);
            } catch (IllegalArgumentException invalid) {
                return rejectWrite(connection, Rejection.INVALID_INPUT);
            }
            long end;
            try {
                end = Math.addExact(frame.startPositionMs(), frame.durationMs());
            } catch (ArithmeticException invalid) {
                return rejectWrite(connection, Rejection.LIMIT_EXCEEDED);
            }
            if (frame.durationMs() > first.acceptedAudioProfile().frameDurationMs()
                    || frame.audio().length != frame.durationMs() * 32
                    || frame.audio().length > Math.min(6400, first.limits().maxAudioFrameBytes())
                    || end > first.limits().maxMeetingDurationMs()) {
                return rejectWrite(connection, Rejection.LIMIT_EXCEEDED);
            }
            if (frame.streamSequence() <= cursor.acceptedStreamSequence()) {
                MeetingAudioFrame stored = readFrame(connection, binding.meetingId(),
                        frame.streamSequence());
                if (stored == null) throw new BadFact();
                boolean same = MessageDigest.isEqual(digest, AudioFrameContentDigest.sha256(stored));
                connection.rollback();
                return same ? new Stored(cursor, true) : new Rejected(Rejection.CONFLICT);
            }
            if (cursor.acceptedStreamSequence() == Long.MAX_VALUE
                    || frame.streamSequence() != cursor.acceptedStreamSequence() + 1) {
                return rejectWrite(connection, Rejection.GAP);
            }
            long expectedStart;
            try {
                expectedStart = Math.addExact(cursor.acceptedAudioPositionMs(),
                        frame.gapDurationMsBefore());
            } catch (ArithmeticException invalid) {
                return rejectWrite(connection, Rejection.LIMIT_EXCEEDED);
            }
            if (frame.startPositionMs() != expectedStart) {
                return rejectWrite(connection, Rejection.CONFLICT);
            }
            Completion completion = cursor.completion();
            if (completion != null && (frame.streamSequence() > completion.lastStreamSequence()
                    || end > completion.lastAudioPositionMs()
                    || (frame.streamSequence() == completion.lastStreamSequence()
                    && end != completion.lastAudioPositionMs()))) {
                return rejectWrite(connection, Rejection.CONFLICT);
            }
            insertFrame(connection, binding.meetingId(), frame, digest);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE meeting_audio_input SET accepted_stream_sequence = ?,
                        accepted_audio_position_ms = ? WHERE meeting_id = ?
                    """)) {
                statement.setLong(1, frame.streamSequence());
                statement.setLong(2, end);
                statement.setString(3, binding.meetingId());
                requireOne(statement.executeUpdate());
            }
            commitStarted = true;
            connection.commit();
            return new Stored(new Cursor(frame.streamSequence(), end, completion), false);
        } catch (BadFact | JdbcAudioInputReader.CorruptFact failure) {
            return commitStarted ? new Unknown() : new Rejected(Rejection.FACT_INCONSISTENT);
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            return commitStarted ? new Unknown() : storedFactFailureWrite(failure);
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "音频帧写入存储故障 module=audio-input sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return commitStarted ? new Unknown() : sqlFailureWrite(failure);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "音频帧写入故障 module=audio-input category=INTERNAL");
            return commitStarted ? new Unknown() : new Unavailable(false);
        }
    }

    /** 同一声明重放不改写事实；新的声明只能由当前 epoch 在可写状态保存。 */
    @Override
    public WriteResult complete(WriterBinding binding, Completion declaration) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(declaration, "declaration");
        boolean commitStarted = false;
        try (Connection connection = begin()) {
            MeetingFact meeting = meeting(connection, binding.meetingId());
            if (meeting == null) return rejectWrite(connection, Rejection.DENIED);
            CreateMeetingResponseFact first = firstCreate(connection, binding.meetingId(), meeting);
            if (first == null) {
                return rejectWrite(connection, Rejection.DENIED);
            }
            InputFact input = input(connection, binding.meetingId());
            if (input == null || !input.matches(binding)) {
                return rejectWrite(connection, Rejection.FENCED);
            }
            Cursor cursor = checkedCursor(connection, binding.meetingId());
            requireCursorWithinLimits(cursor, first);
            Rejection access = access(meeting, true);
            if (cursor.completion() != null && cursor.completion().equals(declaration)
                    && (access == null || acceptedReplay(meeting))) {
                connection.rollback();
                return new Stored(cursor, true);
            }
            if (access != null) return rejectWrite(connection, access);
            if (cursor.completion() != null) return rejectWrite(connection, Rejection.CONFLICT);
            // P2 Mock 还没有独立录音上传事实，不能接收一个无法核实的文件摘要。
            if (declaration.recordingSizeBytes() != 0) {
                return rejectWrite(connection, Rejection.INVALID_INPUT);
            }
            if (declaration.lastAudioPositionMs() == 0
                    || declaration.lastAudioPositionMs() > first.limits().maxMeetingDurationMs()
                    || declaration.lastStreamSequence() > first.limits().maxMeetingDurationMs()) {
                return rejectWrite(connection, Rejection.LIMIT_EXCEEDED);
            }
            if (declaration.lastStreamSequence() < cursor.acceptedStreamSequence()
                    || declaration.lastAudioPositionMs() < cursor.acceptedAudioPositionMs()
                    || (declaration.lastStreamSequence() == cursor.acceptedStreamSequence()
                    && declaration.lastAudioPositionMs() != cursor.acceptedAudioPositionMs())) {
                return rejectWrite(connection, Rejection.CONFLICT);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE meeting_audio_input SET complete_last_stream_sequence = ?,
                        complete_last_audio_position_ms = ?, complete_recording_checksum = ?,
                        complete_recording_size_bytes = ?, complete_request_id = ?
                     WHERE meeting_id = ?
                    """)) {
                statement.setLong(1, declaration.lastStreamSequence());
                statement.setLong(2, declaration.lastAudioPositionMs());
                statement.setString(3, declaration.recordingChecksum());
                statement.setLong(4, declaration.recordingSizeBytes());
                statement.setString(5, declaration.requestId());
                statement.setString(6, binding.meetingId());
                requireOne(statement.executeUpdate());
            }
            commitStarted = true;
            connection.commit();
            LOGGER.log(Level.INFO, "音频完成声明已提交 module=audio-input");
            return new Stored(new Cursor(cursor.acceptedStreamSequence(),
                    cursor.acceptedAudioPositionMs(), declaration), false);
        } catch (BadFact | JdbcAudioInputReader.CorruptFact failure) {
            return commitStarted ? new Unknown() : new Rejected(Rejection.FACT_INCONSISTENT);
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            return commitStarted ? new Unknown() : storedFactFailureWrite(failure);
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "音频声明写入存储故障 module=audio-input sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return commitStarted ? new Unknown() : sqlFailureWrite(failure);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "音频声明写入故障 module=audio-input category=INTERNAL");
            return commitStarted ? new Unknown() : new Unavailable(false);
        }
    }

    @Override public CursorResult readCursor(String meetingId) { return reader.readCursor(meetingId); }
    @Override public ReplayResult replay(String meetingId, long afterSequence, int limit) {
        return reader.replay(meetingId, afterSequence, limit);
    }

    private Connection begin() throws SQLException {
        Connection connection = dataSource.getConnection();
        boolean ready = false;
        try {
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT current_schema()");
                 ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new BadFact();
                String schema = rows.getString(1);
                if (schema == null || schema.startsWith("pg_temp")) throw new BadFact();
            }
            try (PreparedStatement statement = connection.prepareStatement(PIN)) {
                statement.executeQuery().close();
            }
            ready = true;
            return connection;
        } finally {
            if (!ready) connection.close();
        }
    }

    private MeetingFact meeting(Connection connection, String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MEETING)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                MeetingFact fact = new MeetingFact(rows.getString("deployment_scope"),
                        rows.getString("subject_scope"), rows.getString("client_session_id"),
                        rows.getString("profile_id"), rows.getInt("profile_revision"),
                        rows.getTimestamp("created_at"), rows.getString("state"),
                        rows.getString("input_state"));
                if (rows.next()) throw new BadFact();
                return fact;
            }
        }
    }

    private CreateMeetingResponseFact firstCreate(Connection connection, String meetingId,
            MeetingFact meeting) {
        if (!deploymentScope.equals(meeting.scope()) || meeting.subject() == null
                || meeting.subject().isBlank()
                || MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(meeting.subject())) {
            return null;
        }
        CreateMeetingFactStore.Record first = new JdbcCreateMeetingFactStore(connection)
                .findByMeetingId(meetingId).orElse(null);
        if (first == null) return null;
        if (!first.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                meeting.scope(), meeting.subject()))
                || !first.request().requestSnapshot().clientSessionId().equals(meeting.session())
                || !first.response().acceptedProfile().id().equals(meeting.profile())
                || first.response().acceptedProfile().revision() != meeting.profileRevision()
                || meeting.createdAt() == null
                || !first.createdAt().equals(meeting.createdAt().toInstant())) {
            throw new BadFact();
        }
        return first.response();
    }

    private static Rejection access(MeetingFact meeting, boolean requireStarted) {
        try {
            MeetingState state = MeetingState.valueOf(meeting.state());
            InputState input = InputState.valueOf(meeting.inputState());
            RealtimeAccessPolicy.Decision decision = RealtimeAccessPolicy.classify(state, input);
            if (decision == RealtimeAccessPolicy.Decision.FACT_INCONSISTENT) {
                return Rejection.FACT_INCONSISTENT;
            }
            if (decision != RealtimeAccessPolicy.Decision.WRITE) return Rejection.DENIED;
            if (requireStarted && state == MeetingState.CREATED) return Rejection.DENIED;
            return null;
        } catch (RuntimeException malformed) {
            return Rejection.FACT_INCONSISTENT;
        }
    }

    private static boolean acceptedReplay(MeetingFact meeting) {
        try {
            return InputState.valueOf(meeting.inputState()) == InputState.INPUT_ACCEPTED
                    && RealtimeAccessPolicy.classify(MeetingState.valueOf(meeting.state()),
                            InputState.INPUT_ACCEPTED)
                    == RealtimeAccessPolicy.Decision.READ_ONLY;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static InputFact input(Connection connection, String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INPUT)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                UUID writer = rows.getObject("writer_id", UUID.class);
                long epoch = rows.getLong("writer_epoch");
                if (writer == null || epoch <= 0 || rows.next()) throw new BadFact();
                return new InputFact(writer, epoch);
            }
        }
    }

    private static Cursor checkedCursor(Connection connection, String meetingId) throws SQLException {
        Cursor cursor = JdbcAudioInputReader.loadCursor(connection, meetingId);
        if (cursor == null) throw new BadFact();
        JdbcAudioInputReader.validateLastFrame(connection, meetingId, cursor);
        return cursor;
    }

    private static void requireCursorWithinLimits(Cursor cursor, CreateMeetingResponseFact first) {
        long maximum = first.limits().maxMeetingDurationMs();
        if (cursor.acceptedStreamSequence() > maximum
                || cursor.acceptedAudioPositionMs() > maximum) throw new BadFact();
        Completion completion = cursor.completion();
        if (completion != null && (completion.lastStreamSequence() > maximum
                || completion.lastAudioPositionMs() > maximum
                || completion.recordingSizeBytes() != 0)) throw new BadFact();
    }

    private static MeetingAudioFrame readFrame(Connection connection, String meetingId,
            long sequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FRAME)) {
            statement.setString(1, meetingId);
            statement.setLong(2, sequence);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                MeetingAudioFrame frame = JdbcAudioInputReader.decodeFrame(rows);
                if (rows.next()) throw new BadFact();
                return frame;
            }
        }
    }

    private static void insertFrame(Connection connection, String meetingId,
            MeetingAudioFrame frame, byte[] digest) throws SQLException {
        int flags = 0;
        for (MeetingAudioFrame.AudioFrameFlag flag : frame.flags()) {
            flags |= switch (flag) {
                case AFTER_GAP -> 2;
                case SOURCE_CHANGED -> 4;
                case PLATFORM_RESTARTED -> 8;
                case UNKNOWN -> throw new BadFact();
            };
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO meeting_audio_frame (meeting_id, stream_sequence,
                    start_position_ms, duration_ms, audio, source_generation,
                    format_revision, gap_duration_ms_before, flags, digest_version,
                    content_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, meetingId);
            statement.setLong(2, frame.streamSequence());
            statement.setLong(3, frame.startPositionMs());
            statement.setInt(4, frame.durationMs());
            statement.setBytes(5, frame.audio());
            statement.setLong(6, frame.sourceGeneration());
            statement.setInt(7, frame.formatRevision());
            statement.setLong(8, frame.gapDurationMsBefore());
            statement.setInt(9, flags);
            statement.setInt(10, AudioFrameContentDigest.VERSION);
            statement.setBytes(11, digest);
            requireOne(statement.executeUpdate());
        }
    }

    private static BindResult rejectBind(Connection connection, Rejection reason) throws SQLException {
        connection.rollback();
        return new Rejected(reason);
    }
    private static WriteResult rejectWrite(Connection connection, Rejection reason) throws SQLException {
        connection.rollback();
        return new Rejected(reason);
    }
    private static void requireOne(int count) {
        if (count != 1) throw new BadFact();
    }
    private static BindResult storedFactFailureBind(
            JdbcCreateMeetingFactStore.StoredFactException failure) {
        return failure.kind() == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA
                ? new Rejected(Rejection.FACT_INCONSISTENT) : new Unavailable(true);
    }
    private static WriteResult storedFactFailureWrite(
            JdbcCreateMeetingFactStore.StoredFactException failure) {
        return failure.kind() == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA
                ? new Rejected(Rejection.FACT_INCONSISTENT) : new Unavailable(true);
    }
    private static BindResult sqlFailureBind(SQLException failure) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        return kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                ? new Rejected(Rejection.FACT_INCONSISTENT)
                : new Unavailable(kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE);
    }
    private static WriteResult sqlFailureWrite(SQLException failure) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        return kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                ? new Rejected(Rejection.FACT_INCONSISTENT)
                : new Unavailable(kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE);
    }
    private record MeetingFact(String scope, String subject, String session, String profile,
            int profileRevision, java.sql.Timestamp createdAt, String state, String inputState) { }
    private record InputFact(UUID writer, long epoch) {
        boolean matches(WriterBinding binding) {
            return epoch == binding.writerEpoch() && writer.equals(binding.connectionId().value());
        }
    }
    private static final class BadFact extends RuntimeException { }
}
