package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.model.ModelSessionLanguageLookup;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * 模型会话的本场事实读取：会议行和首次 Create 事实来自同一个只读快照。
 * 只有当前部署的会议所有者能得到语言与首次能力决策；不从全局模型配置、
 * 未认证的请求 body 或当前已变化的产品档位推断翻译权限。
 */
public final class JdbcModelSessionLanguageLookup implements ModelSessionLanguageLookup {
    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    public JdbcModelSessionLanguageLookup(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.MYSQL);
    }

    /** 读取本场冻结语言和能力，复用 Context 同一池及只读一致快照。 */
    public JdbcModelSessionLanguageLookup(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcModelSessionLanguageLookup(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public ModelSessionIssuer.Request load(MeetingCommandContext context, String meetingId) {
        return loadFacts(context, meetingId).request();
    }

    @Override
    public SummaryEligibility summaryEligibility(MeetingCommandContext context,
            String meetingId) {
        Facts facts = loadFacts(context, meetingId);
        return new SummaryEligibility(facts.clientStarted(), facts.summaryEnabled());
    }

    private Facts loadFacts(MeetingCommandContext context, String meetingId) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT deployment_scope, subject_scope, client_session_id, created_at
                      FROM %s WHERE meeting_id=?
                    """.formatted(JdbcDatabaseTable.MEETING.physicalName(engine)))) {
                JdbcValues.setText(statement, 1, engine, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || MeetingAccessGuard.evaluate(context,
                            JdbcValues.text(rows, "deployment_scope"), JdbcValues.text(rows, "subject_scope"))
                            != MeetingAccessGuard.Decision.ALLOWED) throw unavailable();
                    String sessionId = JdbcValues.text(rows, "client_session_id");
                    Instant createdAt = JdbcValues.instant(rows, "created_at");
                    if (rows.next()) throw unavailable();
                    CreateMeetingFactStore.Record first = new JdbcCreateMeetingFactStore(connection, engine)
                            .findByMeetingIdReadOnly(meetingId).orElseThrow(
                                    JdbcModelSessionLanguageLookup::unavailable);
                    var request = first.request().requestSnapshot();
                    if (!first.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                            context.deploymentScope(), context.identity().subject().subjectScope()))
                            || !sessionId.equals(request.clientSessionId())
                            || createdAt == null || !first.createdAt().equals(createdAt)) {
                        throw unavailable();
                    }
                    boolean clientStarted;
                    try (PreparedStatement marker = connection.prepareStatement(
                            "SELECT 1 FROM " + JdbcDatabaseTable.MEETING_CLIENT_INPUT.physicalName(engine)
                                    + " WHERE meeting_id=?")) {
                        JdbcValues.setText(marker, 1, engine, meetingId);
                        try (ResultSet clientRows = marker.executeQuery()) {
                            clientStarted = clientRows.next();
                        }
                    }
                    connection.rollback();
                    boolean translationEnabled = request.requestedCapabilities().contains(
                            CapabilityIds.TRANSLATION_REALTIME)
                            && first.response().capabilityDecisions().stream().anyMatch(decision ->
                                    CapabilityIds.TRANSLATION_REALTIME.equals(decision.capability())
                                    && decision.action() == CapabilityDecision.Action.ENABLE);
                    boolean summaryEnabled = request.requestedCapabilities().contains(
                            CapabilityIds.SUMMARY_STRUCTURED)
                            && first.response().capabilityDecisions().stream().anyMatch(decision ->
                                    CapabilityIds.SUMMARY_STRUCTURED.equals(decision.capability())
                                    && decision.action() == CapabilityDecision.Action.ENABLE);
                    return new Facts(new ModelSessionIssuer.Request(request.languages().source(),
                            request.languages().targets(), translationEnabled, meetingId), summaryEnabled,
                            clientStarted);
                }
            }
        } catch (SQLException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("model session language unavailable");
    }

    private record Facts(ModelSessionIssuer.Request request, boolean summaryEnabled,
            boolean clientStarted) {}
}
