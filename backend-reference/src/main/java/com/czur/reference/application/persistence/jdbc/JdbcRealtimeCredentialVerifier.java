package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.realtime.RealtimeAccessPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从一个 PostgreSQL 一致性快照核验实时连接凭据。
 *
 * <p>部署范围由服务端装配注入，绝不从连接请求取得。读取顺序刻意从会议行原始
 * 部署和主体开始：异部署或旧版无主会议统一拒绝，在此前不解码 V013/V014 或状态，
 * 避免泄露别的部署的事实健康状况。通过归属门后，仍要严格核对 V013 首次创建
 * 事实与会议行的部署、主体、会话、创建时间和逻辑流；V014 只提供额外的有效
 * Token，不覆盖首次响应。所有数据库读取在单个显式只读 REPEATABLE_READ 事务
 * 中完成，不使用进程分配器缓存、行锁、HTTP 或 WSS 对象。</p>
 *
 * <p>返回的授权仅适用于当前核验时刻。连接持有者须在每次写入前重检过期和持久
 * 写入状态；本类不提供可永久缓存的会话许可。日志只有固定结果/故障分类，绝不
 * 包含原始 Token、摘要、Grant JSON、主体或会议 ID。</p>
 */
public final class JdbcRealtimeCredentialVerifier implements RealtimeCredentialVerifier {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcRealtimeCredentialVerifier.class.getName());
    private static final String PIN_SEARCH_PATH_SQL = """
            SELECT set_config('search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;
    private static final String MEETING_SQL = """
            SELECT deployment_scope, subject_scope, client_session_id, profile_id,
                   profile_revision, created_at,
                   state, input_state
              FROM meeting
             WHERE meeting_id = ?
            """;
    private static final String ACTIVE_GRANTS_SQL = """
            SELECT idempotency_key, grant_json, grant_sha256, token_sha256,
                   issued_at, expires_at
              FROM meeting_realtime_token_grant
             WHERE meeting_id = ? AND expires_at > ?
             ORDER BY issued_at, idempotency_key
            """;

    private final DataSource dataSource;
    private final String deploymentScope;
    private final Clock clock;

    /**
     * @param dataSource 唯一持久事实库连接源；调用者负责其生命周期
     * @param deploymentScope 服务端可信部署配置，不能取自客户端或 Token
     * @param clock 服务端时钟，用于独占过期截止判定
     */
    public JdbcRealtimeCredentialVerifier(
            DataSource dataSource, String deploymentScope, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope is required");
        }
        this.deploymentScope = deploymentScope;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 只读核验；任一事务建立、读取、回滚或关闭故障均不能放行。 */
    @Override
    public Result verify(Request request) {
        Objects.requireNonNull(request, "request");
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            Result result = inspect(connection, request);
            // 只读事务也显式结束；若 rollback 失败，不能把先前的 Granted 返回。
            connection.rollback();
            LOGGER.log(Level.FINE, "实时凭据核验 result={0}", result.getClass().getSimpleName());
            return result;
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            if (failure.kind() == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA) {
                return factInconsistent();
            }
            return unavailable();
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "实时凭据核验存储故障 sqlState={0}", failure.getSQLState());
            return new Unavailable(true);
        } catch (InvalidFact failure) {
            return factInconsistent();
        } catch (JdbcRealtimeTokenGrantFact.CorruptFact failure) {
            return factInconsistent();
        } catch (RuntimeException failure) {
            // 已通过参数校验的同步读取中出现异常，不能回退到缓存或猜测状态。
            LOGGER.log(Level.WARNING, "实时凭据核验运行故障 category=INTERNAL");
            return new Unavailable(false);
        }
    }

    private Result inspect(Connection connection, Request request) throws SQLException {
        pinSearchPath(connection);
        Optional<MeetingFact> rawMeeting = readMeeting(connection, request.meetingId());
        if (rawMeeting.isEmpty()) {
            return new Denied();
        }
        MeetingFact meeting = rawMeeting.orElseThrow();
        if (!deploymentScope.equals(meeting.deploymentScope())
                || meeting.subjectScope() == null
                || meeting.subjectScope().isBlank()
                || MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(meeting.subjectScope())) {
            return new Denied();
        }
        if (!request.meetingId().equals(request.streamId())) {
            return new Denied();
        }
        // 原始归属门在状态解析和 V013/V014 读取之前。V013 Store 复用现有严格
        // JSON/摘要校验，但使用其专门的无锁只读入口。
        CreateMeetingFactStore.Record first = new JdbcCreateMeetingFactStore(connection)
                .findByMeetingIdReadOnly(request.meetingId()).orElse(null);
        if (first == null) {
            return new Denied(); // 旧 v1 会话没有可供此入口信任的 V013 事实。
        }
        requireFact(IdempotencyScopeCodec.meetingCreateV2(
                meeting.deploymentScope(), meeting.subjectScope()).equals(first.scope()));
        requireFact(first.response().meetingId().equals(request.meetingId())
                && first.request().requestSnapshot().clientSessionId()
                        .equals(meeting.clientSessionId())
                && first.response().acceptedProfile().id().equals(meeting.profileId())
                && first.response().acceptedProfile().revision() == meeting.profileRevision()
                && first.createdAt().equals(meeting.createdAt()));
        requireFact(first.request().requestSnapshot().audioProfile()
                .equals(first.response().acceptedAudioProfile()));
        AudioContract audioContract;
        try {
            audioContract = new AudioContract(first.response().acceptedAudioProfile(),
                    first.response().limits().maxAudioFrameBytes());
        } catch (IllegalArgumentException malformed) {
            throw new InvalidFact();
        }
        MeetingState state = parseState(meeting.state());
        InputState inputState = parseInputState(meeting.inputState());

        Instant now = Objects.requireNonNull(clock.instant(), "clock.instant()");
        byte[] requestedDigest = request.credential().useBytes(
                JdbcRealtimeCredentialVerifier::sha256);
        try {
            Instant matchedExpiry = null;
            var initial = first.response().realtime();
            byte[] initialDigest = sha256(initial.sessionToken().getBytes(StandardCharsets.UTF_8));
            try {
                if (MessageDigest.isEqual(requestedDigest, initialDigest)) {
                    matchedExpiry = initial.expiresAt();
                }
            } finally {
                Arrays.fill(initialDigest, (byte) 0);
            }
            // 不能命中首次 Token 后提前退出；一个活动续期事实损坏也必须关闭。
            matchedExpiry = readActiveGrants(
                    connection, request.meetingId(), now, requestedDigest, matchedExpiry);
            if (matchedExpiry == null || !now.isBefore(matchedExpiry)
                    || !clock.instant().isBefore(matchedExpiry)) {
                return new Denied();
            }
            return switch (RealtimeAccessPolicy.classify(state, inputState)) {
                case WRITE -> new Granted(AccessMode.WRITE, matchedExpiry, audioContract);
                case READ_ONLY -> new Granted(AccessMode.READ_ONLY, matchedExpiry, audioContract);
                case DENY -> new Denied();
                case FACT_INCONSISTENT -> factInconsistent();
            };
        } finally {
            Arrays.fill(requestedDigest, (byte) 0);
        }
    }

    private Optional<MeetingFact> readMeeting(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MEETING_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String scope = rows.getString("deployment_scope");
                String subject = rows.getString("subject_scope");
                // 跨部署和历史无主行只读取原始归属，不解释其它列；就算那些列
                // 损坏，也不能把健康状态泄露给本部署的连接请求。
                boolean visible = deploymentScope.equals(scope) && subject != null
                        && !subject.isBlank()
                        && !MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(subject);
                Timestamp created = visible ? rows.getTimestamp("created_at") : null;
                MeetingFact fact = new MeetingFact(scope, subject,
                        visible ? rows.getString("client_session_id") : null,
                        visible ? rows.getString("profile_id") : null,
                        visible ? rows.getInt("profile_revision") : 0,
                        created == null ? null : created.toInstant(),
                        visible ? rows.getString("state") : null,
                        visible ? rows.getString("input_state") : null);
                if (rows.next()) throw new InvalidFact();
                return Optional.of(fact);
            }
        }
    }

    private static Instant readActiveGrants(Connection connection, String meetingId,
            Instant now, byte[] requestedDigest, Instant matchedExpiry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_GRANTS_SQL)) {
            statement.setString(1, meetingId);
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    var grant = JdbcRealtimeTokenGrantFact.read(rows, meetingId, null);
                    if (grant.matchesTokenDigest(requestedDigest)) {
                        // V014 的唯一约束无法覆盖 V013 首次 Token；两个不同的
                        // Grant 若声称同一凭据，不能任意选择较晚期限。
                        requireFact(matchedExpiry == null);
                        matchedExpiry = grant.expiresAt();
                    }
                }
            }
        }
        return matchedExpiry;
    }

    private static MeetingState parseState(String value) {
        try {
            return MeetingState.valueOf(value);
        } catch (RuntimeException malformed) {
            throw new InvalidFact();
        }
    }

    private static InputState parseInputState(String value) {
        try {
            return InputState.valueOf(value);
        } catch (RuntimeException malformed) {
            throw new InvalidFact();
        }
    }

    private static void pinSearchPath(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_schema()");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("deployment schema is unavailable");
            String schema = rows.getString(1);
            if (schema == null || schema.startsWith("pg_temp")) {
                throw new SQLException("deployment schema is unavailable");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(PIN_SEARCH_PATH_SQL)) {
            statement.execute();
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void requireFact(boolean condition) {
        if (!condition) throw new InvalidFact();
    }

    private static FactInconsistent factInconsistent() {
        LOGGER.log(Level.WARNING, "实时凭据核验事实不一致 category=CORRUPT_DATA");
        return new FactInconsistent();
    }

    private static Unavailable unavailable() {
        LOGGER.log(Level.WARNING, "实时凭据核验存储故障 category=STORAGE_UNAVAILABLE");
        return new Unavailable(true);
    }

    private record MeetingFact(String deploymentScope, String subjectScope,
            String clientSessionId, String profileId, int profileRevision,
            Instant createdAt, String state, String inputState) {
    }

    private static final class InvalidFact extends RuntimeException {
        private InvalidFact() {
            super("Realtime credential fact is inconsistent");
        }
    }
}
