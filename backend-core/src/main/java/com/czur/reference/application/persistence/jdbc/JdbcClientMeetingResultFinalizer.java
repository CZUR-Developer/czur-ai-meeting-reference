package com.czur.reference.application.persistence.jdbc;

import com.czur.aimeeting.contract.realtime.v1.TranscriptUpsert;
import com.czur.aimeeting.contract.realtime.v1.TranslationUpsert;
import com.czur.reference.application.artifact.MeetingBodyArtifactBudget;
import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.AbandonedMeetingFinalizer;
import com.czur.reference.application.port.ClientMeetingInputPort.Reason;
import com.czur.reference.application.port.ClientMeetingResultFinalizer;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.MeetingSpeakers;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.SpeakerSample;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.AudioWindow;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityDecision;
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
public final class JdbcClientMeetingResultFinalizer
        implements ClientMeetingResultFinalizer, AbandonedMeetingFinalizer {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcClientMeetingResultFinalizer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;
    private final FinalizationReceiptGenerator receipts;
    private final Clock clock;
    private final ArtifactStore artifactStore;
    private final EntitlementProvider entitlementProvider;
    private final SpeakerIdentityProvider speakerIdentity;
    private final long maxClientArtifactBytes;

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
        this(dataSource, JdbcDatabaseEngine.MYSQL, receipts, clock, artifactStore,
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
                entitlementProvider, null, MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /**
     * 显式装配可选会后人物插件；旧构造器保持关闭。识别在会议事务及行锁之外
     * 执行，结果仍与原文、录音一起纳入唯一客户端 Manifest。
     */
    public JdbcClientMeetingResultFinalizer(JdbcDatabaseContext database,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider, SpeakerIdentityProvider speakerIdentity) {
        this(database.dataSource(), database.engine(), receipts, clock, artifactStore,
                entitlementProvider, speakerIdentity,
                MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /**
     * 显式注入与读取端口、HTTP 响应相同的会议正文预算；旧构造器沿用兼容下限。
     */
    public JdbcClientMeetingResultFinalizer(JdbcDatabaseContext database,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider, SpeakerIdentityProvider speakerIdentity,
            long maxClientArtifactBytes) {
        this(database.dataSource(), database.engine(), receipts, clock, artifactStore,
                entitlementProvider, speakerIdentity, maxClientArtifactBytes);
    }

    private JdbcClientMeetingResultFinalizer(DataSource dataSource, JdbcDatabaseEngine engine,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider) {
        this(dataSource, engine, receipts, clock, artifactStore, entitlementProvider, null,
                MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    private JdbcClientMeetingResultFinalizer(DataSource dataSource, JdbcDatabaseEngine engine,
            FinalizationReceiptGenerator receipts, Clock clock, ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider, SpeakerIdentityProvider speakerIdentity,
            long maxClientArtifactBytes) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.clock = Objects.requireNonNull(clock, "clock");
        // 无 Store 的旧装配仍可服务旧音频会议；客户端会议必须由完整进程装配提供 Store。
        this.artifactStore = artifactStore;
        this.entitlementProvider = entitlementProvider;
        this.speakerIdentity = speakerIdentity;
        if (maxClientArtifactBytes <= 0) {
            throw new IllegalArgumentException("client result budget must be positive");
        }
        this.maxClientArtifactBytes = maxClientArtifactBytes;
    }

    /**
     * 读取本场已保存的最终修订，供部署方在正常 Stop 前发布同场资源。
     *
     * <p>调用方先用自身认证入口取得可信 context，并传入唯一 Assembly 已持有的
     * DataSource；无需为读取构造 Finalizer、receipt 或 Clock。一次只读
     * REPEATABLE_READ 事务先按会议原始范围校验归属，再核对客户端启动标记、
     * 已冻结采音用量和首次 Create。最终文字直接复用本类原有 project，截图描述符
     * 复用 Manifest 的同场 metadata 排序和连续 revision 校验；不读取截图正文，
     * 不创建另一套修订／顺序规则，也不等待 Stop 后才生成的最终产物。</p>
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
        return readFinalSnapshot(dataSource, JdbcDatabaseEngine.MYSQL, context, meetingId);
    }

    /**
     * 使用显式公共连接上下文读取最终快照；不构造 Finalizer，不结算或写入结果。
     * 原 DataSource 入口固定按 MySQL 解释，部署方切换引擎时必须使用本重载。
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
                // expectedVersion 可以是升级前/部分写入留下的空值；结算按预占归属
                // 和当前版本恢复，快照读取不能把它当成存储不可用而挡住可恢复会议。
                if (duration < 0 || JdbcValues.instant(rows, "usage_submitted_at") == null
                        || (usageVersion != null && usageVersion < 0)) {
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
                        request.languages().source(), request.title(), projection.summary(),
                        request.artifactPolicy().presentationCapture(),
                        presentationCaptures(connection, engine, meetingId).stream()
                                .map(ArtifactReadyMetadataStore.ReadyMetadata::artifact).toList(),
                        request.artifactPolicy().retainAudio()
                                ? audioOriginal(connection, engine, meetingId) : null);
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
     * 读取的已提交标准结构化纪要，未生成时为 null。presentationCaptureRequested 同样
     * 只取首次 Create；presentationCaptures 是已有 READY metadata 的不可变同场描述符，
     * 按从 1 连续递增的 revision 排序，仅包含引用而不载入 JPEG。部署方可以据此做旧结果
     * 格式适配，逐包经 ArtifactStore 读取，不调用模型或等待 Stop 后的最终产物；快照本身
     * 不发布结果。audioArtifact 是可空的同场 {@code audio.original} READY 描述符；
     * 只在首次 Create 请求保留录音且录音已登记时出现，不读取长音频正文。</p>
     */
    public record Ready(List<TranscriptUpsert> transcripts, List<TranslationUpsert> translations,
            String language, long frozenAudioDurationMs, boolean retainAudioRequested,
            String sourceLanguage, String title, ObjectNode summary,
            boolean presentationCaptureRequested,
            List<ArtifactDescriptor> presentationCaptures,
            ArtifactDescriptor audioArtifact) implements SnapshotResult {
        public Ready {
            transcripts = List.copyOf(transcripts);
            translations = List.copyOf(translations);
            summary = summary == null ? null : summary.deepCopy();
            presentationCaptures = List.copyOf(presentationCaptures);
        }

        /** 保留原十参构造器；旧调用方没有录音 descriptor 时明确传入 null。 */
        public Ready(List<TranscriptUpsert> transcripts, List<TranslationUpsert> translations,
                String language, long frozenAudioDurationMs, boolean retainAudioRequested,
                String sourceLanguage, String title, ObjectNode summary,
                boolean presentationCaptureRequested, List<ArtifactDescriptor> presentationCaptures) {
            this(transcripts, translations, language, frozenAudioDurationMs, retainAudioRequested,
                    sourceLanguage, title, summary, presentationCaptureRequested,
                    presentationCaptures, null);
        }

        /** 原纪要调用保持源兼容；未提供演示事实时不推断截图请求或引用。 */
        public Ready(List<TranscriptUpsert> transcripts, List<TranslationUpsert> translations,
                String language, long frozenAudioDurationMs, boolean retainAudioRequested,
                String sourceLanguage, String title, ObjectNode summary) {
            this(transcripts, translations, language, frozenAudioDurationMs, retainAudioRequested,
                    sourceLanguage, title, summary, false, List.of(), null);
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

    /**
     * 读取本场待办并按登记原因收口。
     *
     * <p>Stop 受理后同步收口失败时，待办由 Stop 事务保留；后台扫描再次调用本
     * 入口。{@code USAGE_TIMEOUT} 表示客户端已冻结采音用量但超时没发 Stop，
     * 会议在决定事务中已推进到 {@code STOPPING}，这里按冻结用量结算并按本场
     * 结果能力选择 READY／READY_WITH_WARNINGS；其余原因沿用正常 Stop 语义。
     * 没有待办时回落为普通客户端收口，兼容旧数据与直接调用。</p>
     */
    @Override
    public boolean finalizePendingClient(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        var pending = JdbcClientFinalizationPendingStore.find(dataSource, engine, meetingId);
        if (pending.isEmpty()) {
            return finalizeIfClient(meetingId);
        }
        return switch (pending.orElseThrow().reason()) {
            case STOP_ACCEPTED -> finalizeIfClient(meetingId);
            case USAGE_TIMEOUT -> finalizeUsageTimeout(meetingId);
        };
    }

    @Override
    public boolean finalizeIfClient(String meetingId) {
        // 已落弃场决定的会议只由扫描器收口；正常 Stop 不能把它改写成 READY，
        // 也不能抢在弃场结算前提交采音用量。待办由弃场链自然清除。
        if (abandonmentDecided(meetingId)) {
            LOGGER.log(Level.FINE, "弃场决定已存在，正常收尾让位");
            clearPendingQuietly(meetingId);
            return true;
        }
        // 先释放读取冻结事实的连接，再调用Provider，避免结果事务占用连接等待第二个池位。
        // 本入口每次均经过账本，包括下方resultExists早返的正常Stop重放。
        Long capturedDurationMs = commitClientUsage(meetingId, false);
        // 可选供应商网络和音频切片只在连接释放后运行，不能放进下面的会议行锁事务。
        ObjectNode speakerArtifact = identifySpeakers(meetingId);
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
                // 复核与早检之间可能刚落决定；同一事务内再查一次才允许写结果。
                if (JdbcMeetingAbandonmentStore.exists(connection, engine, meetingId)) {
                    JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return true;
                }
                if (resultExists(connection, meetingId)) {
                    // 上一次尝试已提交结果但待办未删（崩溃窗口）：只补删除，不重复发布。
                    JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return true;
                }
                if (locked.snapshot().state() != MeetingState.STOPPING) {
                    connection.rollback();
                    return false;
                }
                Instant endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (endedAt.isBefore(locked.snapshot().updatedAt())) {
                    endedAt = locked.snapshot().updatedAt();
                }
                writeClientResult(connection, locked, startedAt, endedAt, capturedDurationMs,
                        speakerArtifact, MeetingEvent.READY, ManifestStatus.READY, List.of());
                JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                connection.commit();
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
     * 按冻结采音用量收口“已提交用量但长期没有 Stop”的会议。
     *
     * <p>决策事务已经把会议推进到 {@code STOPPING} 并写入 {@code USAGE_TIMEOUT}
     * 待办；本方法只负责用同场冻结用量结算并发布最终结果。结算键与正常 Stop
     * 完全相同，Provider 账本保证只扣一次；结果写入与待办删除在同一事务内。
     * 终态按首次 Create 冻结的能力计划选择 READY／READY_WITH_WARNINGS，不套用
     * 弃场警告语义，也不做零结算。</p>
     *
     * @return true 表示已收口或由弃场链接管；false 表示冻结用量/输入事实尚不满足
     * @throws IllegalStateException 数据库、结算端口或产物存储暂时不可用
     */
    public boolean finalizeUsageTimeout(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        if (abandonmentDecided(meetingId)) {
            LOGGER.log(Level.FINE, "弃场决定已存在，冻结用量收尾让位");
            clearPendingQuietly(meetingId);
            return true;
        }
        Long capturedDurationMs = commitClientUsage(meetingId, true);
        if (capturedDurationMs == null) {
            // 没有冻结用量就不能假装零结算；保留待办供人工或下一轮核对。
            LOGGER.log(Level.WARNING, "冻结用量收尾缺少冻结事实，保留待办");
            return false;
        }
        ObjectNode speakerArtifact = identifySpeakers(meetingId);
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
                if (JdbcMeetingAbandonmentStore.exists(connection, engine, meetingId)) {
                    JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return true;
                }
                if (resultExists(connection, meetingId)) {
                    JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return true;
                }
                MeetingState state = locked.snapshot().state();
                if (state != MeetingState.STARTING && state != MeetingState.RUNNING
                        && state != MeetingState.STOPPING) {
                    // 其它收口链已把会议推进到终态：只清理待办，不重复发布。
                    JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return true;
                }
                Instant endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (endedAt.isBefore(locked.snapshot().updatedAt())) {
                    endedAt = locked.snapshot().updatedAt();
                }
                ResultTerminal terminal = resultTerminal(connection, meetingId);
                writeClientResult(connection, locked, startedAt, endedAt, capturedDurationMs,
                        speakerArtifact, terminal.event(), terminal.manifestStatus(),
                        terminal.warnings());
                JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                connection.commit();
                LOGGER.log(Level.INFO, "冻结用量超时会议已按正常收尾 durationMs={0}",
                        capturedDurationMs);
                return true;
            } catch (SQLException | ArtifactStoreException | RuntimeException failure) {
                connection.rollback();
                LOGGER.log(Level.WARNING, "冻结用量超时收尾暂未完成 reason={0}",
                        failure.getClass().getSimpleName());
                throw new IllegalStateException("usage timeout finalization unavailable");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("usage timeout result database unavailable");
        }
    }

    /**
     * 按已落库的弃场决定收尾：只写结果、Manifest 与终态。
     *
     * <p>结算已由扫描器在决定落库、连接释放之后完成；本方法不再调用
     * {@link EntitlementProvider}，因此不会在持有池连接时借第二个连接。决定中的
     * 开始时间、决定时间与结算时长都是冻结事实，重试不重算，重复执行幂等收敛。
     * 结果写入与决定删除在同一事务内完成；没有决定、未标记结算或目标不一致时
     * 返回 false，保留决定给下一轮。</p>
     *
     * <p>正常 Stop 的收尾入口会先检查决定并让位。若扫描器与正常 Stop 并发，
     * 两边都以会议行锁串行化：先拿到锁的一方决定结果，另一方看到决定或最终
     * 状态后不再重复写入。</p>
     *
     * @return true 表示本场已收口（含此前已收口）；false 表示决定不存在或尚不满足
     * @throws IllegalStateException 数据库或产物存储暂时不可用，由调用方下轮重试
     */
    @Override
    public boolean finalizeAbandoned(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcMeetingRepository.VersionedMeeting locked =
                        JdbcMeetingRepository.findVersionedForUpdate(connection, engine, meetingId)
                                .orElse(null);
                if (locked == null) {
                    connection.rollback();
                    return false;
                }
                JdbcMeetingAbandonmentStore.Decision decision =
                        JdbcMeetingAbandonmentStore.find(connection, engine, meetingId, true)
                                .orElse(null);
                if (decision == null
                        || decision.kind() != JdbcMeetingAbandonmentStore.Kind.ABANDONED
                        || decision.targetState() != MeetingState.READY_WITH_WARNINGS
                        || decision.settlement()
                                != JdbcMeetingAbandonmentStore.Settlement.SETTLED) {
                    connection.rollback();
                    return false;
                }
                if (resultExists(connection, meetingId)) {
                    // 已收口但决定未删（崩溃窗口）：只补删除，不重复发布产物。
                    JdbcMeetingAbandonmentStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return true;
                }
                MeetingState state = locked.snapshot().state();
                if (state != MeetingState.STARTING && state != MeetingState.RUNNING
                        && state != MeetingState.STOPPING) {
                    // 防御分支：其它路径已把会议推进到别的终态。决定已结算，结果不再
                    //由本入口改写；删除决定避免永久重扫，保留固定告警供人工核对。
                    LOGGER.log(Level.WARNING,
                            "弃场决定与会议状态不一致，清除决定 state={0}", state);
                    JdbcMeetingAbandonmentStore.delete(connection, engine, meetingId);
                    connection.commit();
                    return false;
                }
                // meeting_client_result 对 client_input 有外键；用决定冻结的开始时间补齐。
                ensureClientInput(connection, meetingId, decision.startedAt());
                Instant endedAt = decision.decidedAt().isBefore(locked.snapshot().updatedAt())
                        ? locked.snapshot().updatedAt() : decision.decidedAt();
                writeClientResult(connection, locked, decision.startedAt(), endedAt,
                        decision.settleDurationMs(), null, MeetingEvent.READY_WITH_WARNINGS,
                        ManifestStatus.READY_WITH_WARNINGS,
                        List.of(new MeetingManifest.Warning(
                                "meeting.abandoned", "meeting.lifecycle", "meeting.abandoned", null)));
                JdbcMeetingAbandonmentStore.delete(connection, engine, meetingId);
                connection.commit();
                LOGGER.log(Level.INFO, "弃场会议已收口 durationMs={0}",
                        decision.settleDurationMs());
                return true;
            } catch (SQLException | ArtifactStoreException | RuntimeException failure) {
                connection.rollback();
                LOGGER.log(Level.WARNING, "弃场会议收尾暂未完成 reason={0}",
                        failure.getClass().getSimpleName());
                throw new IllegalStateException("abandoned client result finalization unavailable");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("abandoned client result database unavailable");
        }
    }

    /**
     * 正常 Stop 与弃场共用的结果发布和终态前进。
     *
     * <p>调用方已锁定会议并检查本入口适用的前置状态；本方法在同一事务内写
     * Client Result 行并按领域状态机从当前状态前进到给定终态。正常 Stop 从
     * STOPPING 开始，弃场从 STARTING／RUNNING 开始并先补一次 STOP_REQUESTED，
     * 不另建一套状态转移。</p>
     */
    private void writeClientResult(Connection connection,
            JdbcMeetingRepository.VersionedMeeting locked, Instant startedAt, Instant endedAt,
            Long capturedDurationMs, ObjectNode speakerArtifact, MeetingEvent terminalEvent,
            ManifestStatus manifestStatus, List<MeetingManifest.Warning> warnings)
            throws SQLException, ArtifactStoreException {
        String meetingId = locked.snapshot().meetingId();
        if (terminalEvent != MeetingEvent.READY
                && terminalEvent != MeetingEvent.READY_WITH_WARNINGS) {
            throw new IllegalArgumentException("terminalEvent must be a ready event");
        }
        Projection projection = project(connection, engine, meetingId);
        if (projection.hasArtifacts() && artifactStore == null) {
            throw new IllegalStateException("client result artifact store is unavailable");
        }
        String artifactId = null;
        List<MeetingManifest.ArtifactRef> artifactRefs = new ArrayList<>();
        if (projection.segmentCount() > 0) {
            artifactId = "art_client_" + sha256(meetingId.getBytes(StandardCharsets.UTF_8));
            artifactRefs.add(publishReady(connection, artifactId, meetingId,
                    "transcript.final", projection.language(),
                    transcriptArtifactBody(projection)));
        }
        if (!projection.translations().isEmpty()) {
            String translationId = "art_client_translation_" + sha256(
                    meetingId.getBytes(StandardCharsets.UTF_8));
            artifactRefs.add(publishReady(connection, translationId, meetingId,
                    "translation.final", projection.translationLanguage(),
                    translationArtifactBody(projection)));
        }
        if (projection.summary() != null) {
            String summaryId = "art_client_summary_" + sha256(
                    meetingId.getBytes(StandardCharsets.UTF_8));
            artifactRefs.add(publishReady(connection, summaryId, meetingId,
                    "summary.structured", projection.summary().path("language").asText(),
                    summaryArtifactBody(projection)));
        }
        if (speakerArtifact != null) {
            String speakerId = "art_client_speaker_" + sha256(
                    meetingId.getBytes(StandardCharsets.UTF_8)) + "_1";
            artifactRefs.add(publishReady(connection, speakerId, meetingId,
                    "speaker.identity", null, speakerArtifact));
        }
        for (var capture : presentationCaptures(connection, engine, meetingId)) {
            WriteIntentSpec spec = capture.artifact().intent().spec();
            artifactRefs.add(new MeetingManifest.ArtifactRef(
                    spec.artifactId(), spec.artifactType(), spec.mediaType(), null,
                    spec.revision(), capture.artifact().content().size(),
                    capture.artifact().content().checksum(),
                    MeetingManifest.ArtifactStatus.READY, capture.recordedAt(),
                    null, null));
        }
        ArtifactDescriptor audioArtifact = retainAudioRequested(connection, engine, meetingId)
                ? audioOriginal(connection, engine, meetingId) : null;
        if (audioArtifact != null) {
            WriteIntentSpec spec = audioArtifact.intent().spec();
            artifactRefs.add(new MeetingManifest.ArtifactRef(
                    spec.artifactId(), spec.artifactType(), spec.mediaType(),
                    spec.language(), spec.revision(), audioArtifact.content().size(),
                    audioArtifact.content().checksum(), MeetingManifest.ArtifactStatus.READY,
                    audioReadyRecordedAt(connection, engine, meetingId, spec.artifactId()),
                    null, null));
        }
        MeetingManifest manifest = new MeetingManifest(
                "0.1", meetingId, 1,
                new MeetingManifest.Profile(locked.snapshot().profileId(),
                        locked.snapshot().profileRevision()),
                projection.summary() == null ? null
                        : projection.summary().path("title").asText(),
                manifestStatus, startedAt, endedAt,
                capturedDurationMs == null
                        ? durationMs(startedAt, endedAt, projection.durationMs())
                        : capturedDurationMs,
                new MeetingManifest.Languages(projection.language(),
                        projection.targetLanguages()),
                artifactRefs, warnings);
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

        // 同一事务内按领域状态机依次前进；外部只会看到最终终态。
        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
        long version = locked.aggregateVersion();
        JdbcMeetingRepository.VersionedMeeting current = locked;
        if (aggregate.state() == MeetingState.STARTING
                || aggregate.state() == MeetingState.RUNNING) {
            aggregate.apply(MeetingEvent.STOP_REQUESTED, endedAt);
            JdbcMeetingRepository.updateOwnedExisting(connection, engine, current,
                    aggregate.snapshot());
            version++;
            current = new JdbcMeetingRepository.VersionedMeeting(
                    aggregate.snapshot(), version);
        }
        if (aggregate.state() != MeetingState.STOPPING) {
            throw new IllegalStateException("client result requires a stopping meeting");
        }
        aggregate.apply(MeetingEvent.INPUT_ACCEPTED, endedAt);
        JdbcMeetingRepository.updateOwnedExisting(connection, engine, current,
                aggregate.snapshot());
        version++;
        current = new JdbcMeetingRepository.VersionedMeeting(aggregate.snapshot(), version);
        aggregate.apply(terminalEvent, endedAt);
        JdbcMeetingRepository.updateOwnedExisting(connection, engine, current,
                aggregate.snapshot());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE %s SET input_state='INPUT_ACCEPTED',
                    finalization_receipt_id=?, manifest_state=?
                WHERE meeting_id=? AND state=?
                """.formatted(table(JdbcDatabaseTable.MEETING)))) {
            JdbcValues.setText(statement, 1, engine, receipts.newReceipt());
            JdbcValues.setText(statement, 2, engine, manifestStatus.name());
            JdbcValues.setText(statement, 3, engine, meetingId);
            JdbcValues.setText(statement, 4, engine, aggregate.state().name());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("client result state update failed");
            }
        }
        LOGGER.log(Level.INFO,
                "客户端结果已生成 segments={0} translations={1} summary={2}",
                new Object[]{projection.segmentCount(), projection.translations().size(),
                        projection.summary() != null});
    }

    /**
     * 结果表对 client_input 有外键；心跳已证明本场由客户端驱动，缺失时补齐起点。
     * 已有输入行（正常 startClient 写入）保持原 started_at，不被心跳覆盖。
     */
    private void ensureClientInput(Connection connection, String meetingId, Instant startedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT started_at FROM " + table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)
                        + " WHERE meeting_id=?" + currentRead())) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table(JdbcDatabaseTable.MEETING_CLIENT_INPUT)
                        + " (meeting_id, started_at) VALUES (?, ?)")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            JdbcValues.setInstant(statement, 2, startedAt);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("abandoned client input insert did not create one row");
            }
        }
    }

    /** 只读判断本场是否已落弃场决定；正常 Stop 收尾据此让位给扫描器。 */
    private boolean abandonmentDecided(String meetingId) {
        try (Connection connection = dataSource.getConnection()) {
            return JdbcMeetingAbandonmentStore.exists(connection, engine, meetingId);
        } catch (SQLException failure) {
            throw new IllegalStateException("abandonment decision read unavailable", failure);
        }
    }

    /**
     * 结果已由弃场链接管或已存在时，尽力清理遗留待办。
     *
     * <p>清理失败只记录类别：待办会在下一轮扫描再次遇到同一终态，收口入口仍会
     * 尝试删除，不会重复发布结果。不能因为待办表暂时不可用而把已完成的会议
     * 改报为失败。</p>
     */
    private void clearPendingQuietly(String meetingId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcClientFinalizationPendingStore.delete(connection, engine, meetingId);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "最终收口待办清理暂未完成 reason={0}",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * 按首次 Create 冻结的能力计划选择冻结用量超时收口的终态。
     *
     * <p>能力计划里非 {@code NOT_REQUESTED} 的告警码与普通最终 Manifest 使用同一
     * 规则：有告警时终态为 {@code READY_WITH_WARNINGS} 并携带对应 warning；
     * 否则为 READY。没有 Create 事实的历史夹具按 READY 处理，不伪造告警。</p>
     */
    private ResultTerminal resultTerminal(Connection connection, String meetingId)
            throws SQLException {
        var first = new JdbcCreateMeetingFactStore(connection, engine)
                .findByMeetingId(meetingId).orElse(null);
        return terminalFor(first == null ? List.of()
                : first.response().capabilityDecisions());
    }

    /**
     * 纯函数：能力计划中的非 {@code NOT_REQUESTED} 告警码决定终态与 Manifest 告警。
     * 与普通最终 Manifest 使用同一告警归类，避免两条收尾链对同一计划给出不同状态。
     */
    static ResultTerminal terminalFor(
            List<com.czur.reference.domain.capability.CapabilityDecision> decisions) {
        List<MeetingManifest.Warning> warnings = new ArrayList<>();
        for (com.czur.reference.domain.capability.CapabilityDecision decision : decisions) {
            String code = decision.warningCode();
            if (code == null || "NOT_REQUESTED".equals(code)) continue;
            warnings.add(new MeetingManifest.Warning(code, decision.capability(),
                    "ai_meeting.capability." + warningCategory(code), null));
        }
        if (warnings.isEmpty()) {
            return new ResultTerminal(MeetingEvent.READY, ManifestStatus.READY, List.of());
        }
        return new ResultTerminal(MeetingEvent.READY_WITH_WARNINGS,
                ManifestStatus.READY_WITH_WARNINGS, List.copyOf(warnings));
    }

    private static String warningCategory(String code) {
        int separator = code.indexOf(':');
        String category = separator < 0 ? code : code.substring(0, separator);
        return category.matches("[A-Z_]+") ? category : "UNAVAILABLE";
    }

    /** 冻结用量超时收口使用的终态、Manifest 状态和告警。 */
    record ResultTerminal(MeetingEvent event, ManifestStatus manifestStatus,
            List<MeetingManifest.Warning> warnings) { }

    /**
     * 只从已持久的客户端输入事实恢复Commit，不制造durable_input_fact。
     *
     * <p>首次提交保存的expectedVersion只是冻结时刻的观察值，不能作为结算的
     * 唯一门槛：升级前或部分写入的记录可能缺失该列，Reservation 的当前版本也
     * 可能因恢复路径变化。请求同时携带服务端会议标识，Provider 锁定预占后核对
     * {@code bound_meeting_id} 归属并以当前版本提交；过期预占同样允许结算。幂等键
     * 和摘要仍只依赖同场稳定字段，Provider 账本负责首次提交／重放。失败抛回原
     * Stop 编排，维持已提交停止意图和待收口状态；本类不会在未结算时发布 READY。</p>
     */
    private Long commitClientUsage(String meetingId, boolean allowActiveState) {
        FrozenClientUsage usage = null;
        String states = allowActiveState
                ? "'STARTING', 'RUNNING', 'STOPPING', 'READY'"
                : "'STOPPING', 'READY'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.deployment_scope, m.subject_scope, m.usage_reservation_id,
                            i.audio_duration_ms, i.usage_submitted_at, i.usage_expected_version
                     FROM %s m JOIN %s i USING (meeting_id)
                     WHERE m.meeting_id=? AND m.state IN (%s)
                     """.formatted(table(JdbcDatabaseTable.MEETING),
                             table(JdbcDatabaseTable.MEETING_CLIENT_INPUT), states))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getObject("audio_duration_ms", Long.class) != null) {
                    Long frozenVersion = rows.getObject("usage_expected_version", Long.class);
                    usage = new FrozenClientUsage(JdbcValues.text(rows, "deployment_scope"),
                            JdbcValues.text(rows, "subject_scope"),
                            JdbcValues.text(rows, "usage_reservation_id"),
                            rows.getLong("audio_duration_ms"),
                            JdbcValues.instant(rows, "usage_submitted_at"),
                            frozenVersion == null ? 0L : frozenVersion);
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
                new UsageActual(UsageUnit.MEETING_DURATION_MS, usage.audioDurationMs()),
                meetingId);
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

    private MeetingManifest.ArtifactRef publishReady(Connection connection, String artifactId,
            String meetingId, String type, String language, ObjectNode body)
            throws ArtifactStoreException, SQLException {
        return new JdbcClientResultArtifacts(artifactStore, engine, maxClientArtifactBytes)
                .publishReady(connection, artifactId, meetingId, type, language, 1, body);
    }

    /** 本场输入已在 Stop 冻结；读取完释放连接后再执行可选后处理。 */
    private ObjectNode identifySpeakers(String meetingId) {
        if (speakerIdentity == null) return null;
        MeetingSpeakers input;
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT state FROM "
                    + table(JdbcDatabaseTable.MEETING) + " m WHERE meeting_id=? AND NOT EXISTS (SELECT 1 FROM "
                    + table(JdbcDatabaseTable.MEETING_CLIENT_RESULT) + " r WHERE r.meeting_id=m.meeting_id)")) {
                JdbcValues.setText(statement, 1, engine, meetingId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !"STOPPING".equals(JdbcValues.text(rows, "state"))) return null;
                }
            }
            input = speakerInput(connection, engine, meetingId);
            connection.rollback();
        } catch (SQLException failure) {
            throw new IllegalStateException("speaker input database unavailable");
        }
        if (input == null || input.speakers().isEmpty()) return null;
        Map<String, SpeakerIdentityProvider.Person> matches = Map.of();
        try {
            matches = speakerIdentity.identify(input).matches();
            LOGGER.log(Level.INFO, "会后人物识别完成 speakers={0} matched={1}",
                    new Object[]{input.speakers().size(), matches.size()});
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "会后人物识别暂未完成，保留本场分人 reason={0}",
                    failure.getClass().getSimpleName());
        }
        return identityArtifact(input, matches);
    }

    /** 注册与首次收口共用同一份最终事件投影，不复制本场 speaker／时间窗规则。 */
    static MeetingSpeakers speakerInput(Connection connection, JdbcDatabaseEngine engine,
            String meetingId) throws SQLException {
        var first = new JdbcCreateMeetingFactStore(connection, engine)
                .findByMeetingIdReadOnly(meetingId).orElse(null);
        if (first == null || !first.request().requestSnapshot().artifactPolicy().retainAudio()
                || !first.request().requestSnapshot().requestedCapabilities()
                        .contains(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING)
                || first.response().capabilityDecisions().stream().noneMatch(decision ->
                        CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING.equals(decision.capability())
                                && decision.action() == CapabilityDecision.Action.ENABLE)) return null;
        ArtifactDescriptor audio = audioOriginal(connection, engine, meetingId);
        if (audio == null) return null;
        String deployment;
        String subject;
        try (var statement = connection.prepareStatement("SELECT deployment_scope, subject_scope FROM "
                + JdbcDatabaseTable.MEETING.physicalName(engine) + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                deployment = JdbcValues.text(rows, "deployment_scope");
                subject = JdbcValues.text(rows, "subject_scope");
            }
        }
        Map<String, List<AudioWindow>> windows = new java.util.LinkedHashMap<>();
        for (var segment : project(connection, engine, meetingId).transcripts()) {
            if (segment.hasSpeaker() && !segment.getSpeaker().getSessionSpeakerId().isBlank()
                    && segment.getEndMs() > segment.getStartMs()) {
                windows.computeIfAbsent(segment.getSpeaker().getSessionSpeakerId(),
                        ignored -> new ArrayList<>()).add(
                                new AudioWindow(segment.getStartMs(), segment.getEndMs()));
            }
        }
        return new MeetingSpeakers(deployment, subject, meetingId, audio,
                windows.entrySet().stream().map(entry ->
                        new SpeakerSample(entry.getKey(), entry.getValue())).toList());
    }

    static ObjectNode identityArtifact(MeetingSpeakers input,
            Map<String, SpeakerIdentityProvider.Person> matches) {
        ObjectNode artifact = JSON.createObjectNode();
        artifact.put("schemaVersion", "speaker-identity/v1");
        artifact.put("artifactType", "speaker.identity");
        ArrayNode speakers = artifact.putArray("speakers");
        for (var speaker : input.speakers()) {
            ObjectNode item = speakers.addObject();
            item.put("sessionSpeakerId", speaker.sessionSpeakerId());
            var person = matches.get(speaker.sessionSpeakerId());
            if (person == null) {
                item.putNull("personId");
                item.putNull("displayName");
            } else {
                item.put("personId", person.personId());
                item.put("displayName", person.displayName());
            }
        }
        return artifact;
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

    /** 快照和最终 Manifest 共用同场截图的唯一 metadata 排序／连续性规则。 */
    private static List<ArtifactReadyMetadataStore.ReadyMetadata> presentationCaptures(
            Connection connection, JdbcDatabaseEngine engine, String meetingId) {
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
        }
        return captures;
    }

    /** 录音是本场最多一个不可变 audio.original；缺失时维持原无录音结果行为。 */
    private static ArtifactDescriptor audioOriginal(Connection connection,
            JdbcDatabaseEngine engine, String meetingId) {
        List<ArtifactReadyMetadataStore.ReadyMetadata> audio =
                new JdbcArtifactReadyMetadataStore(connection, engine).findByMeetingId(meetingId)
                        .stream().filter(item -> "audio.original".equals(
                                item.artifact().intent().spec().artifactType()))
                        .toList();
        if (audio.size() > 1) {
            throw new IllegalStateException("multiple original audio artifacts");
        }
        if (audio.isEmpty()) return null;
        ArtifactDescriptor descriptor = audio.getFirst().artifact();
        WriteIntentSpec spec = descriptor.intent().spec();
        if (!meetingId.equals(spec.meetingId()) || spec.revision() != 1
                || spec.language() != null
                || !(spec.mediaType().startsWith("audio/")
                    || "application/octet-stream".equals(spec.mediaType()))) {
            throw new IllegalStateException("original audio artifact metadata is invalid");
        }
        return descriptor;
    }

    private static Instant audioReadyRecordedAt(Connection connection,
            JdbcDatabaseEngine engine, String meetingId, String artifactId) {
        return new JdbcArtifactReadyMetadataStore(connection, engine).findByArtifactId(artifactId)
                .filter(item -> meetingId.equals(item.artifact().intent().spec().meetingId()))
                .map(ArtifactReadyMetadataStore.ReadyMetadata::recordedAt)
                .orElseThrow(() -> new IllegalStateException("original audio metadata is missing"));
    }

    private static boolean retainAudioRequested(Connection connection,
            JdbcDatabaseEngine engine, String meetingId) {
        var first = new JdbcCreateMeetingFactStore(connection, engine)
                .findByMeetingId(meetingId).orElse(null);
        return first != null && first.request().requestSnapshot().artifactPolicy().retainAudio();
    }

    /**
     * 最终字幕产物的唯一序列化口径，发布与追加受理共用，避免两处正文大小漂移。
     *
     * <p>整场文字同时进入顶层 {@code text} 与每个 {@code segments[].text}，预算
     * 统计必须按这份完整 JSON 的 UTF-8 字节数计算。</p>
     */
    static ObjectNode transcriptArtifactBody(Projection projection) {
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
                item.put("sessionSpeakerId", segment.getSpeaker().getSessionSpeakerId());
            }
        }
        return artifact;
    }

    /** 最终译文产物的唯一序列化口径，与发布端相同。 */
    static ObjectNode translationArtifactBody(Projection projection) {
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
        return artifact;
    }

    /** 结构化纪要产物的唯一序列化口径，与发布端相同。 */
    static ObjectNode summaryArtifactBody(Projection projection) {
        ObjectNode artifact = JSON.createObjectNode();
        artifact.put("schemaVersion", "client-summary/v1");
        artifact.put("artifactType", "summary.structured");
        artifact.setAll(projection.summary());
        return artifact;
    }

    /**
     * 返回当前最终序列化正文的 UTF-8 字节数，供追加受理和测试核对同一预算。
     * 只统计实际会发布的字幕／译文／纪要 JSON；不含 Manifest 或无关元数据。
     */
    static long serializedClientBodyBytes(Connection connection, JdbcDatabaseEngine engine,
            String meetingId) throws SQLException {
        Projection projection = project(connection, engine, meetingId);
        long total = 0;
        if (projection.segmentCount() > 0) {
            total = Math.addExact(total, utf8Length(transcriptArtifactBody(projection)));
        }
        if (!projection.translations().isEmpty()) {
            total = Math.addExact(total, utf8Length(translationArtifactBody(projection)));
        }
        if (projection.summary() != null) {
            total = Math.addExact(total, utf8Length(summaryArtifactBody(projection)));
        }
        return total;
    }

    private static long utf8Length(ObjectNode body) {
        return body.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** 两引擎共享既有最终修订规则；只读快照不使用锁定读取。 */
    static Projection project(Connection connection, JdbcDatabaseEngine engine, String meetingId)
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

    record Projection(String text, String language, long durationMs,
            List<TranscriptUpsert> transcripts, List<TranslationUpsert> translations,
            List<String> targetLanguages, String translationLanguage, ObjectNode summary) {
        int segmentCount() { return transcripts.size(); }
        boolean hasArtifacts() {
            return !transcripts.isEmpty() || !translations.isEmpty() || summary != null;
        }
    }
}
