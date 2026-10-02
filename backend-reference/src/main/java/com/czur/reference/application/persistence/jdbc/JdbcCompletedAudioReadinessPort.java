package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CompletedAudioReadinessPort;
import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.AudioFrameContentDigest;
import com.czur.reference.application.realtime.AudioStreamContentDigest;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V015 完整音频的只读 JDBC 核验器，供后续会议级收口编排调用。
 *
 * <p>游标、完成声明和全部帧都从同一个只读 REPEATABLE_READ 快照读取。数据库中的
 * 帧摘要不是可信输入：先用原始音频及时间轴字段重算每帧摘要，再按序送入版本化
 * 流摘要。读取采用 JDBC 游标逐帧处理，不把整场音频装进 JVM。已接管前缀有缺口、
 * 损坏或游标与实际帧不一致时返回 Inconsistent；声明先到而末帧仍未接管时返回
 * Pending，不提前签发完整事实。独立录音文件尚无上传校验，带文件声明也不能就绪。</p>
 *
 * <p>本端口不验证 Stop、身份或会议归属，不写库、不调用模型，也不接管连接池。
 * 调用方必须在可信会议上下文中使用；错误日志只输出类别和白名单 SQLState，绝不
 * 输出会议 ID、原始音频、摘要、Token 或完成声明。</p>
 */
public final class JdbcCompletedAudioReadinessPort implements CompletedAudioReadinessPort {
    private static final Logger LOGGER = Logger.getLogger(JdbcCompletedAudioReadinessPort.class.getName());
    private static final String FRAMES = """
            SELECT stream_sequence, start_position_ms, duration_ms, audio,
                   source_generation, format_revision, gap_duration_ms_before,
                   flags, digest_version, content_digest
              FROM meeting_audio_frame
             WHERE meeting_id = ?
             ORDER BY stream_sequence
            """;
    private static final int FETCH_SIZE = 256;

    private final JdbcAudioInputReader reader;

    /** @param dataSource 已完成 V015 迁移且生命周期由运行时持有的连接源 */
    public JdbcCompletedAudioReadinessPort(DataSource dataSource) {
        reader = new JdbcAudioInputReader(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 从一次持久快照判断输入是否完整；结果不代表 Stop 已提交或 receipt 已签发。
     * 存储不可读返回 Unavailable，持久事实自相矛盾返回 Inconsistent，均不降为 Pending。
     */
    @Override
    public Result check(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = reader.openReadSnapshot()) {
            DurableAudioInputPort.Cursor cursor = JdbcAudioInputReader.loadCursor(
                    connection, meetingId);
            if (cursor == null) {
                connection.rollback();
                return new Missing();
            }
            Scan scan = scanFrames(connection, meetingId, cursor.acceptedStreamSequence());
            if (scan.sequence() != cursor.acceptedStreamSequence()
                    || scan.endPositionMs() != cursor.acceptedAudioPositionMs()) {
                throw new JdbcAudioInputReader.CorruptFact();
            }
            DurableAudioInputPort.Completion completion = cursor.completion();
            // 参考 Mock 的写端拒绝独立录音；若持久库里仍出现这类声明，说明事实绕过
            // 当前接管合同，不能让会议永久处于 Pending，也绝不可签发 Ready。
            if (completion != null && completion.recordingSizeBytes() != 0) {
                throw new JdbcAudioInputReader.CorruptFact();
            }
            if (completion == null || cursor.acceptedStreamSequence()
                    < completion.lastStreamSequence()) {
                connection.rollback();
                return new Pending();
            }
            // loadCursor 已检查声明终点 >= 游标；序号相等但终点不同，不是待补帧。
            if (cursor.acceptedStreamSequence() != completion.lastStreamSequence()
                    || scan.endPositionMs() != completion.lastAudioPositionMs()) {
                throw new JdbcAudioInputReader.CorruptFact();
            }
            String digest = scan.digest().finish(completion.lastStreamSequence(),
                    completion.lastAudioPositionMs());
            connection.rollback();
            return new Ready(meetingId, completion.lastStreamSequence(),
                    completion.lastAudioPositionMs(), digest);
        } catch (JdbcAudioInputReader.CorruptFact | IllegalArgumentException
                 | ArithmeticException failure) {
            LOGGER.log(Level.WARNING, "完整音频读取发现不一致事实 module=audio-readiness");
            return new Inconsistent();
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "完整音频读取存储故障 module=audio-readiness sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return JdbcReadLookupSupport.classify(failure)
                    == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                    ? new Inconsistent() : new Unavailable();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "完整音频读取不可用 module=audio-readiness category=INTERNAL");
            return new Unavailable();
        }
    }

    /** 顺序扫描整个逻辑流，包括游标外的异常多余帧；单帧处理后立即丢弃原始字节。 */
    private static Scan scanFrames(Connection connection, String meetingId, long accepted)
            throws SQLException {
        AudioStreamContentDigest digest = new AudioStreamContentDigest();
        long sequence = 0;
        long end = 0;
        try (PreparedStatement statement = connection.prepareStatement(FRAMES)) {
            statement.setString(1, meetingId);
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (sequence == Long.MAX_VALUE) throw new JdbcAudioInputReader.CorruptFact();
                    MeetingAudioFrame frame = JdbcAudioInputReader.decodeFrame(rows);
                    if (frame.streamSequence() != sequence + 1 || frame.streamSequence() > accepted
                            || frame.startPositionMs() != Math.addExact(end,
                            frame.gapDurationMsBefore())) {
                        throw new JdbcAudioInputReader.CorruptFact();
                    }
                    end = Math.addExact(frame.startPositionMs(), frame.durationMs());
                    digest.append(frame.streamSequence(), AudioFrameContentDigest.VERSION,
                            AudioFrameContentDigest.sha256(frame));
                    sequence++;
                }
            }
        }
        return new Scan(sequence, end, digest);
    }

    private record Scan(long sequence, long endPositionMs, AudioStreamContentDigest digest) { }
}
