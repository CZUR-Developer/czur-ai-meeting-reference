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
 * 基于同一会议事实源的认证事件补拉适配器。
 *
 * <p>MySQL5.7 在一个只读 REPEATABLE READ 事务内读取会议和事件。会议存在性、原始归属、会议与 Manifest 删除状态、整场事件完整性、
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
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            Result result = readMysqlSnapshot(connection, meetingId, afterSequence, limit,
                    deploymentScope, subjectScope);
            connection.rollback();
            return logged(result, afterSequence);
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
     * 5.7 读取使用与共享解码器相同的访问守卫、EventLog 解码及页值对象。
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
