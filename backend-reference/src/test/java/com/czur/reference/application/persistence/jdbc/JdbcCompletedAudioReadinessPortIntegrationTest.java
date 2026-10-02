package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CompletedAudioReadinessPort;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.AudioFrameContentDigest;
import com.czur.reference.application.realtime.AudioStreamContentDigest;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 随机 schema 的隔离 PostgreSQL 门禁：只验证 V015 完整输入，不启动 Stop/Worker。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcCompletedAudioReadinessPortIntegrationTest {
    private static final String MEETING = "mtg_audio_readiness";
    private String schema;
    private JdbcCompletedAudioReadinessPort port;

    @BeforeEach
    void setUp() throws Exception {
        schema = "audio_readiness_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        execute(migration("V001__initial_schema.sql"));
        execute("""
                INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                    subject_scope, profile_id, profile_revision, state, created_at, updated_at)
                VALUES ('mtg_audio_readiness', 'test', 'audio-readiness', 'subject', 'profile', 1,
                    'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        port = new JdbcCompletedAudioReadinessPort(source(schema));
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
    void missingDeclarationAndTerminalFrameRemainDistinctFromReady() throws Exception {
        assertInstanceOf(CompletedAudioReadinessPort.Missing.class, port.check(MEETING));
        insertInput();
        assertInstanceOf(CompletedAudioReadinessPort.Pending.class, port.check(MEETING));
        insertFrame(frame(1, 0, (byte) 1));
        cursor(1, 20);
        assertInstanceOf(CompletedAudioReadinessPort.Pending.class, port.check(MEETING));
        completion(2, 40);
        assertInstanceOf(CompletedAudioReadinessPort.Pending.class, port.check(MEETING));

        MeetingAudioFrame second = frame(2, 20, (byte) 2);
        insertFrame(second);
        cursor(2, 40);
        CompletedAudioReadinessPort.Ready ready = assertInstanceOf(
                CompletedAudioReadinessPort.Ready.class, port.check(MEETING));
        AudioStreamContentDigest expected = new AudioStreamContentDigest();
        expected.append(1, 1, AudioFrameContentDigest.sha256(frame(1, 0, (byte) 1)));
        expected.append(2, 1, AudioFrameContentDigest.sha256(second));
        assertEquals(expected.finish(2, 40), ready.contentDigest());
        assertEquals(2, ready.lastAcceptedSequence());
        assertEquals(40, ready.audioDurationMs());
    }

    @Test
    void missingCommittedMiddleFrameOrTamperedAudioIsInconsistent() throws Exception {
        insertInput();
        insertFrame(frame(1, 0, (byte) 1));
        insertFrame(frame(3, 40, (byte) 3));
        cursor(3, 60);
        completion(3, 60);
        assertInstanceOf(CompletedAudioReadinessPort.Inconsistent.class, port.check(MEETING));

        insertFrame(frame(2, 20, (byte) 2));
        assertInstanceOf(CompletedAudioReadinessPort.Ready.class, port.check(MEETING));
        // 保持长度与已保存摘要不变，只改音频原始字节，不能凭存储摘要签发 Ready。
        execute("""
                UPDATE meeting_audio_frame SET audio = decode(repeat('ff', 640), 'hex')
                 WHERE meeting_id = 'mtg_audio_readiness' AND stream_sequence = 2
                """);
        assertInstanceOf(CompletedAudioReadinessPort.Inconsistent.class, port.check(MEETING));
    }

    @Test
    void timelineAndCompletionEndpointMustMatchLastFrame() throws Exception {
        insertInput();
        insertFrame(frame(1, 0, (byte) 1));
        insertFrame(frame(2, 21, (byte) 2));
        cursor(2, 41);
        completion(2, 41);
        // 两帧各自摘要正确，第二帧的 start 与上一帧 end 不连续。
        assertInstanceOf(CompletedAudioReadinessPort.Inconsistent.class, port.check(MEETING));
        execute("DELETE FROM meeting_audio_frame WHERE meeting_id = 'mtg_audio_readiness' "
                + "AND stream_sequence = 2");
        insertFrame(frame(2, 20, (byte) 2));
        cursor(2, 40);
        // 已接管所有声明序号但完成时间轴超出末帧，不得当成等待更多帧。
        assertInstanceOf(CompletedAudioReadinessPort.Inconsistent.class, port.check(MEETING));
    }

    @Test
    void unverifiedIndependentRecordingIsInconsistent() throws Exception {
        insertInput();
        insertFrame(frame(1, 0, (byte) 1));
        cursor(1, 20);
        execute("""
                UPDATE meeting_audio_input SET complete_last_stream_sequence = 1,
                    complete_last_audio_position_ms = 20,
                    complete_recording_checksum = repeat('a', 64),
                    complete_recording_size_bytes = 640,
                    complete_request_id = 'recording'
                 WHERE meeting_id = 'mtg_audio_readiness'
                """);
        assertInstanceOf(CompletedAudioReadinessPort.Inconsistent.class, port.check(MEETING));
    }

    @Test
    void concurrentCompletionUsesOneSnapshotAndStorageFailureIsUnavailable() throws Exception {
        insertInput();
        insertFrame(frame(1, 0, (byte) 1));
        cursor(1, 20);
        completion(2, 40);
        AtomicBoolean inserted = new AtomicBoolean();
        DataSource hooked = afterCursorQuery(source(schema), () -> {
            insertFrame(frame(2, 20, (byte) 2));
            cursor(2, 40);
            inserted.set(true);
        });
        assertInstanceOf(CompletedAudioReadinessPort.Pending.class,
                new JdbcCompletedAudioReadinessPort(hooked).check(MEETING));
        assertEquals(true, inserted.get());
        assertInstanceOf(CompletedAudioReadinessPort.Ready.class, port.check(MEETING));

        DataSource unavailable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    throw new SQLException("do not log this detail", "08006");
                });
        assertInstanceOf(CompletedAudioReadinessPort.Unavailable.class,
                new JdbcCompletedAudioReadinessPort(unavailable).check(MEETING));
    }

    private void insertInput() throws Exception {
        execute("""
                INSERT INTO meeting_audio_input (meeting_id, writer_id, writer_epoch)
                VALUES ('mtg_audio_readiness', '00000000-0000-0000-0000-000000000001', 1)
                """);
    }

    private void insertFrame(MeetingAudioFrame frame) throws Exception {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting_audio_frame (meeting_id, stream_sequence,
                         start_position_ms, duration_ms, audio, source_generation,
                         format_revision, gap_duration_ms_before, flags, digest_version,
                         content_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, MEETING);
            statement.setLong(2, frame.streamSequence());
            statement.setLong(3, frame.startPositionMs());
            statement.setInt(4, frame.durationMs());
            statement.setBytes(5, frame.audio());
            statement.setLong(6, frame.sourceGeneration());
            statement.setInt(7, frame.formatRevision());
            statement.setLong(8, frame.gapDurationMsBefore());
            statement.setInt(9, 0);
            statement.setInt(10, AudioFrameContentDigest.VERSION);
            statement.setBytes(11, AudioFrameContentDigest.sha256(frame));
            statement.executeUpdate();
        }
    }

    private static MeetingAudioFrame frame(long sequence, long start, byte fill) {
        byte[] audio = new byte[640];
        Arrays.fill(audio, fill);
        return new MeetingAudioFrame(sequence, start, 20, audio, null, 0, 0, 0, Set.of());
    }

    private void cursor(long sequence, long position) throws Exception {
        execute("UPDATE meeting_audio_input SET accepted_stream_sequence = " + sequence
                + ", accepted_audio_position_ms = " + position
                + " WHERE meeting_id = 'mtg_audio_readiness'");
    }

    private void completion(long sequence, long position) throws Exception {
        execute("UPDATE meeting_audio_input SET complete_last_stream_sequence = " + sequence
                + ", complete_last_audio_position_ms = " + position
                + ", complete_recording_checksum = '', complete_recording_size_bytes = 0, "
                + "complete_request_id = 'done' WHERE meeting_id = 'mtg_audio_readiness'");
    }

    /** 在首次读取游标后从另一个连接提交新帧，证明后续帧扫描仍看旧快照。 */
    private static DataSource afterCursorQuery(DataSource source, ThrowingAction action) {
        AtomicBoolean once = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object value = forward(source, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (p, call, arguments) -> {
                                Object prepared = forward(connection, call, arguments);
                                if (!call.getName().equals("prepareStatement")
                                        || !((String) arguments[0]).contains("FROM meeting_audio_input")) {
                                    return prepared;
                                }
                                PreparedStatement statement = (PreparedStatement) prepared;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class},
                                        (sp, query, queryArgs) -> {
                                            Object result = forward(statement, query, queryArgs);
                                            if (query.getName().equals("executeQuery")
                                                    && once.compareAndSet(false, true)) action.run();
                                            return result;
                                        });
                            });
                });
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

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
