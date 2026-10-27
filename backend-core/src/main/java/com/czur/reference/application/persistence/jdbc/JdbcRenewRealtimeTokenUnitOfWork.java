package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.realtime.RealtimeAccessPolicy;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.RealtimeTokenGrantJson;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V014 实时 Token 续期的单会议事务工作单元。
 *
 * <p>先锁定 Meeting 原始行，再用可信上下文判断归属，随后核 V013 首次创建事实。
 * 同键 V014 结果在状态判定之前严格读取并原文重放，因此会议后续停止或 Grant
 * 到期也不改变历史成功响应。新键仅在持久状态允许写入时签发；不重算权益、不改写
 * V013、不访问模型、HTTP 或 WSS。</p>
 *
 * <p>提交调用一旦开始，任何异常都丢弃原连接并在新只读连接中回查同键持久赢家。
 * 只有严格事实、归属和 V013 全部重验通过才返回已保存 JSON；回查为空或失败返回
 * OUTCOME_UNKNOWN，绝不返回内存候选。日志只输出固定阶段、结果和安全 SQLState，
 * 不输出 Token、JSON、主体、会议 ID 或幂等键。</p>
 */
public final class JdbcRenewRealtimeTokenUnitOfWork
        implements RenewRealtimeTokenUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcRenewRealtimeTokenUnitOfWork.class.getName());
    private static final Base64.Encoder TOKEN_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final String MEETING_BASE_SQL = """
            SELECT deployment_scope, subject_scope, client_session_id,
                   profile_id, profile_revision, created_at, state, input_state
              FROM meeting WHERE meeting_id = ?
            """;
    private static final String FIND_GRANT_SQL = """
            SELECT idempotency_key, grant_json, grant_sha256, token_sha256,
                   issued_at, expires_at
              FROM meeting_realtime_token_grant
             WHERE meeting_id = ? AND idempotency_key = ?
            """;
    private static final String INSERT_GRANT_SQL = """
            INSERT INTO meeting_realtime_token_grant (
                meeting_id, idempotency_key, grant_json, grant_sha256,
                token_sha256, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final DataSource dataSource;
    private final Clock clock;
    private final Duration tokenTtl;
    private final SecureRandom random;

    /**
     * @param dataSource 与会议和 V013/V014 共用的持久事实库
     * @param clock 服务端时钟；签发时间与期限均取自本时钟
     * @param tokenTtl 正的短期凭据存活时间
     * @param random 服务端安全随机源；生产不得使用固定种子
     */
    public JdbcRenewRealtimeTokenUnitOfWork(DataSource dataSource, Clock clock,
            Duration tokenTtl, SecureRandom random) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        if (tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("tokenTtl must be positive");
        }
        this.random = Objects.requireNonNull(random, "random");
    }

    /** 在会议行锁内完成归属、同键重放和新键保存。 */
    @Override
    public Result renew(Request request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean commitStarted = false;
        boolean resolved = false;
        byte[] candidateDigest = null;
        Phase phase = Phase.OPEN;
        try {
            connection = dataSource.getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);

            phase = Phase.MEETING_LOCK;
            Optional<MeetingRow> optional = readMeeting(connection, request, true);
            if (optional.isEmpty()) {
                connection.rollback(); resolved = true;
                return failure(ErrorCode.NOT_FOUND, phase);
            }
            MeetingRow meeting = optional.orElseThrow();
            phase = Phase.ACCESS;
            if (MeetingAccessGuard.evaluate(request.context(), meeting.deploymentScope(),
                    meeting.subjectScope()) != MeetingAccessGuard.Decision.ALLOWED) {
                connection.rollback(); resolved = true;
                return failure(ErrorCode.ACCESS_DENIED, phase);
            }
            phase = Phase.FIRST_FACT;
            CreateMeetingFactStore.Record first = validateFirst(
                    connection, request, meeting, false);

            phase = Phase.REPLAY;
            Optional<JdbcRealtimeTokenGrantFact.StoredGrant> previous =
                    findGrant(connection, request.meetingId(), request.idempotencyKey());
            if (previous.isPresent()) {
                String original = previous.orElseThrow().grantJson();
                connection.rollback(); resolved = true;
                LOGGER.log(Level.FINE, "实时 Token 续期命中持久重放 result=REPLAYED");
                return new Success(SuccessCode.REPLAYED, original);
            }

            phase = Phase.STATE;
            RealtimeAccessPolicy.Decision decision = classify(meeting);
            if (decision != RealtimeAccessPolicy.Decision.WRITE) {
                connection.rollback(); resolved = true;
                return failure(decision == RealtimeAccessPolicy.Decision.FACT_INCONSISTENT
                        ? ErrorCode.FACT_INCONSISTENT : ErrorCode.STATE_CONFLICT, phase);
            }

            phase = Phase.ISSUE;
            Instant issuedAt = Objects.requireNonNull(clock.instant(), "clock.instant()")
                    .truncatedTo(ChronoUnit.MICROS);
            if (meeting.createdAt() == null || issuedAt.isBefore(meeting.createdAt())) {
                connection.rollback(); resolved = true;
                return failure(ErrorCode.INTERNAL, phase);
            }
            Instant expiresAt = issuedAt.plus(tokenTtl).truncatedTo(ChronoUnit.MICROS);
            if (!issuedAt.isBefore(expiresAt)) {
                connection.rollback(); resolved = true;
                return failure(ErrorCode.INTERNAL, phase);
            }
            byte[] tokenBytes = new byte[32];
            String token;
            try {
                random.nextBytes(tokenBytes);
                token = TOKEN_ENCODER.encodeToString(tokenBytes);
            } finally {
                // 随机源即使写入部分字节后失败，也不得保留候选原始字节。
                Arrays.fill(tokenBytes, (byte) 0);
            }
            candidateDigest = JdbcRealtimeTokenGrantFact.sha256(
                    token.getBytes(StandardCharsets.UTF_8));
            byte[] initialDigest = JdbcRealtimeTokenGrantFact.sha256(
                    first.response().realtime().sessionToken().getBytes(StandardCharsets.UTF_8));
            boolean initialCollision = MessageDigest.isEqual(candidateDigest, initialDigest);
            Arrays.fill(initialDigest, (byte) 0);
            if (initialCollision) {
                connection.rollback(); resolved = true;
                return failure(ErrorCode.INTERNAL, phase);
            }
            String json = RealtimeTokenGrantJson.encode(new Grant(
                    request.meetingId(), request.meetingId(), token, expiresAt));
            byte[] jsonDigest = JdbcRealtimeTokenGrantFact.sha256(
                    json.getBytes(StandardCharsets.UTF_8));
            phase = Phase.INSERT;
            int inserted;
            try {
                inserted = insertGrant(connection, request, json, jsonDigest,
                        candidateDigest, issuedAt, expiresAt);
            } finally {
                Arrays.fill(jsonDigest, (byte) 0);
            }
            Optional<JdbcRealtimeTokenGrantFact.StoredGrant> persisted =
                    findGrant(connection, request.meetingId(), request.idempotencyKey());
            if (inserted != 1 || persisted.isEmpty()
                    || !json.equals(persisted.orElseThrow().grantJson())) {
                // 会议行锁串行化同会议请求；0 行且本键不存在是全局 Token digest
                // 碰撞或异常 SQL 结果，不得冒充保存成功。
                connection.rollback(); resolved = true;
                return failure(ErrorCode.INTERNAL, phase);
            }
            String persistedJson = persisted.orElseThrow().grantJson();
            phase = Phase.COMMIT;
            commitStarted = true;
            connection.commit();
            resolved = true;
            LOGGER.log(Level.FINE, "实时 Token 续期持久提交 result=CREATED");
            return new Success(SuccessCode.CREATED, persistedJson);
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            if (!commitStarted) {
                rollbackQuietly(connection);
                resolved = true;
                return failure(failure.kind()
                        == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA
                        ? ErrorCode.FACT_INCONSISTENT : ErrorCode.STORAGE_UNAVAILABLE, phase);
            }
        } catch (JdbcRealtimeTokenGrantFact.CorruptFact | InvalidFact failure) {
            if (!commitStarted) {
                rollbackQuietly(connection);
                resolved = true;
                return failure(ErrorCode.FACT_INCONSISTENT, phase);
            }
        } catch (SQLException failure) {
            if (!commitStarted) {
                rollbackQuietly(connection);
                resolved = true;
                return failure(ErrorCode.STORAGE_UNAVAILABLE, phase);
            }
        } catch (DateTimeException | ArithmeticException failure) {
            if (!commitStarted) {
                rollbackQuietly(connection);
                resolved = true;
                return failure(ErrorCode.INTERNAL, phase);
            }
        } catch (RuntimeException failure) {
            if (!commitStarted) {
                rollbackQuietly(connection);
                resolved = true;
                return failure(ErrorCode.INTERNAL, phase);
            }
        } finally {
            // 编码、数据库写入、提交及异常回查的所有分支都必须释放候选摘要。
            if (candidateDigest != null) Arrays.fill(candidateDigest, (byte) 0);
            if (connection != null) {
                if (commitStarted && !resolved) {
                    abortQuietly(connection);
                }
                closeQuietly(connection);
            }
        }
        // 原连接已废弃，任何候选 Token 都不能越过此边界。
        return recoverCommittedWinner(request);
    }

    private Result recoverCommittedWinner(Request request) {
        try (Connection recovery = dataSource.getConnection()) {
            recovery.setReadOnly(true);
            recovery.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            recovery.setAutoCommit(false);
            Optional<MeetingRow> optional = readMeeting(recovery, request, false);
            if (optional.isEmpty()) return failure(ErrorCode.OUTCOME_UNKNOWN, Phase.RECOVERY);
            MeetingRow meeting = optional.orElseThrow();
            if (MeetingAccessGuard.evaluate(request.context(), meeting.deploymentScope(),
                    meeting.subjectScope()) != MeetingAccessGuard.Decision.ALLOWED) {
                return failure(ErrorCode.OUTCOME_UNKNOWN, Phase.RECOVERY);
            }
            validateFirst(recovery, request, meeting, true);
            Optional<JdbcRealtimeTokenGrantFact.StoredGrant> previous =
                    findGrant(recovery, request.meetingId(), request.idempotencyKey());
            recovery.rollback();
            return previous.<Result>map(stored ->
                    new Success(SuccessCode.REPLAYED, stored.grantJson()))
                    .orElseGet(() -> failure(ErrorCode.OUTCOME_UNKNOWN, Phase.RECOVERY));
        } catch (SQLException | RuntimeException failure) {
            return failure(ErrorCode.OUTCOME_UNKNOWN, Phase.RECOVERY);
        }
    }

    private static CreateMeetingFactStore.Record validateFirst(Connection connection,
            Request request, MeetingRow meeting, boolean readOnly) {
        CreateMeetingFactStore.Record first = (readOnly
                ? new JdbcCreateMeetingFactStore(connection)
                        .findByMeetingIdReadOnly(request.meetingId())
                : new JdbcCreateMeetingFactStore(connection)
                        .findByMeetingId(request.meetingId())).orElse(null);
        if (first == null) throw new InvalidFact();
        require(IdempotencyScopeCodec.meetingCreateV2(
                meeting.deploymentScope(), meeting.subjectScope()).equals(first.scope())
                && first.key().equals(first.request().requestSnapshot().idempotencyKey())
                && first.response().meetingId().equals(request.meetingId())
                && first.request().requestSnapshot().clientSessionId()
                        .equals(meeting.clientSessionId())
                && first.response().acceptedProfile().id().equals(meeting.profileId())
                && first.response().acceptedProfile().revision() == meeting.profileRevision()
                && first.createdAt().equals(meeting.createdAt()));
        return first;
    }

    private static RealtimeAccessPolicy.Decision classify(MeetingRow meeting) {
        try {
            return RealtimeAccessPolicy.classify(
                    MeetingState.valueOf(meeting.state()),
                    InputState.valueOf(meeting.inputState()));
        } catch (RuntimeException malformed) {
            throw new InvalidFact();
        }
    }

    private static Optional<MeetingRow> readMeeting(Connection connection,
            Request request, boolean forUpdate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                MEETING_BASE_SQL + (forUpdate ? " FOR UPDATE" : ""))) {
            statement.setString(1, request.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String deployment = rows.getString("deployment_scope");
                String subject = rows.getString("subject_scope");
                // 先从原始列判归属，外域和历史无主会议不解析状态或时间等
                // 健康事实，避免其损坏情况反射到本部署的返回结果。
                boolean visible = MeetingAccessGuard.evaluate(request.context(),
                        deployment, subject) == MeetingAccessGuard.Decision.ALLOWED;
                Timestamp created = visible ? rows.getTimestamp("created_at") : null;
                MeetingRow fact = new MeetingRow(deployment, subject,
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

    private static Optional<JdbcRealtimeTokenGrantFact.StoredGrant> findGrant(
            Connection connection, String meetingId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_GRANT_SQL)) {
            statement.setString(1, meetingId);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var stored = JdbcRealtimeTokenGrantFact.read(rows, meetingId, key);
                if (rows.next()) throw new InvalidFact();
                return Optional.of(stored);
            }
        }
    }

    private static int insertGrant(Connection connection, Request request, String json,
            byte[] jsonDigest, byte[] tokenDigest, Instant issuedAt, Instant expiresAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_GRANT_SQL)) {
            statement.setString(1, request.meetingId());
            statement.setString(2, request.idempotencyKey());
            statement.setString(3, json);
            statement.setBytes(4, jsonDigest);
            statement.setBytes(5, tokenDigest);
            statement.setTimestamp(6, Timestamp.from(issuedAt));
            statement.setTimestamp(7, Timestamp.from(expiresAt));
            return statement.executeUpdate();
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new InvalidFact();
    }

    private static Failure failure(ErrorCode code, Phase phase) {
        LOGGER.log(code.retryable() ? Level.WARNING : Level.FINE,
                "实时 Token 续期失败 phase={0} code={1}",
                new Object[]{phase.name(), code.name()});
        return new Failure(code);
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) return;
        try { connection.rollback(); }
        catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "实时 Token 续期回滚失败");
            abortQuietly(connection);
        }
    }

    private static void abortQuietly(Connection connection) {
        try { connection.abort(Runnable::run); }
        catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "实时 Token 续期连接废弃失败");
        }
    }

    private static void closeQuietly(Connection connection) {
        try { connection.close(); }
        catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "实时 Token 续期连接关闭失败");
        }
    }

    private record MeetingRow(String deploymentScope, String subjectScope,
            String clientSessionId, String profileId, int profileRevision,
            Instant createdAt, String state, String inputState) {
    }

    private static final class InvalidFact extends RuntimeException {
        private InvalidFact() { super("Realtime renewal fact is inconsistent"); }
    }

    private enum Phase {
        OPEN, MEETING_LOCK, ACCESS, FIRST_FACT, REPLAY, STATE, ISSUE, INSERT,
        COMMIT, RECOVERY
    }
}
