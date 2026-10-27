package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.realtime.RealtimeTranscriptEventPublisher;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

/**
 * V004 meeting_event 的 Mock 字幕事件发布适配器，不新增处理游标或队列表。
 *
 * <p>一个短事务先锁会议行，再以会议、分段和修订稳定 eventId 查旧赢家。旧赢家须严格
 * 解码且与本帧 Mock 字幕一致，直接返回其原时间、全局序号与 JSON；只有新事件才
 * 分配下一个会议全局序号、构造完整 ServerEnvelope 并复用既有 append 原语。
 * 提交未知时一律失败关闭，后续新连接重放 V015 帧自然按 eventId 恢复。</p>
 */
public final class JdbcRealtimeTranscriptEventPublisher
        implements RealtimeTranscriptEventPublisher {
    private final DataSource dataSource;
    private final Clock clock;

    public JdbcRealtimeTranscriptEventPublisher(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Published publish(String meetingId, CanonicalTranscriptEvent transcript) {
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(transcript, "transcript");
        String eventId = eventId(meetingId, transcript);
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean committed = false;
        try {
            connection = dataSource.getConnection();
            transactionStarted = true;
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            if (!JdbcMeetingEventLogStore.lockMeeting(connection, meetingId)) {
                throw new IllegalStateException("mock transcript meeting unavailable");
            }
            EventLog winner = JdbcMeetingEventLogStore.findByEventId(connection, eventId)
                    .orElse(null);
            if (winner != null) {
                if (!meetingId.equals(winner.meetingId())
                        || !RealtimeTranscriptEventMapper.sameTranscript(winner, transcript)) {
                    throw new IllegalStateException("mock transcript event inconsistent");
                }
                connection.commit();
                committed = true;
                return new Published(winner, false);
            }
            long sequence = Math.addExact(
                    JdbcMeetingEventLogStore.currentSequence(connection, meetingId), 1L);
            EventLog candidate = RealtimeTranscriptEventMapper.event(meetingId, eventId,
                    sequence, clock.instant(), transcript);
            EventLogStore.AppendResult appended = JdbcMeetingEventLogStore.append(
                    connection, candidate);
            if (appended.status() == EventLogStore.AppendStatus.CONFLICT
                    || !RealtimeTranscriptEventMapper.sameTranscript(
                            appended.event(), transcript)) {
                throw new IllegalStateException("mock transcript append inconsistent");
            }
            connection.commit();
            committed = true;
            return new Published(appended.event(),
                    appended.status() == EventLogStore.AppendStatus.APPENDED);
        } catch (SQLException | RuntimeException failure) {
            throw new IllegalStateException("mock transcript publish unavailable");
        } finally {
            finishConnection(connection, previousAutoCommit, transactionStarted, committed);
        }
    }

    /** 提交未知时也只失败关闭；回滚未确认则废弃物理连接，不归还未决事务。 */
    private static void finishConnection(Connection connection, boolean previousAutoCommit,
            boolean transactionStarted, boolean committed) {
        if (connection == null) return;
        boolean safeToReturn = !transactionStarted || committed;
        if (transactionStarted && !committed) {
            try {
                connection.rollback();
                safeToReturn = true;
            } catch (SQLException | RuntimeException ignored) {
                safeToReturn = false;
            }
        }
        if (safeToReturn) {
            try {
                if (!connection.isClosed()) connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException | RuntimeException ignored) {
                // 状态未确认，物理连接不可返回池。
                safeToReturn = false;
            }
        }
        if (!safeToReturn) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException ignored) {
                // close 仍会尽最大努力释放连接代理。
            }
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            // 发布结果已确定或已按提交未知失败；不得改写返回语义。
        }
    }

    private static String eventId(String meetingId, CanonicalTranscriptEvent transcript) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(meetingId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(transcript.segmentId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(transcript.revision()).getBytes(
                    StandardCharsets.US_ASCII));
            return "evt_mock_" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("mock event identity unavailable");
        }
    }
}
