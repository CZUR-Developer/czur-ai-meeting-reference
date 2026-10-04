package com.czur.reference.application.persistence.jdbc;

import com.czur.aimeeting.contract.realtime.v1.TranscriptUpsert;
import com.czur.aimeeting.contract.realtime.v1.TranslationUpsert;
import com.czur.reference.application.port.ClientMeetingResultFinalizer;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.czur.reference.protocol.realtime.RealtimeTranslationEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 将 App 已保存的最终字幕、译文与结构化纪要写入可替换存储，再冻结为参考结果。
 *
 * <p>Stop 命令先独立提交。此实现随后锁定同一会议，读取客户端标准事件，按
 * 原文 segmentId、译文 translationId 各取最后修订，并读取 Stop 前提交的唯一纪要，
 * 先通过 ArtifactStore 发布不可变正文，再在一个数据库事务
 * 中保存 READY metadata、Manifest 和会议终态。Stop 后客户端不能继续 append，
 * 因此只冻结最终修订；未完成片段不被当作最终文字。若没有任何最终字幕、译文或纪要，直接
 * 冻结无 Artifact 的 READY Manifest，不制造空字幕事件或空的最终字幕产物。
 * 本类只处理客户端事件链，不改变旧音频 Worker 的收口流程。</p>
 */
public final class JdbcClientMeetingResultFinalizer implements ClientMeetingResultFinalizer {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcClientMeetingResultFinalizer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;
    private final FinalizationReceiptGenerator receipts;
    private final Clock clock;
    private final ArtifactStore artifactStore;

