package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.MeetingEventPage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于公共 JDBC 固定会议表的规范事件日志适配器。
 *
 * <p>该类只实现 {@link EventLogStore} 的持久化原语，不负责主体鉴权、会议
 * 访问控制、实时推送或事件业务编排。公开方法每次使用一个独立的短事务；未来
 * 如果会议命令需要把事件和会议快照放入同一个事务，可以在同一连接上调用包
 * 可见的 {@link #append(Connection, EventLog)}，但连接的提交、回滚和生命周期
 * 仍由外层工作单元负责。</p>
 *
 * <p>追加前会锁定目标会议行。这样同一会议的多个追加者会在数据库中串行观察
 * 当前最大序号，并且只有恰好为“最大序号加一”的事件可以写入。事件标识在表
 * 内全局唯一；相同标识且完整 envelope（包括 canonical JSON 原文）一致时返回
 * {@link AppendStatus#REPLAYED}，任意不可变字段不同都返回
 * {@link AppendStatus#CONFLICT}，原记录永远不会被覆盖。数据库同时保存原始
 * {@code canonical_payload} 和可查询的 JSONB {@code payload}，V004 约束保证
 * 两者逻辑一致；重放和对外读取只使用前者，因此与内存适配器保持字符串精确
 * 相等语义。</p>
 *
 * <p>V004 之前的历史事件没有可靠的协议版本和逻辑流来源。读取时只要发现
 * 任一 envelope 元数据为空，就立即失败关闭，不猜测或拼接默认值；这样不会把
 * 无法证明归属的旧事件伪装成新的规范事件。</p>
 */
public final class JdbcMeetingEventLogStore implements EventLogStore {
    private static final Logger LOGGER = Logger.getLogger(JdbcMeetingEventLogStore.class.getName());

    private static final String FIND_BY_EVENT_ID_SQL = """
            SELECT meeting_id, event_sequence, event_id, event_type, canonical_payload,
                   occurred_at, spec_version, stream_id
              FROM %s
             WHERE event_id = ?
            """;

    /** 会议行锁是同一会议序号递增的串行化边界。 */
    private static final String LOCK_MEETING_SQL = """
            SELECT meeting_id
              FROM %s
             WHERE meeting_id = ?
             FOR UPDATE
            """;

    private static final String MAX_SEQUENCE_SQL = """
            SELECT COALESCE(MAX(event_sequence), 0)
              FROM %s
             WHERE meeting_id = ?
            """;

    /**
     * ON CONFLICT 不更新旧事件。并发请求如果同时使用相同 eventId，失败的一方
     * 会在唯一键冲突后读取已经提交的原记录并按完整 envelope 分类。
     */
    private static final String INSERT_SQL = """
            INSERT INTO %s (
                meeting_id, event_sequence, event_id, event_type, payload,
                canonical_payload, occurred_at, spec_version, stream_id)
            VALUES (?, ?, ?, ?, %s, ?, ?, ?, ?)
            %s
            """;

    private static final String HISTORICAL_ENVELOPE_SQL = """
            SELECT 1
             FROM %s
             WHERE meeting_id = ?
               AND (spec_version IS NULL OR stream_id IS NULL OR canonical_payload IS NULL)
             LIMIT 1
            """;

    private static final String PAGE_SQL = """
            SELECT meeting_id, event_sequence, event_id, event_type, canonical_payload,
                   occurred_at, spec_version, stream_id
              FROM %s
             WHERE meeting_id = ?
               AND event_sequence > ?
             ORDER BY event_sequence ASC
             LIMIT ?
            """;

    /** 使用 V004 保存的原始 canonical 文本精确比较完整不可变 envelope。 */
    private static final String SAME_ENVELOPE_SQL = """
            SELECT meeting_id = ?
               AND event_sequence = ?
               AND event_type = ?
               AND canonical_payload = ?
               AND occurred_at = ?
               AND spec_version = ?
               AND stream_id = ? AS same_envelope
              FROM meeting_event
             WHERE event_id = ?
            """;

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    /**
     * 使用已经由运行时装配和生命周期管理的数据库连接池创建事件日志适配器。
     *
     * @param dataSource 会议事实源的数据源；不能为 {@code null}
     */
    public JdbcMeetingEventLogStore(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.MYSQL);
    }

    /** 显式消费唯一连接上下文；标准事件和去重语义仍由原端口定义。 */
    public JdbcMeetingEventLogStore(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcMeetingEventLogStore(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * 在独立短事务中追加事件。
     *
     * @param event 已完成协议校验的事件
     * @return 追加、完整重放或事件标识冲突结果；冲突时返回数据库原记录
     * @throws IllegalArgumentException 序号不是当前会议的下一个序号，或参数非法
     * @throws IllegalStateException 数据库不可用、会议不存在、JSON/历史数据无法安全读取
     */
    @Override
    public AppendResult append(EventLog event) {
        Objects.requireNonNull(event, "event");
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean rollbackConfirmed = true;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;
            AppendResult result = append(connection, engine, event);
            connection.commit();
            transactionStarted = false;
            logAppend(event, result);
            return result;
        } catch (RuntimeException | Error failure) {
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection, failure);
            }
            throw failure;
        } catch (SQLException failure) {
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection, failure);
            }
            throw databaseFailure("追加会议事件失败", failure);
        } finally {
            finishConnection(connection, previousAutoCommit, rollbackConfirmed);
            closeQuietly(connection);
        }
    }

    /**
     * 在调用方已经开启的事务连接上追加事件；不会提交、回滚或关闭连接。
     *
     * <p>该入口用于未来把会议快照、事件日志和 Outbox 放在同一 Unit of Work
     * 中。调用方必须确保连接仍在事务内，并负责处理本方法抛出的异常。</p>
     */
    static AppendResult append(Connection connection, EventLog event) throws SQLException {
        return append(connection, JdbcDatabaseEngine.MYSQL, event);
    }

    /** MySQL 写路径先锁 Meeting 再当前读，重放顺序保持不变。 */
    static AppendResult append(Connection connection, JdbcDatabaseEngine engine, EventLog event)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(event, "event");
        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "connection-scoped event append requires an active transaction");
        }

        // 先处理已存在 eventId 的重放，避免因为调用方重试时序号已经推进而
        // 错误地把本来可以安全重放的请求判成序号错误。
        if (engine == JdbcDatabaseEngine.MYSQL && !lockMeeting(connection, engine, event.meetingId())) {
            throw new IllegalStateException("追加事件的会议不存在");
        }
        Optional<EventLog> existing = findByEventId(connection, engine, event.eventId());
        if (existing.isPresent()) {
            return classifyExisting(connection, engine, event, existing.get());
        }

        // 初次查询与会议行锁之间可能有另一个事务刚提交了相同 eventId。重新
        // 查询是并发重放的必要步骤；否则该请求会把已经成功的重放误判为序号错误。
        existing = findByEventId(connection, engine, event.eventId());
        if (existing.isPresent()) {
            return classifyExisting(connection, engine, event, existing.get());
        }
        long expectedSequence = currentSequence(connection, engine, event.meetingId()) + 1L;
        if (event.eventSequence() != expectedSequence) {
            throw new IllegalArgumentException(
                    "eventSequence must be the next meeting sequence: expected="
                            + expectedSequence + ", actual=" + event.eventSequence());
        }

        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL.formatted(
                JdbcDatabaseTable.MEETING_EVENT.physicalName(engine), JdbcValues.jsonParameter(engine),
                engine == JdbcDatabaseEngine.MYSQL ? "" : "ON CONFLICT (event_id) DO NOTHING"))) {
            bindAppend(statement, engine, event);
            try {
                inserted = statement.executeUpdate();
            } catch (SQLException duplicate) {
                if (!JdbcSqlErrors.isDuplicate(engine, duplicate)
                        || findByEventId(connection, engine, event.eventId()).isEmpty()) throw duplicate;
                inserted = 0;
            }
        }

        // INSERT ... ON CONFLICT 可能是另一个事务刚刚提交了相同 eventId；无论
        // 是否插入成功，都重新读取数据库原记录，确保返回值来自事实源。
        EventLog current = findByEventId(connection, engine, event.eventId())
                .orElseThrow(() -> new IllegalStateException("事件插入后不可见"));
        if (inserted == 1) {
            return new AppendResult(AppendStatus.APPENDED, current);
        }
        return classifyExisting(connection, engine, event, current);
    }

    /**
     * 按会议全局序号游标读取一页事件。
     *
     * <p>查询不接收也不使用 {@code streamId} 条件。事件上的逻辑流标识
     * 仍是必填 envelope 元数据，但 {@code eventSequence} 在整场会议内分配；
     * 对单流过滤会让返回页出现全局序号空洞，破坏 App 的断线补拉。</p>
     *
     * <p>读取前会检查该会议是否存在无法证明 envelope 的历史行。只要
     * 任一行缺失必需元数据，就拒绝返回部分事实。</p>
     */
    @Override
    public MeetingEventPage readPage(String meetingId, long afterSequence, int limit) {
        EventLogStore.validatePage(meetingId, afterSequence, limit);
        int queryLimit = Math.addExact(limit, 1);
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean rollbackConfirmed = true;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            if (engine == JdbcDatabaseEngine.MYSQL) {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            }
            connection.setAutoCommit(false);
            transactionStarted = true;
            if (hasHistoricalEnvelopeGap(connection, engine, meetingId)) {
                throw new IllegalStateException(
                        "会议事件包含无法证明协议版本、逻辑流或 canonical 正文的历史记录");
            }
            List<EventLog> rows = readPageRows(
                    connection, engine, meetingId, afterSequence, queryLimit);
            boolean hasMore = rows.size() > limit;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            MeetingEventPage page = rows.isEmpty()
                    ? MeetingEventPage.empty(afterSequence)
                    : new MeetingEventPage(
                            rows, rows.get(rows.size() - 1).eventSequence(), hasMore);
            connection.commit();
            transactionStarted = false;
            LOGGER.log(Level.FINE,
                    "JDBC 会议全局事件分页读取完成 meetingId={0} itemCount={1} lastSequence={2}",
                    new Object[]{meetingId, page.items().size(), page.lastSequence()});
            return page;
        } catch (RuntimeException | Error failure) {
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection, failure);
            }
            throw failure;
        } catch (SQLException failure) {
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection, failure);
            }
            throw databaseFailure("读取会议事件分页失败", failure);
        } finally {
            finishConnection(connection, previousAutoCommit, rollbackConfirmed);
            closeQuietly(connection);
        }
    }

    static Optional<EventLog> findByEventId(Connection connection, String eventId)
            throws SQLException {
        return findByEventId(connection, JdbcDatabaseEngine.MYSQL, eventId);
    }

    static Optional<EventLog> findByEventId(Connection connection, JdbcDatabaseEngine engine,
            String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_EVENT_ID_SQL.formatted(
                JdbcDatabaseTable.MEETING_EVENT.physicalName(engine))
                + (engine == JdbcDatabaseEngine.MYSQL ? " FOR UPDATE" : ""))) {
            JdbcValues.setText(statement, 1, engine, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readEvent(rows)) : Optional.empty();
            }
        }
    }

    static boolean lockMeeting(Connection connection, String meetingId) throws SQLException {
        return lockMeeting(connection, JdbcDatabaseEngine.MYSQL, meetingId);
    }

    static boolean lockMeeting(Connection connection, JdbcDatabaseEngine engine, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_MEETING_SQL.formatted(
                JdbcDatabaseTable.MEETING.physicalName(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    static long currentSequence(Connection connection, String meetingId) throws SQLException {
        return currentSequence(connection, JdbcDatabaseEngine.MYSQL, meetingId);
    }

    static long currentSequence(Connection connection, JdbcDatabaseEngine engine, String meetingId)
            throws SQLException {
        String table = JdbcDatabaseTable.MEETING_EVENT.physicalName(engine);
        try (PreparedStatement statement = connection.prepareStatement(engine == JdbcDatabaseEngine.MYSQL
                ? "SELECT event_sequence FROM " + table
                        + " WHERE meeting_id=? ORDER BY event_sequence DESC LIMIT 1 FOR UPDATE"
                : MAX_SEQUENCE_SQL.formatted(table))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    if (engine == JdbcDatabaseEngine.MYSQL) return 0;
                    throw new SQLException("无法读取会议当前事件序号");
                }
                return rows.getLong(1);
            }
        }
    }

    private static boolean hasHistoricalEnvelopeGap(Connection connection, JdbcDatabaseEngine engine,
            String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(HISTORICAL_ENVELOPE_SQL.formatted(
                JdbcDatabaseTable.MEETING_EVENT.physicalName(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static List<EventLog> readPageRows(
            Connection connection,
            JdbcDatabaseEngine engine,
            String meetingId,
            long afterSequence,
            int queryLimit) throws SQLException {
        List<EventLog> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PAGE_SQL.formatted(
                JdbcDatabaseTable.MEETING_EVENT.physicalName(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            statement.setLong(2, afterSequence);
            statement.setInt(3, queryLimit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(readEvent(rows));
                }
            }
        }
        return events;
    }

    /**
     * 从当前结果行解码规范事件。
     *
     * <p>包内认证读取适配器必须复用这里的 canonical envelope 映射，避免同一张
     * 表在公开读取和持久化原语中形成两套字段解释。该方法只读取事件列，不读取
     * 或判断会议归属；调用方必须先完成自己的访问控制。</p>
     */
    static EventLog readEvent(ResultSet rows) throws SQLException {
        String specVersion = rows.getString("spec_version");
        String streamId = JdbcValues.text(rows, "stream_id");
        // V001 历史行和任何半迁移行都不能转换成 EventLog；不要用默认值猜测。
        if (specVersion == null || specVersion.isBlank()
                || streamId == null || streamId.isBlank()) {
            throw new InvalidStoredEvent();
        }
        String canonicalPayload = rows.getString("canonical_payload");
        if (canonicalPayload == null || canonicalPayload.isBlank()) {
            throw new InvalidStoredEvent();
        }
        Instant occurredAt = JdbcValues.instant(rows, "occurred_at");
        if (occurredAt == null) {
            throw new InvalidStoredEvent();
        }
        try {
            return new EventLog(
                    specVersion,
                    requiredColumn(rows, "meeting_id"),
                    streamId,
                    requiredColumn(rows, "event_id"),
                    rows.getLong("event_sequence"),
                    occurredAt,
                    requiredColumn(rows, "event_type"),
                    canonicalPayload);
        } catch (IllegalArgumentException | NullPointerException failure) {
            // EventLog 的构造校验不能把数据库原值或列级异常正文带到公开适配器。
            throw new InvalidStoredEvent();
        }
    }

    private static String requiredColumn(ResultSet rows, String column) throws SQLException {
        String value = switch (column) {
            case "meeting_id", "event_id" -> JdbcValues.text(rows, column);
            default -> rows.getString(column);
        };
        if (value == null || value.isBlank()) {
            throw new InvalidStoredEvent();
        }
        return value;
    }

    /** 固定诊断文本的持久化坏事件标记；不得携带列值、正文或驱动异常。 */
    static final class InvalidStoredEvent extends IllegalStateException {
        private InvalidStoredEvent() {
            super("stored meeting event is invalid");
        }
    }

    private static AppendResult classifyExisting(
            Connection connection, JdbcDatabaseEngine engine, EventLog candidate, EventLog existing)
            throws SQLException {
        // MySQL LONGTEXT 的 PAD SPACE 等值不等于原文精确一致；直接比较完整不可变值。
        if (engine == JdbcDatabaseEngine.MYSQL ? existing.equals(candidate)
                : sameEnvelope(connection, candidate)) {
            return new AppendResult(AppendStatus.REPLAYED, existing);
        }
        return new AppendResult(AppendStatus.CONFLICT, existing);
    }

    private static boolean sameEnvelope(Connection connection, EventLog event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SAME_ENVELOPE_SQL)) {
            statement.setString(1, event.meetingId());
            statement.setLong(2, event.eventSequence());
            statement.setString(3, event.eventType());
            statement.setString(4, event.canonicalPayloadJson());
            statement.setTimestamp(5, Timestamp.from(event.emittedAt()));
            statement.setString(6, event.specVersion());
            statement.setString(7, event.streamId());
            statement.setString(8, event.eventId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean("same_envelope");
            }
        }
    }

    private static void bindAppend(PreparedStatement statement, JdbcDatabaseEngine engine,
            EventLog event) throws SQLException {
        JdbcValues.setText(statement, 1, engine, event.meetingId());
        statement.setLong(2, event.eventSequence());
        JdbcValues.setText(statement, 3, engine, event.eventId());
        statement.setString(4, event.eventType());
        statement.setString(5, event.canonicalPayloadJson());
        statement.setString(6, event.canonicalPayloadJson());
        JdbcValues.setInstant(statement, 7, event.emittedAt());
        statement.setString(8, event.specVersion());
        JdbcValues.setText(statement, 9, engine, event.streamId());
    }

    private static void logAppend(EventLog event, AppendResult result) {
        Level level = result.status() == AppendStatus.CONFLICT ? Level.WARNING :
                result.status() == AppendStatus.APPENDED ? Level.INFO : Level.FINE;
        LOGGER.log(level,
                "JDBC 会议事件追加结果 meetingId={0} sequence={1} status={2}",
                new Object[]{event.meetingId(), event.eventSequence(), result.status()});
    }

    private static RuntimeException databaseFailure(String message, SQLException cause) {
        // 数据库异常的 message/detail 可能回显无法解析的 JSON 参数，即会议
        // 正文。公开异常只保留经过白名单校验的 SQLState，不挂载原始 cause。
        IllegalStateException safe = new IllegalStateException(
                message + " sqlState=" + safeSqlState(cause));
        for (Throwable suppressed : cause.getSuppressed()) {
            safe.addSuppressed(new IllegalStateException(
                    "事件日志数据库清理失败 sqlState=" + safeSqlState(suppressed)));
        }
        return safe;
    }

    /**
     * 尝试确认回滚。返回 {@code false} 时连接中可能仍有未决事务，调用方绝不能
     * 恢复 {@code autoCommit=true}，否则部分 JDBC 驱动会把状态恢复解释为提交。
     */
    private static boolean rollbackQuietly(Connection connection, Throwable originalFailure) {
        if (connection == null) {
            return true;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException rollbackFailure) {
            // 只把安全摘要挂到原异常；禁止原始 SQLException 通过 suppressed
            // 进入 API 日志、测试报告或 HTTP 错误映射。
            originalFailure.addSuppressed(new IllegalStateException(
                    "事件日志事务回滚失败 sqlState=" + safeSqlState(rollbackFailure)));
            LOGGER.log(Level.WARNING, "事件日志事务回滚失败 sqlState={0}",
                    safeSqlState(rollbackFailure));
            return false;
        }
    }

    private static void finishConnection(
            Connection connection, boolean previousAutoCommit, boolean rollbackConfirmed) {
        if (connection == null) {
            return;
        }
        if (rollbackConfirmed) {
            restoreAutoCommit(connection, previousAutoCommit);
            return;
        }
        // rollback 未确认时必须废弃物理连接。abort 的直接执行器不会创建线程；
        // 对连接池代理也会使底层连接失效，随后 close 只负责归还/清理代理。
        abortQuietly(connection);
    }

    private static void restoreAutoCommit(Connection connection, boolean previousAutoCommit) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "恢复事件日志连接自动提交状态失败 sqlState={0}",
                    safeSqlState(failure));
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "关闭事件日志数据库连接失败 sqlState={0}",
                    safeSqlState(failure));
        }
    }

    private static void abortQuietly(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "废弃事件日志数据库连接失败 sqlState={0}",
                    safeSqlState(failure));
        }
    }

    /** SQLState 只能是五位大写字母或数字；其他驱动文本统一折叠为 UNKNOWN。 */
    private static String safeSqlState(Throwable failure) {
        String state = failure instanceof SQLException sqlFailure
                ? sqlFailure.getSQLState() : null;
        return state != null && state.matches("[0-9A-Z]{5}") ? state : "UNKNOWN";
    }
}
