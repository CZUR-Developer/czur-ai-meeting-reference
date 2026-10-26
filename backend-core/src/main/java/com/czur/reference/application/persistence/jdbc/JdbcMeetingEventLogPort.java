package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.MeetingEventPage;
import com.czur.reference.application.port.RealtimeEventLogPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 PG／MySQL 同一会议事实源的认证事件补拉适配器。
 *
 * <p>PG 一次调用只执行一条 SELECT；MySQL5.7 在一个只读 REPEATABLE READ 事务内
 * 读取会议和事件。会议存在性、原始归属、会议与 Manifest 删除状态、整场事件完整性、
 * 最高已提交事件序号和 {@code afterSequence} 之后的事件页都来自同一快照，
 * 不会出现先鉴权、后查询时读到两份数据库状态的竞态。结果行到达后
 * 必须先用 {@link MeetingAccessGuard} 判断原始归属；跨部署、跨主体和历史
 * {@code LEGACY_UNOWNED} 会议立即返回 {@link AccessDenied}，不会读取事件正文或
 * 暴露该行是否包含损坏事件。已归属会议的 {@code state} 或
 * {@code manifest_state} 任一进入 {@code DELETION_PENDING}/{@code DELETED}，
 * 就不再允许读取历史事件；删除撤权优先于坏事件与超前游标的诊断。</p>
 *
 * <p>事件查询按会议全局 {@code event_sequence} 升序读取 {@code limit + 1} 条，
 * 不按 {@code stream_id} 过滤。额外一条只用于计算 {@code hasMore}；空页保留调用
 * 方的 {@code afterSequence}。所有者会议只要包含无法证明 envelope 的历史事件、
 * 任一无法构造 {@link EventLog} 的必需字段，或 canonical JSON 已损坏/与原生 JSON
 * 事实不一致，就关闭式返回不可用，不返回部分页面；这不代表历史已按保留策略
 * 截断。只有游标高于最高已提交事件序号才返回
 * {@link ConflictReason#INVALID_CURSOR}。当前参考实现保留全量事件，不主动返回
 * {@link ConflictReason#EVENT_REPLAY_WINDOW_EXPIRED}。</p>
 *
 * <p>日志只记录稳定状态、分页计数、游标和白名单 SQLState，不记录会议标识、
 * 部署范围、主体、事件正文、Token、receipt、数据库地址或异常正文。</p>
 */
public final class JdbcMeetingEventLogPort implements MeetingEventLogPort, RealtimeEventLogPort {
    private static final Logger LOGGER = Logger.getLogger(JdbcMeetingEventLogPort.class.getName());

    /**
     * MATERIALIZED 单行 CTE 强制整场异常扫描每次请求只执行一次；随后
     * LEFT JOIN LATERAL 让“存在但无新事件”的会议仍返回一行。归属、两处删除状态、整场异常
     * 标记和分页事件仍由同一个 PostgreSQL statement snapshot 解释。
     *
     * <p>PostgreSQL 的 POSIX {@code [:space:]} 与 Java 21
     * {@link String#isBlank()} 基本一致，但缺少 U+001C～U+001F 四个信息分隔符。
     * 每个必填文本先用 {@code translate} 删除这四个字符，再检查剩余字符是否
     * 全属于 POSIX whitespace，等价覆盖二者并集；这种写法也能正确处理 TAB 与
     * U+001C 混合的字符串。</p>
     */
    private static final String READ_SQL = """
            WITH meeting_snapshot AS MATERIALIZED (
                SELECT m.meeting_id,
                       m.deployment_scope,
                       m.subject_scope,
                       m.state,
                       m.manifest_state,
                       COALESCE((
                           SELECT MAX(committed.event_sequence)
                             FROM meeting_event committed
                            WHERE committed.meeting_id = m.meeting_id
                       ), 0) AS max_event_sequence,
                       EXISTS (
                           SELECT 1
                             FROM meeting_event historical
                            WHERE historical.meeting_id = m.meeting_id
                              AND (
                                  historical.meeting_id IS NULL
                                  OR translate(
                                         historical.meeting_id,
                                         chr(28) || chr(29) || chr(30) || chr(31), '')
                                     !~ '[^[:space:]]'
                                  OR historical.event_sequence IS NULL
                                  OR historical.event_sequence <= 0
                                  OR historical.event_id IS NULL
                                  OR translate(
                                         historical.event_id,
                                         chr(28) || chr(29) || chr(30) || chr(31), '')
                                     !~ '[^[:space:]]'
                                  OR historical.event_type IS NULL
                                  OR translate(
                                         historical.event_type,
                                         chr(28) || chr(29) || chr(30) || chr(31), '')
                                     !~ '[^[:space:]]'
                                  OR historical.occurred_at IS NULL
                                  OR historical.spec_version IS NULL
                                  OR translate(
                                         historical.spec_version,
                                         chr(28) || chr(29) || chr(30) || chr(31), '')
                                     !~ '[^[:space:]]'
                                  OR historical.stream_id IS NULL
                                  OR translate(
                                         historical.stream_id,
                                         chr(28) || chr(29) || chr(30) || chr(31), '')
                                     !~ '[^[:space:]]'
                                  OR CASE
                                      WHEN historical.canonical_payload IS NULL
                                           OR translate(
                                                  historical.canonical_payload,
                                                  chr(28) || chr(29) || chr(30) || chr(31), '')
                                              !~ '[^[:space:]]'
                                          THEN TRUE
                                      WHEN NOT pg_input_is_valid(
                                              historical.canonical_payload, 'jsonb')
                                          THEN TRUE
                                      ELSE historical.canonical_payload::jsonb
                                           IS DISTINCT FROM historical.payload
                                  END
                              )
                       ) AS has_invalid_event
                  FROM meeting m
                 WHERE m.meeting_id = ?
            )
            SELECT m.deployment_scope,
                   m.subject_scope,
                   m.state,
                   m.manifest_state,
                   m.has_invalid_event,
                   m.max_event_sequence,
                   event_page.meeting_id,
                   event_page.event_sequence,
                   event_page.event_id,
                   event_page.event_type,
                   event_page.canonical_payload,
                   event_page.occurred_at,
                   event_page.spec_version,
                   event_page.stream_id,
                   event_page.event_sequence IS NOT NULL AS event_present
              FROM meeting_snapshot m
              LEFT JOIN LATERAL (
                  SELECT event.meeting_id,
                         event.event_sequence,
                         event.event_id,
                         event.event_type,
                         event.canonical_payload,
                         event.occurred_at,
                         event.spec_version,
                         event.stream_id
                    FROM meeting_event event
                   WHERE event.meeting_id = m.meeting_id
                     AND event.event_sequence > ?
                   ORDER BY event.event_sequence ASC
                   LIMIT ?
              ) event_page ON TRUE
             ORDER BY event_page.event_sequence ASC
            """;

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    /**
     * 创建认证事件补拉适配器。
     *
     * @param dataSource 由参考后端数据库运行时统一管理的连接池
     */
    public JdbcMeetingEventLogPort(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.MYSQL);
    }

    /** 复用唯一池；5.7 无 CTE/LATERAL 时以一个只读 RR 事务保持同一认证事件快照。 */
    public JdbcMeetingEventLogPort(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcMeetingEventLogPort(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * 按认证主体和会议全局游标读取一页事件。
     *
     * <p>端口输入已经完成结构校验；本方法只负责会议归属和持久化事实解释。
     * 调用方仍应在进入端口前校验当前身份是否具备会议读取权限。</p>
     */
    @Override
    public Result read(MeetingEventLogPort.Request request) {
        Objects.requireNonNull(request, "request");
        return readOwned(request.meetingId(), request.afterSequence(), request.limit(),
                request.context().deploymentScope(),
                request.context().identity().subject().subjectScope());
    }

    /** 实时凭据已在当前消息前核验；只用其中绑定的会议和归属读同一事件页。 */
    @Override
    public Result read(RealtimeEventLogPort.Request request) {
        Objects.requireNonNull(request, "request");
        var ownership = request.grant().readOwnership();
        return readOwned(ownership.meetingId(), request.afterSequence(), request.limit(),
                ownership.deploymentScope(), ownership.subjectScope());
    }

    private Result readOwned(String meetingId, long afterSequence, int limit,
            String deploymentScope, String subjectScope) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (engine == JdbcDatabaseEngine.MYSQL) {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                Result result = readMysqlSnapshot(connection, meetingId, afterSequence, limit,
                        deploymentScope, subjectScope);
                connection.rollback();
                return logged(result, afterSequence);
            }
            return logged(readSnapshot(connection, meetingId, afterSequence, limit,
                    deploymentScope, subjectScope), afterSequence);
        } catch (JdbcMeetingEventLogStore.InvalidStoredEvent
                 | CorruptEventPage failure) {
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false),
                    afterSequence);
        } catch (SQLException failure) {
            return fromSqlFailure(failure, afterSequence);
        } catch (RuntimeException failure) {
            // DataSource、JDBC 代理或日志 Provider 的无类型运行时故障不能携带
            // 动态正文进入结果，也不能无依据承诺重试一定成功。
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false),
                    afterSequence);
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 5.7 读取与 PG 使用同一守卫、EventLog 解码及页值对象。
     * 先读归属／删除状态，后在同一 RR 快照扫描整场完整性；只保留本页 limit+1 条。
     * 不使用锁定读，不把历史坏事件隐藏在游标之前，也不另建事件事实源。
     */
    private Result readMysqlSnapshot(Connection connection, String meetingId, long afterSequence,
            int limit, String deploymentScope, String subjectScope) throws SQLException {
        try (PreparedStatement header = connection.prepareStatement(
                "SELECT deployment_scope, subject_scope, state, manifest_state FROM "
                        + JdbcDatabaseTable.MEETING.physicalName(engine) + " WHERE meeting_id=?")) {
            JdbcValues.setText(header, 1, engine, meetingId);
            try (ResultSet rows = header.executeQuery()) {
                if (!rows.next()) return new NotFound();
                if (MeetingAccessGuard.evaluate(deploymentScope, subjectScope,
                        JdbcValues.text(rows, "deployment_scope"), JdbcValues.text(rows, "subject_scope"))
                        != MeetingAccessGuard.Decision.ALLOWED) return new AccessDenied();
                String state = JdbcValues.text(rows, "state");
                String manifest = JdbcValues.text(rows, "manifest_state");
                if ("DELETION_PENDING".equals(state) || "DELETED".equals(state)
                        || "DELETION_PENDING".equals(manifest) || "DELETED".equals(manifest)) {
                    return new AccessDenied();
                }
            }
        }
        int queryLimit = Math.addExact(limit, 1);
        List<EventLog> events = new ArrayList<>(queryLimit);
        long maxSequence = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT meeting_id, event_sequence, event_id, event_type, canonical_payload,
                       occurred_at, spec_version, stream_id,
                       CASE WHEN JSON_VALID(canonical_payload)
                            THEN payload=CAST(canonical_payload AS JSON) ELSE FALSE END AS payload_matches
                  FROM %s WHERE meeting_id=? ORDER BY event_sequence
                """.formatted(JdbcDatabaseTable.MEETING_EVENT.physicalName(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!rows.getBoolean("payload_matches")) {
                        return new Unavailable(UnavailableReason.STORAGE_FAILURE, false);
                    }
                    EventLog event = JdbcMeetingEventLogStore.readEvent(rows);
                    maxSequence = event.eventSequence();
                    if (maxSequence > afterSequence && events.size() < queryLimit) events.add(event);
                }
            }
        }
        if (afterSequence > maxSequence) return new Conflict(ConflictReason.INVALID_CURSOR);
        boolean hasMore = events.size() > limit;
        List<EventLog> page = hasMore ? events.subList(0, limit) : events;
        return new Page(page.isEmpty() ? MeetingEventPage.empty(afterSequence)
                : new MeetingEventPage(page, page.getLast().eventSequence(), hasMore));
    }

    /**
     * 执行唯一的数据库语句并按安全顺序解释 ResultSet。
     *
     * <p>即使 SQL 已经返回事件列，也必须先读取两个原始 scope 并调用访问守卫；
     * 守卫放行后先判断删除撤权，再解释坏事实、游标和事件正文。</p>
     */
    private static Result readSnapshot(Connection connection, String meetingId,
            long afterSequence, int limit, String deploymentScope, String subjectScope)
            throws SQLException {
        int queryLimit = Math.addExact(limit, 1);
        try (PreparedStatement statement = connection.prepareStatement(READ_SQL)) {
            statement.setString(1, meetingId);
            statement.setLong(2, afterSequence);
            statement.setInt(3, queryLimit);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new NotFound();
                }

                String storedDeploymentScope = rows.getString("deployment_scope");
                String storedSubjectScope = rows.getString("subject_scope");
                if (MeetingAccessGuard.evaluate(deploymentScope, subjectScope,
                        storedDeploymentScope, storedSubjectScope)
                        != MeetingAccessGuard.Decision.ALLOWED) {
                    return new AccessDenied();
                }
                String state = rows.getString("state");
                String manifestState = rows.getString("manifest_state");
                if ("DELETION_PENDING".equals(state) || "DELETED".equals(state)
                        || "DELETION_PENDING".equals(manifestState)
                        || "DELETED".equals(manifestState)) {
                    return new AccessDenied();
                }

                if (rows.getBoolean("has_invalid_event")) {
                    return new Unavailable(UnavailableReason.STORAGE_FAILURE, false);
                }
                if (afterSequence > rows.getLong("max_event_sequence")) {
                    return new Conflict(ConflictReason.INVALID_CURSOR);
                }

                List<EventLog> events = new ArrayList<>(queryLimit);
                do {
                    if (rows.getBoolean("event_present")) {
                        events.add(JdbcMeetingEventLogStore.readEvent(rows));
                    }
                } while (rows.next());

                boolean hasMore = events.size() > limit;
                int returnedCount = hasMore ? limit : events.size();
                List<EventLog> returnedEvents = new ArrayList<>(
                        events.subList(0, returnedCount));
                MeetingEventPage page;
                try {
                    page = returnedEvents.isEmpty()
                            ? MeetingEventPage.empty(afterSequence)
                            : new MeetingEventPage(
                                    returnedEvents,
                                    returnedEvents.getLast().eventSequence(),
                                    hasMore);
                } catch (IllegalArgumentException | NullPointerException failure) {
                    throw new CorruptEventPage();
                }
                return new Page(page);
            }
        }
    }

    private static Result fromSqlFailure(SQLException failure, long afterSequence) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        if (kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT) {
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false),
                    afterSequence, failure);
        }
        boolean retryable = kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE;
        UnavailableReason reason = retryable
                ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                : UnavailableReason.STORAGE_FAILURE;
        return logged(new Unavailable(reason, retryable), afterSequence, failure);
    }

    private static Result logged(Result result, long afterSequence) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        if (result instanceof Page page) {
            MeetingEventPage value = page.page();
            LOGGER.log(level,
                    "认证事件补拉完成 status={0} itemCount={1} afterSequence={2} "
                            + "lastSequence={3} hasMore={4}",
                    new Object[]{result.status(), value.items().size(), afterSequence,
                            value.lastSequence(), value.hasMore()});
        } else if (result instanceof Conflict conflict) {
            LOGGER.log(level, "认证事件补拉完成 status={0} reason={1} afterSequence={2}",
                    new Object[]{result.status(), conflict.reason(), afterSequence});
        } else {
            LOGGER.log(level, "认证事件补拉完成 status={0} afterSequence={1}",
                    new Object[]{result.status(), afterSequence});
        }
        return result;
    }

    private static Result logged(Result result, long afterSequence, SQLException failure) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level,
                "认证事件补拉完成 status={0} afterSequence={1} sqlState={2}",
                new Object[]{result.status(), afterSequence,
                        JdbcReadLookupSupport.safeSqlState(failure)});
        return result;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            // close 异常正文可能包含连接地址或驱动配置，只记录稳定操作名。
            LOGGER.log(Level.WARNING, "认证事件补拉关闭连接失败");
        }
    }

    /** 不携带事件、列名或异常正文的页不一致标记。 */
    private static final class CorruptEventPage extends RuntimeException {
        private CorruptEventPage() {
            super("stored meeting event page is invalid", null, false, false);
        }
    }
}
