package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.MeetingSummary;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从同一会议权威事实表读取会议详情和归属列表。
 *
 * <p>{@link #get(GetRequest)} 使用一条 SELECT 同时读取聚合归属、会议状态、
 * meeting-level 输入状态、finalization receipt、Manifest 状态和时间，再复用
 * {@link MeetingAccessGuard} 做统一归属判断。会议不存在返回 {@link NotFound}；
 * 历史 {@code LEGACY_UNOWNED}、跨部署和跨主体访问统一返回
 * {@link AccessDenied}，不会通过不同数据库查询路径泄露归属类型。</p>
 *
 * <p>{@link #list(ListRequest)} 同样只执行一条 SELECT，并在 SQL 中直接限定服务端
 * 部署范围与已验证主体。查询按 {@code created_at DESC, meeting_id DESC} 使用
 * keyset 分页，读取 {@code pageSize + 1} 条判断是否有下一页；下一页 token 始终
 * 基于本页最后一条已返回记录生成，不使用额外探测行。标题固定为 {@code null}，
 * 绝不从 {@code metadata} 或会议正文猜测。</p>
 *
 * <p>日志只记录固定状态、冲突原因和经过校验的 SQLState，不记录部署、主体、
 * page token、Secret、receipt、数据库地址或会议正文。</p>
 */
public final class JdbcMeetingQueryPort implements MeetingQueryPort {
    private static final Logger LOGGER = Logger.getLogger(JdbcMeetingQueryPort.class.getName());

    private static final String GET_SQL = """
            SELECT meeting_id, deployment_scope, client_session_id, subject_scope,
                   grant_id, grant_expires_at, usage_reservation_id,
                   profile_id, profile_revision, state, input_state,
                   finalization_receipt_id, manifest_state, created_at, updated_at
              FROM %s
             WHERE meeting_id = ?
            """;

    private static final String LIST_FIRST_PAGE_SQL = """
            SELECT m.meeting_id, m.state, m.input_state, m.finalization_receipt_id,
                   m.manifest_state, m.created_at, m.updated_at, s.summary_json
              FROM %s m
              LEFT JOIN %s s ON s.meeting_id = m.meeting_id
             WHERE m.deployment_scope = ?
               AND m.subject_scope = ?
               AND m.subject_scope <> 'LEGACY_UNOWNED'
             ORDER BY m.created_at DESC, m.meeting_id DESC
             LIMIT ?
            """;

    private static final String LIST_AFTER_CURSOR_SQL = """
            SELECT m.meeting_id, m.state, m.input_state, m.finalization_receipt_id,
                   m.manifest_state, m.created_at, m.updated_at, s.summary_json
              FROM %s m
              LEFT JOIN %s s ON s.meeting_id = m.meeting_id
             WHERE m.deployment_scope = ?
               AND m.subject_scope = ?
               AND m.subject_scope <> 'LEGACY_UNOWNED'
               AND (m.created_at < ? OR (m.created_at = ? AND m.meeting_id < ?))
             ORDER BY m.created_at DESC, m.meeting_id DESC
             LIMIT ?
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;
    private final HmacMeetingPageTokenCodec pageTokenCodec;

    /**
     * 创建生产装配使用的 JDBC 查询端口。
     *
     * @param dataSource 已由运行时统一管理生命周期的连接池
     * @param deploymentSecret 至少 32 字节、跨进程重启稳定的分页签名 Secret
     */
    public JdbcMeetingQueryPort(DataSource dataSource, byte[] deploymentSecret) {
        this(dataSource, new HmacMeetingPageTokenCodec(deploymentSecret));
    }

    /** 供同包测试和装配复用显式 codec；不会读取或复制 codec 内部 Secret。 */
    JdbcMeetingQueryPort(
            DataSource dataSource,
            HmacMeetingPageTokenCodec pageTokenCodec) {
        this(dataSource, JdbcDatabaseEngine.MYSQL, pageTokenCodec);
    }

    /** 从唯一 Context 的固定事实表读取；分页签名与原归属规则保持。 */
    public JdbcMeetingQueryPort(JdbcDatabaseContext database, byte[] deploymentSecret) {
        this(database.dataSource(), database.engine(), new HmacMeetingPageTokenCodec(deploymentSecret));
    }

    private JdbcMeetingQueryPort(DataSource dataSource, JdbcDatabaseEngine engine,
            HmacMeetingPageTokenCodec pageTokenCodec) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.pageTokenCodec = Objects.requireNonNull(pageTokenCodec, "pageTokenCodec");
    }

    @Override
    public Result get(GetRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            return logged(findDetail(connection, request));
        } catch (CorruptMeetingReadModel failure) {
            return logged(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
        } catch (SQLException failure) {
            return fromGetSqlFailure(failure);
        } catch (RuntimeException failure) {
            // DataSource、驱动代理或安全 Provider 可能抛出无 SQLState 的运行时异常。
            // 不能把其消息透传，也不能无依据承诺重试一定有效。
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false));
        } finally {
            closeQuietly(connection, "会议详情查询");
        }
    }

    @Override
    public ListResult list(ListRequest request) {
        Objects.requireNonNull(request, "request");
        MeetingCommandContext context = request.context();
        Optional<HmacMeetingPageTokenCodec.Cursor> cursor = Optional.empty();
        if (request.pageToken() != null) {
            cursor = pageTokenCodec.decode(context, request.pageToken());
            if (cursor.isEmpty()) {
                return logged(new ListConflict(ListConflictReason.INVALID_PAGE_TOKEN));
            }
        }

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            List<ListRow> rows = readList(
                    connection, context, cursor, Math.addExact(request.pageSize(), 1));
            boolean hasMore = rows.size() > request.pageSize();
            int returnedCount = hasMore ? request.pageSize() : rows.size();
            List<MeetingSummary> items = new ArrayList<>(returnedCount);
            for (int index = 0; index < returnedCount; index++) {
                items.add(rows.get(index).summary());
            }

            String nextPageToken = null;
            if (hasMore) {
                ListRow lastReturned = rows.get(returnedCount - 1);
                nextPageToken = pageTokenCodec.encode(
                        context,
                        new HmacMeetingPageTokenCodec.Cursor(
                                lastReturned.createdAt(), lastReturned.summary().meetingId()));
            }
            return logged(new ListPage(items, nextPageToken));
        } catch (CorruptMeetingReadModel | IllegalArgumentException failure) {
            // 数据库中的状态、时间或游标字段无法组成公开读模型时关闭式失败。
            // 客户端 token 的格式/签名错误已经在访问数据库前单独映射。
            return logged(new ListConflict(ListConflictReason.SNAPSHOT_INCONSISTENT));
        } catch (SQLException failure) {
            return fromListSqlFailure(failure);
        } catch (RuntimeException failure) {
            return logged(new ListUnavailable(UnavailableReason.STORAGE_FAILURE, false));
        } finally {
            closeQuietly(connection, "会议列表查询");
        }
    }

    /**
     * 单条语句读取详情，确保访问守卫与返回状态解释同一个 statement
     * snapshot。禁止先查归属、再用另一条语句查状态，否则 READ COMMITTED 下两次
     * 查询之间的并发更新可能产生混合快照。
     */
    private Result findDetail(Connection connection, GetRequest request)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(GET_SQL.formatted(table()))) {
            JdbcValues.setText(statement, 1, engine, request.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new NotFound();
                }
                // 必须先只读取原始归属字段并调用公共守卫。外域和历史记录在这里
                // 立即终止，不再解析其 Grant、状态或 receipt，避免暴露行健康。
                if (MeetingAccessGuard.evaluate(
                        request.context(),
                        JdbcValues.text(rows, "deployment_scope"),
                        JdbcValues.text(rows, "subject_scope"))
                        != MeetingAccessGuard.Decision.ALLOWED) {
                    return new AccessDenied();
                }
                return new Found(readDetail(rows).status());
            }
        }
    }

    /**
     * 单条范围查询读取一页，隔离条件在 SQL 中执行，避免先加载其他主体记录后
     * 再由 Java 过滤。{@code LEGACY_UNOWNED} 显式排除，即使未来错误的身份
     * Provider 放行了保留主体值，也不会让历史无主会议出现在列表中。
     */
    private List<ListRow> readList(
            Connection connection,
            MeetingCommandContext context,
            Optional<HmacMeetingPageTokenCodec.Cursor> cursor,
            int queryLimit) throws SQLException {
        String sql = (cursor.isPresent() ? LIST_AFTER_CURSOR_SQL : LIST_FIRST_PAGE_SQL)
                .formatted(table(), JdbcDatabaseTable.MEETING_CLIENT_SUMMARY.physicalName(engine));
        List<ListRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, context.deploymentScope());
            JdbcValues.setText(statement, 2, engine, context.identity().subject().subjectScope());
            if (cursor.isPresent()) {
                HmacMeetingPageTokenCodec.Cursor value = cursor.orElseThrow();
                JdbcValues.setInstant(statement, 3, value.createdAt());
                JdbcValues.setInstant(statement, 4, value.createdAt());
                JdbcValues.setText(statement, 5, engine, value.meetingId());
                statement.setInt(6, queryLimit);
            } else {
                statement.setInt(3, queryLimit);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readListRow(rows));
                }
            }
        }
        return result;
    }

    private static DetailRow readDetail(ResultSet rows) throws SQLException {
        try {
            String meetingId = requiredText(rows, "meeting_id");
            String deploymentScope = requiredText(rows, "deployment_scope");
            String clientSessionId = requiredText(rows, "client_session_id");
            String subjectScope = requiredText(rows, "subject_scope");
            String grantId = JdbcValues.text(rows, "grant_id");
            Instant grantExpiresAt = nullableInstant(rows, "grant_expires_at");
            String reservationId = JdbcValues.text(rows, "usage_reservation_id");
            String profileId = requiredText(rows, "profile_id");
            int profileRevision = rows.getInt("profile_revision");
            if (rows.wasNull()) {
                throw new IllegalArgumentException("profile_revision is null");
            }
            MeetingState state = readMeetingState(rows);
            Instant createdAt = requiredInstant(rows, "created_at");
            Instant updatedAt = requiredInstant(rows, "updated_at");
            MeetingAggregate.MeetingSnapshot snapshot = new MeetingAggregate.MeetingSnapshot(
                    meetingId,
                    deploymentScope,
                    clientSessionId,
                    subjectScope,
                    grantId,
                    grantExpiresAt,
                    reservationId,
                    profileId,
                    profileRevision,
                    state,
                    createdAt,
                    updatedAt);
            MeetingStatus status = readStatus(
                    rows, meetingId, state, updatedAt);
            return new DetailRow(snapshot, status);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptMeetingReadModel();
        }
    }

    private static ListRow readListRow(ResultSet rows) throws SQLException {
        try {
            String meetingId = requiredText(rows, "meeting_id");
            MeetingState state = readMeetingState(rows);
            Instant createdAt = requiredInstant(rows, "created_at");
            Instant updatedAt = requiredInstant(rows, "updated_at");
            MeetingStatus status = readStatus(rows, meetingId, state, updatedAt);
            return new ListRow(status.summary(createdAt, summaryTitle(rows)), createdAt);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptMeetingReadModel();
        }
    }

    private static MeetingStatus readStatus(
            ResultSet rows,
            String meetingId,
            MeetingState state,
            Instant updatedAt) throws SQLException {
        InputState inputState = InputState.valueOf(requiredText(rows, "input_state"));
        String receipt = JdbcValues.text(rows, "finalization_receipt_id");
        FinalizationFact finalization = new FinalizationFact(inputState, receipt);
        ManifestStatus manifestState = ManifestStatus.valueOf(
                requiredText(rows, "manifest_state"));
        return new MeetingStatus(
                meetingId, state, finalization, manifestState, updatedAt);
    }

    /**
     * 列表标题来自服务端已发布的总结标题（收口时写进总结事实的 {@code title}）。
     * 没有总结、字段缺失或历史格式异常时返回 {@code null}，由调用方显示「未命名」，
     * 不在这里从正文猜名字。
     */
    private static String summaryTitle(ResultSet rows) throws SQLException {
        return summaryTitleFromJson(JdbcValues.text(rows, "summary_json"));
    }

    /** 拆出来便于单测：只认显式标题，空值、缺字段、非法 JSON 一律返回 null。 */
    static String summaryTitleFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode title = node == null ? null : node.get("title");
            if (title == null || !title.isTextual()) return null;
            String value = title.asText().trim();
            return value.isEmpty() ? null : value;
        } catch (java.io.IOException | RuntimeException invalid) {
            return null;
        }
    }

    private static MeetingState readMeetingState(ResultSet rows) throws SQLException {
        MeetingState state = MeetingState.valueOf(requiredText(rows, "state"));
        if (state == MeetingState.UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN is not a persisted meeting state");
        }
        return state;
    }

    private static String requiredText(ResultSet rows, String column) throws SQLException {
        String value = JdbcValues.text(rows, column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("required meeting column is blank");
        }
        return value;
    }

    private static Instant requiredInstant(ResultSet rows, String column) throws SQLException {
        Instant value = JdbcValues.instant(rows, column);
        if (value == null) {
            throw new IllegalArgumentException("required meeting timestamp is null");
        }
        return value;
    }

    private static Instant nullableInstant(ResultSet rows, String column) throws SQLException {
        return JdbcValues.instant(rows, column);
    }

    private String table() { return JdbcDatabaseTable.MEETING.physicalName(engine); }

    private static Result fromGetSqlFailure(SQLException failure) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        if (kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT) {
            return logged(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT), failure);
        }
        boolean retryable = kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE;
        UnavailableReason reason = retryable
                ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                : UnavailableReason.STORAGE_FAILURE;
        return logged(new Unavailable(reason, retryable), failure);
    }

    private static ListResult fromListSqlFailure(SQLException failure) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        if (kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT) {
            return logged(
                    new ListConflict(ListConflictReason.SNAPSHOT_INCONSISTENT), failure);
        }
        boolean retryable = kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE;
        UnavailableReason reason = retryable
                ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                : UnavailableReason.STORAGE_FAILURE;
        return logged(new ListUnavailable(reason, retryable), failure);
    }

    private static Result logged(Result result) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        if (result instanceof Conflict conflict) {
            LOGGER.log(level, "会议详情查询完成 status={0} reason={1}",
                    new Object[]{result.status(), conflict.reason()});
        } else {
            LOGGER.log(level, "会议详情查询完成 status={0}", result.status());
        }
        return result;
    }

    private static Result logged(Result result, SQLException failure) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level, "会议详情查询完成 status={0} sqlState={1}",
                new Object[]{result.status(), JdbcReadLookupSupport.safeSqlState(failure)});
        return result;
    }

    private static ListResult logged(ListResult result) {
        Level level = result.status() == ListStatus.UNAVAILABLE ? Level.WARNING : Level.FINE;
        if (result instanceof ListConflict conflict) {
            LOGGER.log(level, "会议列表查询完成 status={0} reason={1}",
                    new Object[]{result.status(), conflict.reason()});
        } else if (result instanceof ListPage page) {
            LOGGER.log(level,
                    "会议列表查询完成 status={0} itemCount={1} nextPageTokenPresent={2}",
                    new Object[]{result.status(), page.items().size(),
                            page.nextPageToken() != null});
        } else {
            LOGGER.log(level, "会议列表查询完成 status={0}", result.status());
        }
        return result;
    }

    private static ListResult logged(ListResult result, SQLException failure) {
        Level level = result.status() == ListStatus.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level, "会议列表查询完成 status={0} sqlState={1}",
                new Object[]{result.status(), JdbcReadLookupSupport.safeSqlState(failure)});
        return result;
    }

    private static void closeQuietly(Connection connection, String operation) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "{0}关闭连接失败", operation);
        }
    }

    private record DetailRow(
            MeetingAggregate.MeetingSnapshot snapshot,
            MeetingStatus status) {
        private DetailRow {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(status, "status");
        }
    }

    private record ListRow(MeetingSummary summary, Instant createdAt) {
        private ListRow {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** 内部损坏标记不携带数据库值、列名、主体或 receipt 正文。 */
    private static final class CorruptMeetingReadModel extends RuntimeException {
        private CorruptMeetingReadModel() {
            super("stored meeting read model is invalid", null, false, false);
        }
    }
}
