package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.model.RealtimeSessionSpec;
import com.czur.reference.application.realtime.RealtimeMockSessionSpecLookup;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * 同一只读快照联合 Meeting、V013 首次 Create 与 V011 冻结 Start 的 Mock 规格。
 *
 * <p>源语言、音频合同和时长限制只来自 V013；有效能力与计划版本只来自 V011。
 * 不从当前配置或客户端 stream_start 补事实。任一归属、版本或冻结事实不一致均
 * 关闭式失败，不输出 Token、正文、SQL 或会议标识。</p>
 */
public final class JdbcRealtimeMockSessionSpecLookup
        implements RealtimeMockSessionSpecLookup {
    private final DataSource dataSource;
    private final String deploymentScope;

    public JdbcRealtimeMockSessionSpecLookup(DataSource dataSource, String deploymentScope) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deployment scope required");
        }
        this.deploymentScope = deploymentScope;
    }

    @Override
    public RealtimeSessionSpec load(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            pinSchema(connection);
            MeetingRow meeting = meeting(connection, meetingId);
            if (meeting == null || !deploymentScope.equals(meeting.deploymentScope())
                    || meeting.subjectScope() == null || meeting.subjectScope().isBlank()
                    || MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                            meeting.subjectScope())) {
                throw invalid();
            }
            CreateMeetingFactStore.Record first = new JdbcCreateMeetingFactStore(connection)
                    .findByMeetingIdReadOnly(meetingId).orElseThrow(
                            JdbcRealtimeMockSessionSpecLookup::invalid);
            var execution = new JdbcFinalizationExecutionFactStore(connection)
                    .find(meetingId).orElseThrow(JdbcRealtimeMockSessionSpecLookup::invalid);
            var request = first.request().requestSnapshot();
            var response = first.response();
            if (!first.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                            meeting.deploymentScope(), meeting.subjectScope()))
                    || !first.createdAt().equals(meeting.createdAt())
                    || !meetingId.equals(response.meetingId())
                    || !meetingId.equals(execution.meetingId())
                    || !meeting.clientSessionId().equals(request.clientSessionId())
                    || !response.acceptedProfile().equals(request.profile())
                    || !meeting.profileId().equals(response.acceptedProfile().id())
                    || meeting.profileRevision() != response.acceptedProfile().revision()
                    || !response.acceptedProfile().id().equals(execution.productProfile().id())
                    || response.acceptedProfile().revision()
                            != execution.productProfile().revision()
                    || !response.acceptedAudioProfile().equals(request.audioProfile())
                    || !new CapabilityPlan(1L, response.capabilityDecisions()).equals(
                            execution.capabilityPlan())
                    || !execution.capabilityPlan().enabledCapabilities().contains(
                            CapabilityIds.TRANSCRIPT_REALTIME)) {
                throw invalid();
            }
            RealtimeSessionSpec spec = new RealtimeSessionSpec(meetingId, meetingId,
                    request.languages().source(), response.acceptedAudioProfile(),
                    execution.capabilityPlan().enabledCapabilities(),
                    execution.capabilityPlan().planRevision(),
                    response.limits().maxMeetingDurationMs(),
                    "v011-" + execution.productProfile().id() + "-"
                            + execution.productProfile().revision());
            connection.rollback();
            return spec;
        } catch (SQLException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static MeetingRow meeting(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deployment_scope, subject_scope, client_session_id,
                       profile_id, profile_revision, created_at
                  FROM meeting WHERE meeting_id=?
                """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Timestamp created = rows.getTimestamp("created_at");
                MeetingRow fact = new MeetingRow(rows.getString("deployment_scope"),
                        rows.getString("subject_scope"), rows.getString("client_session_id"),
                        rows.getString("profile_id"), rows.getInt("profile_revision"),
                        created == null ? null : created.toInstant());
                if (rows.next()) throw invalid();
                return fact;
            }
        }
    }

    private static void pinSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE)
                """)) {
            statement.executeQuery().close();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("mock session facts unavailable");
    }

    private record MeetingRow(String deploymentScope, String subjectScope,
            String clientSessionId, String profileId, int profileRevision,
            java.time.Instant createdAt) { }
}