    public JdbcClientMeetingResultFinalizer(DataSource dataSource,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.clock = Objects.requireNonNull(clock, "clock");
        // 无 Store 的旧装配仍可服务旧音频会议；客户端会议必须由完整进程装配提供 Store。
        this.artifactStore = artifactStore;
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
                if (projection.hasArtifacts() && artifactStore == null) {
                    throw new IllegalStateException("client result artifact store is unavailable");
                }
                Instant endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (endedAt.isBefore(locked.snapshot().updatedAt())) {
                    endedAt = locked.snapshot().updatedAt();
                }
                String artifactId = null;
                List<MeetingManifest.ArtifactRef> artifactRefs = new ArrayList<>();
                if (projection.segmentCount() > 0) {
                    artifactId = "art_client_" + sha256(meetingId.getBytes(
                            StandardCharsets.UTF_8));
                    ObjectNode artifact = JSON.createObjectNode();
                    artifact.put("schemaVersion", "client-transcript/v1");
                    artifact.put("artifactType", "transcript.final");
                    artifact.put("language", projection.language());
                    artifact.put("text", projection.text());
                    ArrayNode segments = artifact.putArray("segments");
                    for (TranscriptUpsert segment : projection.transcripts()) {
                        ObjectNode item = segments.addObject();
                        item.put("segmentId", segment.getSegmentId());
                        item.put("startMs", segment.getStartMs());
                        item.put("endMs", segment.getEndMs());
                        item.put("language", segment.getLanguage());
                        item.put("text", segment.getText());
                        if (segment.hasSpeaker()) {
                            item.put("sessionSpeakerId",
                                    segment.getSpeaker().getSessionSpeakerId());
                        }
                    }
                    artifactRefs.add(publishReady(connection, artifactId, meetingId,
                            "transcript.final", projection.language(), artifact));
                }
                if (!projection.translations().isEmpty()) {
                    String translationId = "art_client_translation_" + sha256(
                            meetingId.getBytes(StandardCharsets.UTF_8));
                    ObjectNode artifact = JSON.createObjectNode();
                    artifact.put("schemaVersion", "client-translation/v1");
                    artifact.put("artifactType", "translation.final");
                    artifact.put("language", projection.translationLanguage());
                    ArrayNode translations = artifact.putArray("translations");
                    for (TranslationUpsert translation : projection.translations()) {
                        ObjectNode item = translations.addObject();
                        item.put("translationId", translation.getTranslationId());
                        item.put("sourceSegmentId", translation.getSourceSegmentId());
                        item.put("sourceLanguage", translation.getSourceLanguage());
                        item.put("targetLanguage", translation.getTargetLanguage());
                        item.put("text", translation.getText());
                    }
                    artifactRefs.add(publishReady(connection, translationId, meetingId,
                            "translation.final", projection.translationLanguage(), artifact));
                }
                if (projection.summary() != null) {
                    ObjectNode artifact = JSON.createObjectNode();
                    artifact.put("schemaVersion", "client-summary/v1");
                    artifact.put("artifactType", "summary.structured");
                    artifact.setAll(projection.summary());
                    String summaryId = "art_client_summary_" + sha256(
                            meetingId.getBytes(StandardCharsets.UTF_8));
                    artifactRefs.add(publishReady(connection, summaryId, meetingId,
                            "summary.structured", projection.summary().path("language").asText(),
                            artifact));
                }
                List<ArtifactReadyMetadataStore.ReadyMetadata> captures =
                        new JdbcArtifactReadyMetadataStore(connection).findByMeetingId(meetingId)
                                .stream().filter(item -> "presentation.capture".equals(
                                        item.artifact().intent().spec().artifactType()))
                                .sorted(Comparator.comparingInt(item ->
                                        item.artifact().intent().spec().revision()))
                                .toList();
                int nextCapture = 1;
                for (var capture : captures) {
                    WriteIntentSpec spec = capture.artifact().intent().spec();
                    if (spec.revision() != nextCapture++
                            || !"application/json".equals(spec.mediaType())
                            || spec.language() != null) {
                        throw new IllegalStateException("presentation capture order is invalid");
                    }
                    artifactRefs.add(new MeetingManifest.ArtifactRef(
                            spec.artifactId(), spec.artifactType(), spec.mediaType(), null,
                            spec.revision(), capture.artifact().content().size(),
                            capture.artifact().content().checksum(),
                            MeetingManifest.ArtifactStatus.READY, capture.recordedAt(),
                            null, null));
                }
                MeetingManifest manifest = new MeetingManifest(
                        "0.1", meetingId, 1,
                        new MeetingManifest.Profile(locked.snapshot().profileId(),
                                locked.snapshot().profileRevision()),
                        projection.summary() == null ? null
                                : projection.summary().path("title").asText(),
                        com.czur.reference.application.port.ManifestStatus.READY,
                        locked.snapshot().createdAt(), endedAt, projection.durationMs(),
                        new MeetingManifest.Languages(projection.language(),
                                projection.targetLanguages()),
                        artifactRefs, List.of());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO meeting_client_result
                            (meeting_id, artifact_id, manifest_json)
                        VALUES (?, ?, ?)
                        """)) {
                    statement.setString(1, meetingId);
                    // 该兼容列只指向字幕；仅译文或零产物时留空，双产物以 Manifest 为准。
                    statement.setString(2, artifactId);
                    statement.setString(3, MeetingManifestJson.encode(manifest));
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
                LOGGER.log(Level.INFO,
                        "客户端结果已生成 segments={0} translations={1} summary={2}",
                        new Object[]{projection.segmentCount(), projection.translations().size(),
                                projection.summary() != null});
                return true;
            } catch (SQLException | ArtifactStoreException | RuntimeException failure) {
                connection.rollback();
                LOGGER.log(Level.WARNING, "客户端最终结果生成暂未完成 reason={0}",
                        failure.getClass().getSimpleName());
                throw new IllegalStateException("client result finalization unavailable");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("client result database unavailable");
        }
    }

    /** 稳定 artifactId 使 Stop 重试可以复用已发布内容，不依赖数据库与存储的跨资源事务。 */
    private ArtifactDescriptor publish(WriteIntentSpec spec, byte[] content)
            throws ArtifactStoreException {
        var prepared = artifactStore.createWriteIntent(spec);
        if (prepared.status() == ArtifactStore.PrepareStatus.CONFLICT) {
            throw new IllegalStateException("client result artifact intent conflict");
        }
        WriteIntent intent = prepared.intent();
        ContentClaim claim = new ContentClaim(content.length, spec.expectedChecksum());
        artifactStore.writeContent(intent, new ByteArrayInputStream(content));
        return artifactStore.completeWrite(intent, claim).artifact();
    }

    private MeetingManifest.ArtifactRef publishReady(Connection connection, String artifactId,
            String meetingId, String type, String language, ObjectNode body)
            throws ArtifactStoreException, SQLException {
        byte[] content = body.toString().getBytes(StandardCharsets.UTF_8);
        String checksum = "sha256:" + sha256(content);
        WriteIntentSpec spec = new WriteIntentSpec(artifactId, meetingId, type,
                "application/json", language, 1, content.length, checksum);
        ArtifactDescriptor descriptor = publish(spec, content);
        ArtifactReadyMetadataStore.PersistResult persisted =
                new JdbcArtifactReadyMetadataStore(connection).persistReady(descriptor);
        if (persisted.status() == ArtifactReadyMetadataStore.PersistStatus.CONFLICT) {
            throw new IllegalStateException("client result artifact metadata conflict");
        }
        return new MeetingManifest.ArtifactRef(artifactId, type, "application/json",
                language, 1, content.length, checksum, MeetingManifest.ArtifactStatus.READY,
                persisted.metadata().recordedAt(), null, null);
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
        Map<String, TranslationRevision> latestTranslations = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.meeting_id, e.event_sequence, e.event_id, e.event_type,
                       c.client_sequence,
                       e.canonical_payload, e.occurred_at, e.spec_version, e.stream_id
                  FROM meeting_client_event AS c
                  JOIN meeting_event AS e ON e.event_id=c.event_id
                 WHERE c.meeting_id=? ORDER BY c.client_sequence
                """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    var event = JdbcMeetingEventLogStore.readEvent(rows);
                    if (RealtimeTranscriptEventMapper.EVENT_TYPE.equals(event.eventType())) {
                        TranscriptUpsert item = RealtimeTranscriptEventMapper.fromStored(event)
                                .getTranscriptUpsert();
                        latest.put(item.getSegmentId(), item);
                    } else if (RealtimeTranslationEventMapper.EVENT_TYPE.equals(event.eventType())) {
                        TranslationUpsert item = RealtimeTranslationEventMapper.fromStored(event)
                                .getTranslationUpsert();
                        latestTranslations.put(item.getTranslationId(),
                                new TranslationRevision(item, rows.getLong("client_sequence")));
                    } else {
                        throw new IllegalStateException("unsupported client result event type");
                    }
                }
            }
        }
        // Stop 后 append 已关闭，等待 partial 变成 final 只会永久停在 STOPPING。
        // 未完成片段不进入结果；即使一场会议仅有 partial，也能以无字幕结果收口。
        List<TranscriptUpsert> ordered = new ArrayList<>(latest.values().stream()
                .filter(TranscriptUpsert::getFinal).toList());
        ordered.sort(Comparator.comparingLong(TranscriptUpsert::getStartMs)
                .thenComparing(TranscriptUpsert::getSegmentId));
        // und 表示无最终原文；混合语种使用标准 mul，同时在正文保留逐片语言。
        String language = ordered.isEmpty() ? "und" : ordered.getFirst().getLanguage();
        if (!ordered.isEmpty() && ordered.stream().anyMatch(item ->
                !ordered.getFirst().getLanguage().equals(item.getLanguage()))) language = "mul";
        String text = String.join("\n", ordered.stream().map(TranscriptUpsert::getText).toList());
        if (text.isBlank()) {
            ordered.clear();
            language = "und";
        }
        long durationMs = ordered.stream().mapToLong(TranscriptUpsert::getEndMs)
                .max().orElse(0);
        Map<String, Integer> sourceOrder = new HashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            sourceOrder.put(ordered.get(index).getSegmentId(), index);
        }
        List<TranslationRevision> translations = new ArrayList<>(latestTranslations.values()
                .stream().filter(item -> item.value().getFinal()).toList());
        translations.sort(Comparator
                .comparingInt((TranslationRevision item) -> sourceOrder.getOrDefault(
                        item.value().getSourceSegmentId(), Integer.MAX_VALUE))
                .thenComparingLong(TranslationRevision::sequence)
                .thenComparing(item -> item.value().getTranslationId()));
        List<TranslationUpsert> finalTranslations = translations.stream()
                .map(TranslationRevision::value).toList();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        finalTranslations.forEach(item -> targets.add(item.getTargetLanguage()));
        String translationLanguage = targets.size() == 1 ? targets.iterator().next() : "mul";
        ObjectNode summary = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT summary_json FROM meeting_client_summary WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    try {
                        JsonNode parsed = JSON.readTree(rows.getString(1));
                        if (!(parsed instanceof ObjectNode object)) {
                            throw new IllegalStateException("stored summary is invalid");
                        }
                        summary = object;
                    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                        throw new IllegalStateException("stored summary is invalid", invalid);
                    }
                }
            }
        }
        return new Projection(text, language, durationMs, ordered, finalTranslations,
                List.copyOf(targets), translationLanguage, summary);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record TranslationRevision(TranslationUpsert value, long sequence) { }

    private record Projection(String text, String language, long durationMs,
            List<TranscriptUpsert> transcripts, List<TranslationUpsert> translations,
            List<String> targetLanguages, String translationLanguage, ObjectNode summary) {
        int segmentCount() { return transcripts.size(); }
        boolean hasArtifacts() {
            return !transcripts.isEmpty() || !translations.isEmpty() || summary != null;
        }
    }
}
