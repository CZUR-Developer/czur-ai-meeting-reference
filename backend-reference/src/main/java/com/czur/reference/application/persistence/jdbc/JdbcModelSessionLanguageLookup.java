package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.model.ModelSessionLanguageLookup;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * 模型会话的本场语言读取：会议行和首次 Create 事实来自同一个只读快照。
 * 只有当前部署的会议所有者能得到语言；不从全局模型配置或请求 body 推断。
 */
public final class JdbcModelSessionLanguageLookup implements ModelSessionLanguageLookup {
    private final DataSource dataSource;

    public JdbcModelSessionLanguageLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public String load(MeetingCommandContext context, String meetingId) {
        Objects.requireNonNull(context, "context");
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            pinSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT deployment_scope, subject_scope, client_session_id, created_at
                      FROM meeting WHERE meeting_id=?
                    """)) {
                statement.setString(1, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || MeetingAccessGuard.evaluate(context,
                            rows.getString("deployment_scope"), rows.getString("subject_scope"))
                            != MeetingAccessGuard.Decision.ALLOWED) throw unavailable();
                    String sessionId = rows.getString("client_session_id");
                    Timestamp createdAt = rows.getTimestamp("created_at");
                    if (rows.next()) throw unavailable();
                    CreateMeetingFactStore.Record first = new JdbcCreateMeetingFactStore(connection)
                            .findByMeetingIdReadOnly(meetingId).orElseThrow(
                                    JdbcModelSessionLanguageLookup::unavailable);
                    var request = first.request().requestSnapshot();
                    if (!first.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                            context.deploymentScope(), context.identity().subject().subjectScope()))
                            || !sessionId.equals(request.clientSessionId())
                            || createdAt == null || !first.createdAt().equals(createdAt.toInstant())) {
                        throw unavailable();
                    }
                    connection.rollback();
                    return request.languages().source();
                }
            }
        } catch (SQLException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void pinSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE)
                """)) {
            statement.executeQuery().close();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("model session language unavailable");
    }
}
