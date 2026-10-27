package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingInputClosureContextPort;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从一次只读快照还原会议输入收口所需的可信上下文。
 *
 * <p>Meeting 的归属、状态、V013 首次 Create 与 V011 冻结 Start 必须在同一
 * REPEATABLE_READ 快照中核对。源语言只取首次 Create；有效能力只取 Start 的
 * 冻结计划。当前参考后端只提供 ASR-only 收口，因此有效翻译不能被静默写成
 * 空目标语言。此适配器不执行 Stop、Admission 或任何写入。</p>
 */
public final class JdbcMeetingInputClosureContextPort
        implements MeetingInputClosureContextPort {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingInputClosureContextPort.class.getName());
    private final DataSource dataSource;
    private final String trustedDeploymentScope;

    /**
     * @param dataSource 已完成 V013/V011/V005 迁移、由运行时持有的连接源
     * @param trustedDeploymentScope 服务端部署配置，不能取自会议请求或客户端 Token
     */
    public JdbcMeetingInputClosureContextPort(DataSource dataSource,
            String trustedDeploymentScope) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (trustedDeploymentScope == null || trustedDeploymentScope.isBlank()) {
            throw new IllegalArgumentException("trustedDeploymentScope is required");
        }
        this.trustedDeploymentScope = trustedDeploymentScope;
    }

    /** 只读取完整事实；缺少 Stop 与缺失/损坏的历史事实分别返回不同分类。 */
    @Override
    public Result load(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            pinSchema(connection);

            // 先只读原始部署范围，再解析完整会议快照。即使另一部署的会议行
            // 本身损坏，也不能向本部署泄漏“存在但不一致”的分类。
            boolean sameDeployment;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT deployment_scope FROM meeting WHERE meeting_id=?")) {
                statement.setString(1, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    sameDeployment = rows.next()
                            && trustedDeploymentScope.equals(rows.getString("deployment_scope"));
                }
            }
            if (!sameDeployment) {
                connection.rollback();
                return new Missing();
            }

            JdbcMeetingRepository.PublicationMeeting meeting =
                    JdbcMeetingRepository.findManifestPublication(connection, meetingId)
                            .orElse(null);
            if (meeting == null) {
                connection.rollback();
                return new Inconsistent();
            }
            var snapshot = meeting.snapshot();
            // meetingId 本身不是授权凭据。本部署不能用另一部署的 Meeting 去触发
            // Admission；在读取其 V013/V011 之前按不存在处理，避免暴露跨部署事实。
            if (!trustedDeploymentScope.equals(snapshot.deploymentScope())) {
                connection.rollback();
                return new Missing();
            }
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                    snapshot.subjectScope())
                    || snapshot.subjectScope().isBlank()
                    || snapshot.deploymentScope().isBlank()) {
                connection.rollback();
                return new Inconsistent();
            }
            CreateMeetingFactStore.Record first = new JdbcCreateMeetingFactStore(connection)
                    .findByMeetingIdReadOnly(meetingId).orElse(null);
            FinalizationExecutionFact started =
                    new JdbcFinalizationExecutionFactStore(connection)
                            .find(meetingId).orElse(null);
            if (first == null || started == null || !sameFrozenFacts(meeting, first, started)) {
                connection.rollback();
                return new Inconsistent();
            }
            if (translationEnabled(started)) {
                connection.rollback();
                return new Inconsistent();
            }
            Result result = classifyState(meeting, first);
            connection.rollback();
            return result;
        } catch (JdbcMeetingRepository.PublicationSnapshotException failure) {
            return inconsistent();
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            return failure.kind() == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA
                    ? inconsistent() : unavailable();
        } catch (JdbcFinalizationExecutionFactStore.StoredFactException failure) {
            return failure.kind()
                    == JdbcFinalizationExecutionFactStore.FailureKind.CORRUPT_DATA
                    ? inconsistent() : unavailable();
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "收口上下文读取失败 sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return JdbcReadLookupSupport.classify(failure)
                    == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                    ? new Inconsistent() : new Unavailable();
        } catch (RuntimeException failure) {
            // 未知 JDBC/连接池故障不得伪装成历史事实损坏；异常正文可能含动态值。
            return unavailable();
        }
    }

    private static boolean sameFrozenFacts(JdbcMeetingRepository.PublicationMeeting meeting,
            CreateMeetingFactStore.Record first, FinalizationExecutionFact started) {
        var snapshot = meeting.snapshot();
        var request = first.request().requestSnapshot();
        var response = first.response();
        return first.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                        snapshot.deploymentScope(), snapshot.subjectScope()))
                && first.createdAt().equals(snapshot.createdAt())
                && snapshot.meetingId().equals(response.meetingId())
                && snapshot.meetingId().equals(started.meetingId())
                && snapshot.clientSessionId().equals(request.clientSessionId())
                && snapshot.profileId().equals(request.profile().id())
                && snapshot.profileRevision() == request.profile().revision()
                && response.acceptedProfile().equals(request.profile())
                && response.acceptedAudioProfile().equals(request.audioProfile())
                && snapshot.profileId().equals(started.productProfile().id())
                && snapshot.profileRevision() == started.productProfile().revision()
                && response.capabilityDecisions().stream()
                        .allMatch(decision -> decision.planRevision() == 1L)
                && new CapabilityPlan(1L, response.capabilityDecisions())
                        .equals(started.capabilityPlan())
                && !started.startedAt().isBefore(snapshot.createdAt())
                && !started.startedAt().isAfter(snapshot.updatedAt());
    }

    private static boolean translationEnabled(FinalizationExecutionFact started) {
        CapabilityDecision translation = started.capabilityPlan()
                .decisionFor(CapabilityIds.TRANSLATION_REALTIME);
        return translation != null
                && (translation.action() == CapabilityDecision.Action.ENABLE
                || translation.action() == CapabilityDecision.Action.USE_FALLBACK);
    }

    private static Result classifyState(JdbcMeetingRepository.PublicationMeeting meeting,
            CreateMeetingFactStore.Record first) {
        var snapshot = meeting.snapshot();
        InputState input = meeting.finalization().inputState();
        MeetingState state = snapshot.state();
        ManifestStatus manifest = meeting.manifestState();
        if ((state == MeetingState.RUNNING || state == MeetingState.STARTING)
                && input == InputState.INPUT_OPEN
                && manifest == ManifestStatus.NOT_STARTED) {
            return new WaitingForStop();
        }
        if (state == MeetingState.STOPPING
                && input == InputState.INPUT_COMPLETE_PENDING
                && manifest == ManifestStatus.NOT_STARTED) {
            return new Found(new Context(snapshot.deploymentScope(), snapshot.subjectScope(),
                    snapshot.meetingId(), input, snapshot.updatedAt(),
                    first.request().requestSnapshot().languages().source()));
        }
        if (input == InputState.INPUT_ACCEPTED && acceptedStateMatches(state, manifest)) {
            return new Found(new Context(snapshot.deploymentScope(), snapshot.subjectScope(),
                    snapshot.meetingId(), input, snapshot.updatedAt(),
                    first.request().requestSnapshot().languages().source()));
        }
        return new Inconsistent();
    }

    private static boolean acceptedStateMatches(MeetingState state, ManifestStatus manifest) {
        return switch (state) {
            case FINALIZING -> manifest == ManifestStatus.GENERATING;
            case READY -> manifest == ManifestStatus.READY;
            case READY_WITH_WARNINGS -> manifest == ManifestStatus.READY_WITH_WARNINGS;
            case FAILED -> manifest == ManifestStatus.FAILED;
            case DELETION_PENDING -> manifest == ManifestStatus.DELETION_PENDING;
            case DELETED -> manifest == ManifestStatus.DELETED;
            default -> false;
        };
    }

    private static void pinSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_schema()");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getString(1) == null
                    || rows.getString(1).startsWith("pg_temp")) {
                throw new SQLException("deployment schema unavailable");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE)
                """)) {
            statement.execute();
        }
    }

    private static Inconsistent inconsistent() {
        LOGGER.log(Level.WARNING, "收口上下文持久事实不一致 module=input-closure-context");
        return new Inconsistent();
    }

    private static Unavailable unavailable() {
        LOGGER.log(Level.WARNING, "收口上下文读取不可用 module=input-closure-context");
        return new Unavailable();
    }
}
