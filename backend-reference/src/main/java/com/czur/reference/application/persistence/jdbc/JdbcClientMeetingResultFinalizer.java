package com.czur.reference.application.persistence.jdbc;

import com.czur.aimeeting.contract.realtime.v1.TranscriptUpsert;
import com.czur.reference.application.port.ClientMeetingResultFinalizer;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 将 App 已保存的最终字幕直接冻结为参考结果，不接触原有音频 Worker。
 *
 * <p>Stop 命令先独立提交。此实现随后锁定同一会议，读取客户端标准事件，按
 * segmentId 采用最后修订，并在一个事务中保存最终正文和 READY 状态。若最后
 * 修订尚非 final，保持 Stop 的 202 等待状态；App 应先提交最终修订再停止。
 * P4 仅验证 Mock 字幕结果，演示预占的生产结算留待后续阶段。</p>
 */
public final class JdbcClientMeetingResultFinalizer implements ClientMeetingResultFinalizer {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcClientMeetingResultFinalizer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;
    private final FinalizationReceiptGenerator receipts;
    private final Clock clock;

    public JdbcClientMeetingResultFinalizer(DataSource dataSource,
            FinalizationReceiptGenerator receipts, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean finalizeIfClient(String meetingId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcMeetingRepository.VersionedMeeting locked =
                        JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                                .orElse(null);
                if (locked == null || !isClient(connection, meetingId)) {
                    connection.rollback();
                    return false;
                }
                if (resultExists(connection, meetingId)) {
                    connection.rollback();
                    return true;
                }
                if (locked.snapshot().state() != MeetingState.STOPPING) {
                    connection.rollback();
                    return true;
                }
                Projection projection = project(connection, meetingId);
                if (projection == null) {
                    connection.rollback();
                    return true;
                }
                Instant endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (endedAt.isBefore(locked.snapshot().updatedAt())) {
                    endedAt = locked.snapshot().updatedAt();
                }
                String artifactId = "art_client_" + sha256(meetingId.getBytes(
                        StandardCharsets.UTF_8));
                ObjectNode artifact = JSON.createObjectNode();
                artifact.put("schemaVersion", "client-transcript/v1");
                artifact.put("artifactType", "transcript.final");
                artifact.put("language", projection.language());
                artifact.put("text", projection.text());
                String artifactJson = artifact.toString();
                byte[] content = artifactJson.getBytes(StandardCharsets.UTF_8);
                MeetingManifest manifest = new MeetingManifest(
                        "0.1", meetingId, 1,
                        new MeetingManifest.Profile(locked.snapshot().profileId(),
                                locked.snapshot().profileRevision()),
                        null, com.czur.reference.application.port.ManifestStatus.READY,
                        locked.snapshot().createdAt(), endedAt, projection.durationMs(),
                        new MeetingManifest.Languages(projection.language(), List.of()),
                        List.of(new MeetingManifest.ArtifactRef(artifactId,
                                "transcript.final", "application/json", projection.language(),
                                1, content.length, "sha256:" + sha256(content),
                                MeetingManifest.ArtifactStatus.READY, endedAt, null, null)),
                        List.of());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO meeting_client_result
                            (meeting_id, artifact_id, manifest_json, artifact_json)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setString(1, meetingId);
                    statement.setString(2, artifactId);
                    statement.setString(3, MeetingManifestJson.encode(manifest));
                    statement.setString(4, artifactJson);
                    statement.executeUpdate();
                }

                // 同一事务内按领域状态机依次前进；外部只会看到最终 READY。
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
                aggregate.apply(MeetingEvent.INPUT_ACCEPTED, endedAt);
                JdbcMeetingRepository.updateOwnedExisting(connection, locked,
                        aggregate.snapshot());
                var finalizing = new JdbcMeetingRepository.VersionedMeeting(
                        aggregate.snapshot(), locked.aggregateVersion() + 1);
                aggregate.apply(MeetingEvent.READY, endedAt);
                JdbcMeetingRepository.updateOwnedExisting(connection, finalizing,
                        aggregate.snapshot());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE meeting SET input_state='INPUT_ACCEPTED',
                            finalization_receipt_id=?, manifest_state='READY'
                        WHERE meeting_id=? AND state='READY'
                        """)) {
                    statement.setString(1, receipts.newReceipt());
                    statement.setString(2, meetingId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("client result state update failed");
                    }
                }
                connection.commit();
                LOGGER.log(Level.INFO, "客户端最终字幕已生成 segments={0}",
                        projection.segmentCount());
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                LOGGER.log(Level.WARNING, "客户端最终字幕生成暂未完成 reason={0}",
                        failure.getClass().getSimpleName());
                throw new IllegalStateException("client result finalization unavailable");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("client result database unavailable");
        }
    }

    private static boolean isClient(Connection connection, String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM meeting_client_input WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static boolean resultExists(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM meeting_client_result WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static Projection project(Connection connection, String meetingId)
            throws SQLException {
        Map<String, TranscriptUpsert> latest = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.meeting_id, e.event_sequence, e.event_id, e.event_type,
                       e.canonical_payload, e.occurred_at, e.spec_version, e.stream_id
                  FROM meeting_client_event AS c
                  JOIN meeting_event AS e ON e.event_id=c.event_id
                 WHERE c.meeting_id=? ORDER BY c.client_sequence
                """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    TranscriptUpsert item = RealtimeTranscriptEventMapper.fromStored(
                            JdbcMeetingEventLogStore.readEvent(rows)).getTranscriptUpsert();
                    latest.put(item.getSegmentId(), item);
                }
            }
        }
        if (latest.isEmpty() || latest.values().stream().anyMatch(item -> !item.getFinal())) {
            return null;
        }
        List<TranscriptUpsert> ordered = new ArrayList<>(latest.values());
        ordered.sort(Comparator.comparingLong(TranscriptUpsert::getStartMs)
                .thenComparing(TranscriptUpsert::getSegmentId));
        String language = ordered.getFirst().getLanguage();
        if (ordered.stream().anyMatch(item -> !language.equals(item.getLanguage()))) {
            throw new IllegalStateException("mixed source languages are not supported in P4");
        }
        String text = String.join("\n", ordered.stream().map(TranscriptUpsert::getText).toList());
        if (text.isBlank()) return null;
        long durationMs = ordered.stream().mapToLong(TranscriptUpsert::getEndMs)
                .max().orElse(0);
        return new Projection(text, language, durationMs, ordered.size());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record Projection(String text, String language, long durationMs, int segmentCount) { }
}
