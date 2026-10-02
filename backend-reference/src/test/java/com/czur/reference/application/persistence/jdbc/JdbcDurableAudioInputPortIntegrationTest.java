package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 04B-2b 写事务门禁；每例在隔离 PostgreSQL 的随机 schema 安装真实迁移。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcDurableAudioInputPortIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql", "V013__meeting_create_fact.sql",
            "V014__realtime_token_grant.sql", "V015__durable_audio_input.sql");
    private static final String SUFFIX = "audio_write";
    private static final String MEETING = T15CreateRecoveryFixtures.meetingId(SUFFIX);
    private String schema;
    private JdbcDurableAudioInputPort port;

    @BeforeEach
    void setUp() throws Exception {
        schema = "audio_write_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        for (String migration : MIGRATIONS) {
            execute(migration(migration));
            if (!migration.startsWith("V015")) {
                execute("INSERT INTO schema_migrations(version) VALUES ('"
                        + migration.substring(0, migration.length() - 4) + "')");
            }
        }
        T15CreateRecoveryFixtures.insertReserve(source(schema), SUFFIX);
        assertInstanceOf(CompleteMeetingCreationUnitOfWork.Success.class,
                T15CreateRecoveryFixtures.unitOfWork(source(schema)).create(
                        T15CreateRecoveryFixtures.request(SUFFIX,
                                "audio-write-create-key", null, "test-audio-token")));
        port = new JdbcDurableAudioInputPort(
                source(schema), T15CreateRecoveryFixtures.DEPLOYMENT);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (schema != null) {
            try (Connection connection = source(null).getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void startGateEpochFenceFrameIdentityAndCompletionEndpoint() throws Exception {
        DurableAudioInputPort.WriterBinding first = bind(port);
        MeetingAudioFrame frame1 = frame(1, 0, (byte) 1);
        assertReject(DurableAudioInputPort.Rejection.DENIED, port.append(first, frame1));
        assertReject(DurableAudioInputPort.Rejection.DENIED, port.complete(first, complete(1, 20)));
        setState("STARTING", "INPUT_OPEN");
        assertEquals(1, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.append(first, frame1)).cursor().acceptedStreamSequence());
        assertEquals(true, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.append(first, frame1)).replayed());
        assertReject(DurableAudioInputPort.Rejection.CONFLICT,
                port.append(first, frame(1, 0, (byte) 9)));
        assertReject(DurableAudioInputPort.Rejection.GAP,
                port.append(first, frame(3, 40, (byte) 3)));
        assertReject(DurableAudioInputPort.Rejection.CONFLICT,
                port.complete(first, complete(1, 21)));
        assertEquals(false, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.complete(first, complete(2, 40))).replayed());
        assertReject(DurableAudioInputPort.Rejection.CONFLICT,
                port.append(first, frame(2, 21, (byte) 2)));

        DurableAudioInputPort.WriterBinding second = bind(port);
        assertEquals(first.writerEpoch() + 1, second.writerEpoch());
        assertReject(DurableAudioInputPort.Rejection.FENCED,
                port.append(first, frame(2, 20, (byte) 2)));
        assertReject(DurableAudioInputPort.Rejection.FENCED,
                port.complete(first, complete(2, 40)));
        setState("STOPPING", "INPUT_COMPLETE_PENDING");
        assertEquals(2, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.append(second, frame(2, 20, (byte) 2))).cursor().acceptedStreamSequence());
        assertEquals(true, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.complete(second, complete(2, 40))).replayed());
        assertReject(DurableAudioInputPort.Rejection.CONFLICT,
                port.complete(second, new DurableAudioInputPort.Completion(2, 40, "", 0, "other")));
        execute("UPDATE meeting SET state = 'FINALIZING', input_state = 'INPUT_ACCEPTED', "
                + "finalization_receipt_id = 'receipt-test' WHERE meeting_id = '" + MEETING + "'");
        assertEquals(true, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.complete(second, complete(2, 40))).replayed());
        assertReject(DurableAudioInputPort.Rejection.DENIED,
                port.append(second, frame(2, 20, (byte) 2)));
        setState("DELETION_PENDING", "INPUT_ACCEPTED");
        assertReject(DurableAudioInputPort.Rejection.DENIED,
                port.complete(second, complete(2, 40)));
        DurableAudioInputPort.FramePage replay = assertInstanceOf(
                DurableAudioInputPort.FramePage.class, port.replay(MEETING, 0, 10));
        assertEquals(2, replay.frames().size());
        assertArrayEquals(frame1.audio(), replay.frames().get(0).audio());
        assertEquals(40, assertInstanceOf(DurableAudioInputPort.CursorFound.class,
                port.readCursor(MEETING)).cursor().acceptedAudioPositionMs());
    }

    @Test
    void badV013AndCorruptFrameFailClosed() throws Exception {
        DurableAudioInputPort.WriterBinding binding = bind(port);
        setState("STARTING", "INPUT_OPEN");
        assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.append(binding, frame(1, 0, (byte) 1)));
        execute("ALTER TABLE meeting_audio_frame DROP CONSTRAINT meeting_audio_frame_shape_ck");
        execute("UPDATE meeting_audio_frame SET content_digest = decode(repeat('00', 32), 'hex')");
        assertReject(DurableAudioInputPort.Rejection.FACT_INCONSISTENT,
                port.append(binding, frame(2, 20, (byte) 2)));
        assertInstanceOf(DurableAudioInputPort.FactInconsistentRead.class, port.readCursor(MEETING));
        execute("ALTER TABLE meeting_create_fact DROP CONSTRAINT meeting_create_fact_response_ck");
        execute("UPDATE meeting_create_fact SET response_sha256 = decode(repeat('00', 32), 'hex')");
        assertReject(DurableAudioInputPort.Rejection.FACT_INCONSISTENT,
                port.bindWriter(MEETING, DurableAudioInputPort.WriterConnectionId.newConnection()));
    }

    @Test
    void v013LimitsAndEpochOverflowRejectBeforeWriting() throws Exception {
        DurableAudioInputPort.WriterBinding binding = bind(port);
        setState("STARTING", "INPUT_OPEN");
        assertReject(DurableAudioInputPort.Rejection.LIMIT_EXCEEDED,
                port.append(binding, new MeetingAudioFrame(1, 0, 101, new byte[3232],
                        null, 0, 0, 0, Set.of())));
        assertReject(DurableAudioInputPort.Rejection.LIMIT_EXCEEDED,
                port.complete(binding, complete(2, 3_600_001)));
        assertReject(DurableAudioInputPort.Rejection.INVALID_INPUT,
                port.complete(binding, new DurableAudioInputPort.Completion(1, 20,
                        "a".repeat(64), 640, "recording-without-upload")));
        execute("UPDATE meeting_audio_input SET writer_epoch = 9223372036854775807 "
                + "WHERE meeting_id = '" + MEETING + "'");
        assertReject(DurableAudioInputPort.Rejection.FACT_INCONSISTENT,
                port.bindWriter(MEETING, DurableAudioInputPort.WriterConnectionId.newConnection()));
    }

    @Test
    void commitConfirmationLossReturnsUnknownAndReadbackDecidesFrame() throws Exception {
        AtomicBoolean loseCommit = new AtomicBoolean(true);
        JdbcDurableAudioInputPort uncertain = new JdbcDurableAudioInputPort(
                loseFirstCommit(source(schema), loseCommit),
                T15CreateRecoveryFixtures.DEPLOYMENT);
        assertInstanceOf(DurableAudioInputPort.Unknown.class, uncertain.bindWriter(
                MEETING, DurableAudioInputPort.WriterConnectionId.newConnection()));
        // 绑定未知时只能以新连接重新绑定，不能拿第一次的 epoch 继续写。
        DurableAudioInputPort.WriterBinding binding = bind(port);
        assertEquals(2, binding.writerEpoch());
        setState("STARTING", "INPUT_OPEN");
        loseCommit.set(true);
        assertInstanceOf(DurableAudioInputPort.Unknown.class,
                uncertain.append(binding, frame(1, 0, (byte) 1)));
        assertEquals(1, assertInstanceOf(DurableAudioInputPort.CursorFound.class,
                port.readCursor(MEETING)).cursor().acceptedStreamSequence());
        assertEquals(true, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.append(binding, frame(1, 0, (byte) 1))).replayed());
    }

    private static DataSource loseFirstCommit(DataSource source, AtomicBoolean lose) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object result = forward(source, method, args);
                    if (!method.getName().equals("getConnection")) return result;
                    Connection connection = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (p, call, arguments) -> {
                                Object value = forward(connection, call, arguments);
                                if (call.getName().equals("commit")
                                        && lose.compareAndSet(true, false)) {
                                    throw new SQLException("commit confirmation lost", "08006");
                                }
                                return value;
                            });
                });
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private DurableAudioInputPort.WriterBinding bind(JdbcDurableAudioInputPort target) {
        return assertInstanceOf(DurableAudioInputPort.Bound.class,
                target.bindWriter(MEETING,
                        DurableAudioInputPort.WriterConnectionId.newConnection())).binding();
    }

    private static MeetingAudioFrame frame(long sequence, long start, byte fill) {
        byte[] audio = new byte[640];
        java.util.Arrays.fill(audio, fill);
        return new MeetingAudioFrame(sequence, start, 20, audio, null, 0, 0, 0, Set.of());
    }

    private static DurableAudioInputPort.Completion complete(long sequence, long end) {
        return new DurableAudioInputPort.Completion(sequence, end, "", 0, "complete-1");
    }

    private static void assertReject(DurableAudioInputPort.Rejection reason, Object actual) {
        assertEquals(reason, assertInstanceOf(DurableAudioInputPort.Rejected.class, actual).reason());
    }

    private void setState(String state, String input) throws Exception {
        execute("UPDATE meeting SET state = '" + state + "', input_state = '" + input
                + "' WHERE meeting_id = '" + MEETING + "'");
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String migration(String filename) throws Exception {
        if (!filename.equals("V001__initial_schema.sql")) return "SELECT 1";
        for (Path path : new Path[]{Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)}) {
            if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("migration file missing");
    }

    private static PGSimpleDataSource source(String schema) {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared database is forbidden");
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }

    private static String quote(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
}
