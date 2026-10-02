package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.MeetingEventPage;

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
 * 基于 PostgreSQL 会议事实源的认证事件补拉适配器。
 *
 * <p>一次调用只执行一条 SELECT。会议存在性、原始归属、会议与 Manifest 删除状态、整场事件完整性、
 * 最高已提交事件序号和 {@code afterSequence} 之后的事件页因此都来自同一个 statement
 * snapshot，不会出现先鉴权、后查询时读到两份数据库状态的竞态。结果行到达后
 * 必须先用 {@link MeetingAccessGuard} 判断原始归属；跨部署、跨主体和历史
 * {@code LEGACY_UNOWNED} 会议立即返回 {@link AccessDenied}，不会读取事件正文或
 * 暴露该行是否包含损坏事件。已归属会议的 {@code state} 或
 * {@code manifest_state} 任一进入 {@code DELETION_PENDING}/{@code DELETED}，
 * 就不再允许读取历史事件；删除撤权优先于坏事件与超前游标的诊断。</p>
 *
 * <p>事件查询按会议全局 {@code event_sequence} 升序读取 {@code limit + 1} 条，
 * 不按 {@code stream_id} 过滤。额外一条只用于计算 {@code hasMore}；空页保留调用
 * 方的 {@code afterSequence}。所有者会议只要包含无法证明 envelope 的历史事件、
 * 任一无法构造 {@link EventLog} 的必需字段，或 canonical JSON 已损坏/与 JSONB
 * 事实不一致，就关闭式返回不可用，不返回部分页面；这不代表历史已按保留策略
 * 截断。只有游标高于最高已提交事件序号才返回
 * {@link ConflictReason#INVALID_CURSOR}。当前参考实现保留全量事件，不主动返回
 * {@link ConflictReason#EVENT_REPLAY_WINDOW_EXPIRED}。</p>
 *
 * <p>日志只记录稳定状态、分页计数、游标和白名单 SQLState，不记录会议标识、
 * 部署范围、主体、事件正文、Token、receipt、数据库地址或异常正文。</p>
 */
public final class JdbcMeetingEventLogPort implements MeetingEventLogPort {
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

    /**
     * 创建认证事件补拉适配器。
     *
     * @param dataSource 由参考后端数据库运行时统一管理的连接池
     */
    public JdbcMeetingEventLogPort(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 按认证主体和会议全局游标读取一页事件。
     *
     * <p>端口输入已经完成结构校验；本方法只负责会议归属和持久化事实解释。
     * 调用方仍应在进入端口前校验当前身份是否具备会议读取权限。</p>
     */
    @Override
    public Result read(Request request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            return logged(readSnapshot(connection, request), request.afterSequence());
        } catch (JdbcMeetingEventLogStore.InvalidStoredEvent
                 | CorruptEventPage failure) {
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false),
                    request.afterSequence());
        } catch (SQLException failure) {
            return fromSqlFailure(failure, request.afterSequence());
        } catch (RuntimeException failure) {
            // DataSource、JDBC 代理或日志 Provider 的无类型运行时故障不能携带
            // 动态正文进入结果，也不能无依据承诺重试一定成功。
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false),
                    request.afterSequence());
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 执行唯一的数据库语句并按安全顺序解释 ResultSet。
     *
     * <p>即使 SQL 已经返回事件列，也必须先读取两个原始 scope 并调用访问守卫；
     * 守卫放行后先判断删除撤权，再解释坏事实、游标和事件正文。</p>
     */
    private static Result readSnapshot(Connection connection, Request request)
            throws SQLException {
        int queryLimit = Math.addExact(request.limit(), 1);
        try (PreparedStatement statement = connection.prepareStatement(READ_SQL)) {
            statement.setString(1, request.meetingId());
            statement.setLong(2, request.afterSequence());
            statement.setInt(3, queryLimit);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new NotFound();
                }

                String storedDeploymentScope = rows.getString("deployment_scope");
                String storedSubjectScope = rows.getString("subject_scope");
                if (MeetingAccessGuard.evaluate(
                        request.context(), storedDeploymentScope, storedSubjectScope)
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
                if (request.afterSequence() > rows.getLong("max_event_sequence")) {
                    return new Conflict(ConflictReason.INVALID_CURSOR);
                }

                List<EventLog> events = new ArrayList<>(queryLimit);
                do {
                    if (rows.getBoolean("event_present")) {
                        events.add(JdbcMeetingEventLogStore.readEvent(rows));
                    }
                } while (rows.next());

                boolean hasMore = events.size() > request.limit();
                int returnedCount = hasMore ? request.limit() : events.size();
                List<EventLog> returnedEvents = new ArrayList<>(
                        events.subList(0, returnedCount));
                MeetingEventPage page;
                try {
                    page = returnedEvents.isEmpty()
                            ? MeetingEventPage.empty(request.afterSequence())
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
