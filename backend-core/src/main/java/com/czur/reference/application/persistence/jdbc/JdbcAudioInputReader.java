package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.AudioFrameContentDigest;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V015 单流输入的只读 JDBC 组件，由持久写端复用读取与帧事实核验。
 *
 * <p>一次读取使用一个只读 REPEATABLE_READ 事务：游标、完成声明和帧页来自同一
 * PostgreSQL 快照。{@code readCursor} 核对末帧与游标终点，
 * {@code replay} 校验本页从请求下一序号起连续；读取帧时重新计算 v1 完整内容
 * 摘要。损坏事实返回独立结果，不能伪装成缺失或空页。本组件不取得写权、不签发
 * ACK、不读取模型处理游标，也不向 EventLog 写原始音频。</p>
 *
 * <p>调用者负责既有会议/实时凭据授权；本类不新增认证层。数据库运行时持有
 * DataSource 的生命周期。本组件及写端尚未装配到生产入口。错误日志仅记录
 * 稳定分类和白名单 SQLState，不记录会议 ID、连接 ID、
 * 原始音频、摘要、Token 或完整声明。</p>
 */
final class JdbcAudioInputReader {
    private static final Logger LOGGER = Logger.getLogger(JdbcAudioInputReader.class.getName());
    private static final String PIN_SEARCH_PATH = """
            SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;
    private static final String CURSOR_SQL = """
            SELECT accepted_stream_sequence, accepted_audio_position_ms,
                   complete_last_stream_sequence, complete_last_audio_position_ms,
                   complete_recording_checksum, complete_recording_size_bytes,
                   complete_request_id
              FROM meeting_audio_input
             WHERE meeting_id = ?
            """;
    private static final String FRAME_SQL = """
            SELECT stream_sequence, start_position_ms, duration_ms, audio,
                   source_generation, format_revision, gap_duration_ms_before,
                   flags, digest_version, content_digest
              FROM meeting_audio_frame
             WHERE meeting_id = ? AND stream_sequence = ?
            """;
    private static final String PAGE_SQL = """
            SELECT stream_sequence, start_position_ms, duration_ms, audio,
                   source_generation, format_revision, gap_duration_ms_before,
                   flags, digest_version, content_digest
              FROM meeting_audio_frame
             WHERE meeting_id = ? AND stream_sequence > ?
             ORDER BY stream_sequence
             LIMIT ?
            """;

    private final DataSource dataSource;

    JdbcAudioInputReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 只读核对游标及末帧；无 V015 流行时返回 Missing。 */
    DurableAudioInputPort.CursorResult readCursor(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = openReadSnapshot()) {
            DurableAudioInputPort.Cursor cursor = loadCursor(connection, meetingId);
            if (cursor == null) {
                connection.rollback();
                return new DurableAudioInputPort.Missing();
            }
            validateLastFrame(connection, meetingId, cursor);
            connection.rollback();
            return new DurableAudioInputPort.CursorFound(cursor);
        } catch (CorruptFact failure) {
            LOGGER.log(Level.WARNING, "音频输入只读游标事实不一致");
            return new DurableAudioInputPort.FactInconsistentRead();
        } catch (SQLException failure) {
            return cursorSqlFailure(failure);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "音频输入只读游标不可用 category=INTERNAL");
            return new DurableAudioInputPort.UnavailableRead();
        }
    }

    /** 只读返回最多 1000 个从 afterSequence+1 开始连续的已接管帧。 */
    DurableAudioInputPort.ReplayResult replay(String meetingId, long afterSequence, int limit) {
        MeetingIdPolicy.requireValid(meetingId);
        if (afterSequence < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("invalid replay cursor or limit");
        }
        try (Connection connection = openReadSnapshot()) {
            DurableAudioInputPort.Cursor cursor = loadCursor(connection, meetingId);
            if (cursor == null) {
                connection.rollback();
                return new DurableAudioInputPort.Missing();
            }
            long accepted = cursor.acceptedStreamSequence();
            if (afterSequence > accepted) {
                connection.rollback();
                return new DurableAudioInputPort.InvalidCursor();
            }
            List<MeetingAudioFrame> frames = readPage(
                    connection, meetingId, afterSequence, limit, accepted);
            connection.rollback();
            return new DurableAudioInputPort.FramePage(frames, accepted);
        } catch (CorruptFact failure) {
            LOGGER.log(Level.WARNING, "音频帧重放事实不一致");
            return new DurableAudioInputPort.FactInconsistentRead();
        } catch (SQLException failure) {
            return replaySqlFailure(failure);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "音频帧重放不可用 category=INTERNAL");
            return new DurableAudioInputPort.UnavailableRead();
        }
    }

    /** 供同一 V015 事实域的完整输入核验复用，避免出现不同的快照初始化规则。 */
    Connection openReadSnapshot() throws SQLException {
        Connection connection = dataSource.getConnection();
        boolean ready = false;
        try {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(PIN_SEARCH_PATH)) {
                statement.executeQuery().close();
            }
            ready = true;
            return connection;
        } finally {
            if (!ready) connection.close();
        }
    }

    static DurableAudioInputPort.Cursor loadCursor(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CURSOR_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                long sequence = rows.getLong("accepted_stream_sequence");
                long position = rows.getLong("accepted_audio_position_ms");
                Long lastSequence = rows.getObject("complete_last_stream_sequence", Long.class);
                Long lastPosition = rows.getObject("complete_last_audio_position_ms", Long.class);
                String checksum = rows.getString("complete_recording_checksum");
                Long size = rows.getObject("complete_recording_size_bytes", Long.class);
                String requestId = rows.getString("complete_request_id");
                if (rows.next()) throw new CorruptFact();
                boolean allAbsent = lastSequence == null && lastPosition == null
                        && checksum == null && size == null && requestId == null;
                DurableAudioInputPort.Completion completion = null;
                if (!allAbsent) {
                    if (lastSequence == null || lastPosition == null || checksum == null
                            || size == null || requestId == null) throw new CorruptFact();
                    try {
                        completion = new DurableAudioInputPort.Completion(
                                lastSequence, lastPosition, checksum, size, requestId);
                    } catch (IllegalArgumentException malformed) {
                        throw new CorruptFact();
                    }
                    if (lastSequence < sequence || lastPosition < position) {
                        throw new CorruptFact();
                    }
                }
                try {
                    return new DurableAudioInputPort.Cursor(sequence, position, completion);
                } catch (IllegalArgumentException malformed) {
                    throw new CorruptFact();
                }
            }
        }
    }

    static void validateLastFrame(Connection connection, String meetingId,
            DurableAudioInputPort.Cursor cursor) throws SQLException {
        long accepted = cursor.acceptedStreamSequence();
        if (accepted > 0) {
            try (PreparedStatement statement = connection.prepareStatement(FRAME_SQL)) {
                statement.setString(1, meetingId);
                statement.setLong(2, accepted);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new CorruptFact();
                    MeetingAudioFrame last = decodeFrame(rows);
                    if (rows.next() || endPosition(last) != cursor.acceptedAudioPositionMs()) {
                        throw new CorruptFact();
                    }
                }
            }
        }
    }

    private static List<MeetingAudioFrame> readPage(Connection connection, String meetingId,
            long afterSequence, int limit, long accepted) throws SQLException {
        ArrayList<MeetingAudioFrame> frames = new ArrayList<>();
        long expected = afterSequence;
        try (PreparedStatement statement = connection.prepareStatement(PAGE_SQL)) {
            statement.setString(1, meetingId);
            statement.setLong(2, afterSequence);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (expected == Long.MAX_VALUE) throw new CorruptFact();
                    expected++;
                    MeetingAudioFrame frame = decodeFrame(rows);
                    if (frame.streamSequence() != expected || expected > accepted) {
                        throw new CorruptFact();
                    }
                    frames.add(frame);
                }
            }
        }
        if (frames.size() < limit && expected < accepted) throw new CorruptFact();
        return frames;
    }

    static MeetingAudioFrame decodeFrame(ResultSet rows) throws SQLException {
        long sequence = rows.getLong("stream_sequence");
        long start = rows.getLong("start_position_ms");
        int duration = rows.getInt("duration_ms");
        byte[] audio = rows.getBytes("audio");
        long source = rows.getLong("source_generation");
        int format = rows.getInt("format_revision");
        long gap = rows.getLong("gap_duration_ms_before");
        int bits = rows.getInt("flags");
        int version = rows.getInt("digest_version");
        byte[] storedDigest = rows.getBytes("content_digest");
        if (sequence <= 0 || start < 0 || duration < 1 || duration > 200
                || audio == null || audio.length != duration * 32 || audio.length > 6400
                || source < 0 || format < 0 || gap < 0 || bits < 0 || (bits & ~14) != 0
                || version != AudioFrameContentDigest.VERSION
                || storedDigest == null || storedDigest.length != 32) {
            throw new CorruptFact();
        }
        EnumSet<MeetingAudioFrame.AudioFrameFlag> flags =
                EnumSet.noneOf(MeetingAudioFrame.AudioFrameFlag.class);
        if ((bits & 2) != 0) flags.add(MeetingAudioFrame.AudioFrameFlag.AFTER_GAP);
        if ((bits & 4) != 0) flags.add(MeetingAudioFrame.AudioFrameFlag.SOURCE_CHANGED);
        if ((bits & 8) != 0) flags.add(MeetingAudioFrame.AudioFrameFlag.PLATFORM_RESTARTED);
        MeetingAudioFrame frame;
        try {
            frame = new MeetingAudioFrame(sequence, start, duration, audio, null,
                    source, format, gap, flags);
        } catch (IllegalArgumentException malformed) {
            throw new CorruptFact();
        }
        if (!MessageDigest.isEqual(storedDigest, AudioFrameContentDigest.sha256(frame))) {
            throw new CorruptFact();
        }
        return frame;
    }

    private static long endPosition(MeetingAudioFrame frame) {
        try {
            return Math.addExact(frame.startPositionMs(), frame.durationMs());
        } catch (ArithmeticException overflow) {
            throw new CorruptFact();
        }
    }

    private static DurableAudioInputPort.CursorResult cursorSqlFailure(SQLException failure) {
        LOGGER.log(Level.WARNING, "音频游标读取失败 sqlState={0}",
                JdbcReadLookupSupport.safeSqlState(failure));
        return JdbcReadLookupSupport.classify(failure)
                == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                ? new DurableAudioInputPort.FactInconsistentRead()
                : new DurableAudioInputPort.UnavailableRead();
    }

    private static DurableAudioInputPort.ReplayResult replaySqlFailure(SQLException failure) {
        LOGGER.log(Level.WARNING, "音频帧重放失败 sqlState={0}",
                JdbcReadLookupSupport.safeSqlState(failure));
        return JdbcReadLookupSupport.classify(failure)
                == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                ? new DurableAudioInputPort.FactInconsistentRead()
                : new DurableAudioInputPort.UnavailableRead();
    }

    static final class CorruptFact extends RuntimeException {
    }
}
