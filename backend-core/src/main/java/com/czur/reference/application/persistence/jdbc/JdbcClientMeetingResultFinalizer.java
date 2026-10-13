package com.czur.reference.application.persistence.jdbc;

import com.czur.aimeeting.contract.realtime.v1.TranscriptUpsert;
import com.czur.aimeeting.contract.realtime.v1.TranslationUpsert;
import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.ClientMeetingInputPort.Reason;
import com.czur.reference.application.port.ClientMeetingResultFinalizer;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageUnit;
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
import com.czur.reference.domain.meeting.MeetingIdPolicy;
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
import java.time.Clock;
import java.time.Duration;
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
    private final JdbcDatabaseEngine engine;
    private final FinalizationReceiptGenerator receipts;
    private final Clock clock;
    private final ArtifactStore artifactStore;
    private final EntitlementProvider entitlementProvider;

    public JdbcClientMeetingResultFinalizer(DataSource dataSource,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore) {
        this(dataSource, receipts, clock, artifactStore, null);
    }

    /**
     * 为客户端会议装配实际采音用量结算。
     *
     * <p>Provider与Create／Worker使用同一实例。已有客户端提交冻结值时，在结果
     * READY前提交同场累积用量；重复Stop也读取原值／版本／幂等材料。旧没有冻结值
     * 的调用保留显示时长，不从墙钟猜计费用量。旧构造器仅保留这些兼容调用。</p>
     */
    public JdbcClientMeetingResultFinalizer(DataSource dataSource,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider) {
        this(dataSource, JdbcDatabaseEngine.POSTGRESQL, receipts, clock, artifactStore,
                entitlementProvider);
    }

    /**
     * 使用显式引擎及唯一池完成原客户端 Stop 收口。
     * Provider 必须与 Create 共用；结果写入仍在同一连接内按原聚合前进到 READY。
     * 本对象不关闭 Context、存储或部署方 Provider，也不装配旧音频 Worker。
     */
    public JdbcClientMeetingResultFinalizer(JdbcDatabaseContext database,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider) {
        this(database.dataSource(), database.engine(), receipts, clock, artifactStore,
                entitlementProvider);
    }

    private JdbcClientMeetingResultFinalizer(DataSource dataSource, JdbcDatabaseEngine engine,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.clock = Objects.requireNonNull(clock, "clock");
        // 无 Store 的旧装配仍可服务旧音频会议；客户端会议必须由完整进程装配提供 Store。
        this.artifactStore = artifactStore;
        this.entitlementProvider = entitlementProvider;
    }

    /**
     * 读取本场已保存的最终修订，供部署方在正常 Stop 前发布同场资源。
     *
     * <p>调用方先用自身认证入口取得可信 context，并传入唯一 Assembly 已持有的
     * DataSource；无需为读取构造 Finalizer、receipt 或 Clock。一次只读
     * REPEATABLE_READ 事务先按会议原始范围校验归属，再核对客户端启动标记、
     * 已冻结采音用量和首次 Create。最终文字直接复用本类原有 project，
     * 不创建另一套修订／顺序规则，也不等待 Stop 后才生成的 Artifact。</p>
     *
     * <p>正常发布在 STARTING/RUNNING 读取；STOPPING/READY 同场也可读取以便核对。
     * 返回的 Ready 只说明这些持久事实已读到，不表示结算或资源发布成功；它不调用
     * Provider、不写 Artifact/Manifest/READY，也不提交数据库事务。调用方应在
     * 模型尾事件及采音用量均保存后调用，已返回的不可变快照不会随后续事件改变。
     * 无最终文字返回真实空列表；尚未冻结用量返回 INVALID_STATE，不能冒充实测 0。</p>
     *
     * @param dataSource 运行时唯一会议事实源，不在此入口创建连接池
     * @param context 上游已认证及检查所需权限的服务端上下文
     * @param meetingId 本场客户端输入会议 ID
     * @return 不可变标准事件与首次建会参数，或复用现有输入拒绝原因
     */
    public static SnapshotResult readFinalSnapshot(DataSource dataSource,
            MeetingCommandContext context, String meetingId) {
        return readFinalSnapshot(dataSource, JdbcDatabaseEngine.POSTGRESQL, context, meetingId);
    }

    /**
     * 使用显式公共连接上下文读取最终快照；不构造 Finalizer，不结算或写入结果。
     * 原 DataSource 入口仍按 PG 解释，部署方切换引擎时必须使用本重载。
     *
     * @param database 已启动的唯一会议连接上下文，连接由本次只读调用释放
     * @param context 已由上游核验归属及权限的上下文
     * @param meetingId 本场客户端会议标识
     * @return 原最终修订投影与冻结实际用量；不保证后续发布或 READY 已完成
     */
    public static SnapshotResult readFinalSnapshot(JdbcDatabaseContext database,
            MeetingCommandContext context, String meetingId) {
        Objects.requireNonNull(database, "database");
        return readFinalSnapshot(database.dataSource(), database.engine(), context, meetingId);
    }

    private static SnapshotResult readFinalSnapshot(DataSource dataSource,
            JdbcDatabaseEngine engine, MeetingCommandContext context, String meetingId) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            SnapshotResult result = readFinalSnapshot(connection, engine, context, meetingId);
            // 只读事务也明确结束；结束失败不返回看似已经核验的事实。
            connection.rollback();
            if (result instanceof Ready ready) {
                LOGGER.log(Level.FINE,
                        "客户端最终快照已读取 segments={0} translations={1} durationMs={2}",
                        new Object[]{ready.transcripts().size(), ready.translations().size(),
                                ready.frozenAudioDurationMs()});
            } else {
                LOGGER.log(Level.FINE, "客户端最终快照拒绝 reason={0}",
                        ((Rejected) result).reason());
            }
            return result;
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "客户端最终快照读取暂未完成 reason={0}",
                    failure.getClass().getSimpleName());
            return new Rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static SnapshotResult readFinalSnapshot(Connection connection,
            JdbcDatabaseEngine engine, MeetingCommandContext context, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.deployment_scope, m.subject_scope, m.state, m.client_session_id,
                       m.profile_id, m.profile_revision, m.created_at,
                       i.started_at, i.audio_duration_ms,
                       i.usage_submitted_at, i.usage_expected_version
                  FROM %s m LEFT JOIN %s i USING (meeting_id)
                 WHERE m.meeting_id=?
                """.formatted(JdbcDatabaseTable.MEETING.physicalName(engine),
                        JdbcDatabaseTable.MEETING_CLIENT_INPUT.physicalName(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new Rejected(Reason.NOT_FOUND);
                // 先用原始归属字段执行公共 Guard，再解析仅所有者可见的状态／事实。
                if (MeetingAccessGuard.evaluate(context, JdbcValues.text(rows, "deployment_scope"),
                        JdbcValues.text(rows, "subject_scope")) != MeetingAccessGuard.Decision.ALLOWED) {
                    return new Rejected(Reason.ACCESS_DENIED);
                }
                if (JdbcValues.instant(rows, "started_at") == null) {
                    return new Rejected(Reason.INPUT_SOURCE_CONFLICT);
                }
                MeetingState state = MeetingState.valueOf(JdbcValues.text(rows, "state"));
                if (state != MeetingState.STARTING && state != MeetingState.RUNNING
                        && state != MeetingState.STOPPING && state != MeetingState.READY) {
                    return new Rejected(Reason.INVALID_STATE);
                }
                Long duration = rows.getObject("audio_duration_ms", Long.class);
                if (duration == null) return new Rejected(Reason.INVALID_STATE);
                Long usageVersion = rows.getObject("usage_expected_version", Long.class);
                if (duration < 0 || JdbcValues.instant(rows, "usage_submitted_at") == null
                        || usageVersion == null || usageVersion < 0) {
                    return new Rejected(Reason.STORAGE_UNAVAILABLE);
                }
                // 复用既有 Create 解码／摘要／scope-key／响应一致性门，不重写正文 SQL。
                var first = new JdbcCreateMeetingFactStore(connection, engine)
                        .findByMeetingIdReadOnly(meetingId).orElse(null);
                if (first == null) return new Rejected(Reason.STORAGE_UNAVAILABLE);
                var request = first.request().requestSnapshot();
                var response = first.response();
                Instant createdAt = JdbcValues.instant(rows, "created_at");
                if (!first.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                        context.deploymentScope(), context.identity().subject().subjectScope()))
                        || !meetingId.equals(response.meetingId())
                        || !request.clientSessionId().equals(JdbcValues.text(rows, "client_session_id"))
                        || !request.profile().id().equals(rows.getString("profile_id"))
                        || request.profile().revision() != rows.getInt("profile_revision")
                        || !response.acceptedProfile().equals(request.profile())
                        || !response.acceptedAudioProfile().equals(request.audioProfile())
                        || createdAt == null || !first.createdAt().equals(createdAt)) {
                    return new Rejected(Reason.STORAGE_UNAVAILABLE);
                }
                Projection projection = project(connection, engine, meetingId);
                return new Ready(projection.transcripts(), projection.translations(),
                        projection.language(), duration, request.artifactPolicy().retainAudio(),
                        request.languages().source(), request.title(), projection.summary());
            }
        }
    }

    /** 仅供进程内插件消费的只读结果；不新增公共 HTTP 或持久化协议。 */
    public sealed interface SnapshotResult permits Ready, Rejected { }

    /**
     * 已读取的不可变最终标准事件和首次 Create 参数。
     *
     * <p>transcripts/ translations 保留原投影的最终修订及顺序，Protobuf 消息自身
     * 不可变，列表在构造时复制。language 是最终原文的聚合语种（无最终原文为 und，
     * 混合为 mul）；sourceLanguage 是首次 Create 的源语。frozenAudioDurationMs
     * 始终为已提交的实测采音值，0 有明确含义；retainAudioRequested/title 也仅来自
     * 首次 Create，title 可为空，不从总结、全局配置或字幕正文补值。summary 是同一投影
     * 读取的已提交标准结构化纪要，未生成时为 null。部署方可以据此做旧结果格式适配，
     * 无需重复读取提交表、调用模型或等待 Stop 后的产物；快照本身不发布结果。</p>
     */
    public record Ready(List<TranscriptUpsert> transcripts, List<TranslationUpsert> translations,
            String language, long frozenAudioDurationMs, boolean retainAudioRequested,
            String sourceLanguage, String title, ObjectNode summary) implements SnapshotResult {
        public Ready {
            transcripts = List.copyOf(transcripts);
            translations = List.copyOf(translations);
            summary = summary == null ? null : summary.deepCopy();
        }

        /** 原仅字幕调用保持源兼容；此构造器明确没有已提交的纪要事实。 */
        public Ready(List<TranscriptUpsert> transcripts, List<TranslationUpsert> translations,
                String language, long frozenAudioDurationMs, boolean retainAudioRequested,
                String sourceLanguage, String title) {
            this(transcripts, translations, language, frozenAudioDurationMs, retainAudioRequested,
                    sourceLanguage, title, null);
        }

        /** JSON 树可变；返回本次独立副本，不能通过旧格式适配改变其他消费者的快照。 */
        @Override public ObjectNode summary() { return summary == null ? null : summary.deepCopy(); }
    }

    /** 读取未完成或当前事实不适用；原因沿用客户端输入端口，不携带身份／正文。 */
    public record Rejected(Reason reason) implements SnapshotResult {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    @Override
    public boolean finalizeIfClient(String meetingId) {
        // 先释放读取冻结事实的连接，再调用Provider，避免结果事务占用连接等待第二个池位。
        // 本入口每次均经过账本，包括下方resultExists早返的正常Stop重放。
        Long capturedDurationMs = commitClientUsage(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcMeetingRepository.VersionedMeeting locked =
                        JdbcMeetingRepository.findVersionedForUpdate(connection, engine, meetingId)
                                .orElse(null);
                Instant startedAt = locked == null ? null : clientStartedAt(connection, meetingId);
                if (startedAt == null) {
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
                Projection projection = project(connection, engine, meetingId);
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
                        new JdbcArtifactReadyMetadataStore(connection, engine).findByMeetingId(meetingId)
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
                        startedAt, endedAt,
                        capturedDurationMs == null
                                ? durationMs(startedAt, endedAt, projection.durationMs())
                                : capturedDurationMs,
                        new MeetingManifest.Languages(projection.language(),
                                projection.targetLanguages()),
                        artifactRefs, List.of());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO %s
                            (meeting_id, artifact_id, manifest_json)
                        VALUES (?, ?, ?)
                        """.formatted(table(JdbcDatabaseTable.MEETING_CLIENT_RESULT)))) {
                    JdbcValues.setText(statement, 1, engine, meetingId);
                    // 该兼容列只指向字幕；仅译文或零产物时留空，双产物以 Manifest 为准。
                    JdbcValues.setText(statement, 2, engine, artifactId);
                    statement.setString(3, MeetingManifestJson.encode(manifest));
                    statement.executeUpdate();
                }

                // 同一事务内按领域状态机依次前进；外部只会看到最终 READY。
                MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
                aggregate.apply(MeetingEvent.INPUT_ACCEPTED, endedAt);
                JdbcMeetingRepository.updateOwnedExisting(connection, engine, locked,
                        aggregate.snapshot());
                var finalizing = new JdbcMeetingRepository.VersionedMeeting(
                        aggregate.snapshot(), locked.aggregateVersion() + 1);
                aggregate.apply(MeetingEvent.READY, endedAt);
                JdbcMeetingRepository.updateOwnedExisting(connection, engine, finalizing,
                        aggregate.snapshot());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE %s SET input_state='INPUT_ACCEPTED',
                            finalization_receipt_id=?, manifest_state='READY'
                        WHERE meeting_id=? AND state='READY'
                        """.formatted(table(JdbcDatabaseTable.MEETING)))) {
                    JdbcValues.setText(statement, 1, engine, receipts.newReceipt());
                    JdbcValues.setText(statement, 2, engine, meetingId);
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

    /**
     * 只从已持久的客户端输入事实恢复Commit，不制造durable_input_fact。
     *
     * <p>首次提交保存的expectedVersion不会随Reservation演进而变化。幂等键和摘要
     * 只依赖同场稳定字段，Provider账本负责首次提交／重放。失败抛回原Stop编排，
     * 维持已提交停止意图和待收口状态；本类不会在未结算时发布READY。</p>
     */
    private Long commitClientUsage(String meetingId) {
        FrozenClientUsage usage = null;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.deployment_scope, m.subject_scope, m.usage_reservation_id,
                            i.audio_duration_ms, i.usage_submitted_at, i.usage_expected_version
                     FROM %s m JOIN %s i USING (meeting_id)
                     WHERE m.meeting_id=? AND m.state IN ('STOPPING', 'READY')
                     """.formatted(table(JdbcDatabaseTable.MEETING),
                             table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getObject("audio_duration_ms", Long.class) != null) {
                    usage = new FrozenClientUsage(JdbcValues.text(rows, "deployment_scope"),
                            JdbcValues.text(rows, "subject_scope"), JdbcValues.text(rows, "usage_reservation_id"),
                            rows.getLong("audio_duration_ms"),
                            JdbcValues.instant(rows, "usage_submitted_at"),
                            rows.getLong("usage_expected_version"));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("client usage database unavailable");
        }
        if (usage == null) return null; // 未提供用量，不把未知值伪装成实测0。
        if (entitlementProvider == null) {
            throw new IllegalStateException("client usage settlement is not configured");
        }
        String identity = sha256(("client-input-usage/v1\u0000" + usage.deploymentScope()
                + "\u0000" + usage.subjectScope() + "\u0000" + meetingId
                + "\u0000" + usage.reservationId() + "\u0000" + usage.expectedVersion()
                + "\u0000" + usage.audioDurationMs() + "\u0000" + usage.submittedAt())
                .getBytes(StandardCharsets.UTF_8));
        var request = new CommitUsageRequest(usage.deploymentScope(), usage.subjectScope(),
                UsageOperationScopes.COMMIT,
                "client-usage-" + sha256(meetingId.getBytes(StandardCharsets.UTF_8)),
                identity, usage.reservationId(), usage.expectedVersion(),
                new UsageActual(UsageUnit.MEETING_DURATION_MS, usage.audioDurationMs()));
        var result = entitlementProvider.commitUsage(request);
        if (!result.isSuccess()) {
            LOGGER.log(Level.WARNING, "客户端采音用量尚未结算，结果保持待收口");
            throw new IllegalStateException("client usage settlement unavailable");
        }
        LOGGER.log(Level.FINE, "客户端采音用量已确认结算 durationMs={0}", usage.audioDurationMs());
        return usage.audioDurationMs();
    }

    private record FrozenClientUsage(String deploymentScope, String subjectScope,
            String reservationId, long audioDurationMs, Instant submittedAt, long expectedVersion) { }

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
                new JdbcArtifactReadyMetadataStore(connection, engine).persistReady(descriptor);
        if (persisted.status() == ArtifactReadyMetadataStore.PersistStatus.CONFLICT) {
            throw new IllegalStateException("client result artifact metadata conflict");
        }
        return new MeetingManifest.ArtifactRef(artifactId, type, "application/json",
                language, 1, content.length, checksum, MeetingManifest.ArtifactStatus.READY,
                persisted.metadata().recordedAt(), null, null);
    }

    private Instant clientStartedAt(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT started_at FROM " + table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)
                        + " WHERE meeting_id=?" + currentRead())) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? JdbcValues.instant(rows, "started_at") : null;
            }
        }
    }

    /** 会议时长取实际客户端起止，且不能短于最终字幕的媒体时间轴。 */
    static long durationMs(Instant startedAt, Instant endedAt, long transcriptEndMs) {
        return Math.max(transcriptEndMs,
                Math.max(0, Duration.between(startedAt, endedAt).toMillis()));
    }

    private boolean resultExists(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table(JdbcDatabaseTable.MEETING_CLIENT_RESULT)
                        + " WHERE meeting_id=?" + currentRead())) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private String table(JdbcDatabaseTable table) { return table.physicalName(engine); }

    /** 决定性读均在 Meeting 行锁之后；只读最终快照仍用原普通 SELECT。 */
    private String currentRead() { return engine == JdbcDatabaseEngine.MYSQL ? " FOR UPDATE" : ""; }

    /** 两引擎共享既有最终修订规则；只读快照不使用锁定读取。 */
    private static Projection project(Connection connection, JdbcDatabaseEngine engine, String meetingId)
            throws SQLException {
        Map<String, TranscriptUpsert> latest = new HashMap<>();
        Map<String, TranslationRevision> latestTranslations = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.meeting_id, e.event_sequence, e.event_id, e.event_type,
                       c.client_sequence,
                       e.canonical_payload, e.occurred_at, e.spec_version, e.stream_id
                  FROM %s AS c
                  JOIN %s AS e ON e.event_id=c.event_id
                 WHERE c.meeting_id=? ORDER BY c.client_sequence
                """.formatted(JdbcDatabaseTable.MEETING_CLIENT_EVENT.physicalName(engine),
                        JdbcDatabaseTable.MEETING_EVENT.physicalName(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
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
                "SELECT summary_json FROM " + JdbcDatabaseTable.MEETING_CLIENT_SUMMARY.physicalName(engine)
                        + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
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
