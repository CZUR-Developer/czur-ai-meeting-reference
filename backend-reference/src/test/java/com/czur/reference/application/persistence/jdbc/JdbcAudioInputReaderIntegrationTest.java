package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.AudioFrameContentDigest;
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
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V015 只读快照与重放门禁；仅使用随机 schema 的隔离 PostgreSQL。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcAudioInputReaderIntegrationTest {
    private static final String MEETING = "mtg_audio_reader";
    private String schema;
    private JdbcAudioInputReader reader;

    @BeforeEach
    void setUp() throws Exception {
        schema = "audio_reader_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        execute(migration("V001__initial_schema.sql"));
        execute(migration("V015__durable_audio_input.sql"));
        execute("""
                INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                    subject_scope, profile_id, profile_revision, state, created_at, updated_at)
                VALUES ('mtg_audio_reader', 'test', 'audio-reader', 'subject', 'profile', 1,
                    'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        reader = new JdbcAudioInputReader(source(schema));
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
    void missingZeroCursorAndBoundedReplayOfCommittedFrames() throws Exception {
        assertInstanceOf(DurableAudioInputPort.Missing.class, reader.readCursor(MEETING));
        assertInstanceOf(DurableAudioInputPort.Missing.class, reader.replay(MEETING, 0, 1));
        insertInput();
        DurableAudioInputPort.Cursor zero = assertInstanceOf(
                DurableAudioInputPort.CursorFound.class, reader.readCursor(MEETING)).cursor();
        assertEquals(0, zero.acceptedStreamSequence());
        assertEquals(List.of(), assertInstanceOf(DurableAudioInputPort.FramePage.class,
                reader.replay(MEETING, 0, 1)).frames());

        MeetingAudioFrame first = frame(1, 0, (byte) 1);
        MeetingAudioFrame second = frame(2, 20, (byte) 2);
        insertFrame(first);
        insertFrame(second);
        execute("""
                UPDATE meeting_audio_input SET accepted_stream_sequence = 2,
                    accepted_audio_position_ms = 40,
                    complete_last_stream_sequence = 2,
                    complete_last_audio_position_ms = 40,
                    complete_recording_checksum = '',
                    complete_recording_size_bytes = 0,
                    complete_request_id = 'complete-1'
                 WHERE meeting_id = 'mtg_audio_reader'
                """);

        DurableAudioInputPort.Cursor cursor = assertInstanceOf(
                DurableAudioInputPort.CursorFound.class, reader.readCursor(MEETING)).cursor();
        assertEquals(2, cursor.acceptedStreamSequence());
        assertEquals(40, cursor.acceptedAudioPositionMs());
        assertEquals("complete-1", cursor.completion().requestId());
        DurableAudioInputPort.FramePage page = assertInstanceOf(
                DurableAudioInputPort.FramePage.class, reader.replay(MEETING, 0, 1));
        assertEquals(2, page.acceptedStreamSequence());
        assertEquals(1, page.frames().size());
        assertEquals(1, page.frames().get(0).streamSequence());
        assertArrayEquals(first.audio(), page.frames().get(0).audio());
        assertEquals(2, assertInstanceOf(DurableAudioInputPort.FramePage.class,
                reader.replay(MEETING, 1, 1000)).frames().get(0).streamSequence());
        assertEquals(List.of(), assertInstanceOf(DurableAudioInputPort.FramePage.class,
                reader.replay(MEETING, 2, 1)).frames());
        assertInstanceOf(DurableAudioInputPort.InvalidCursor.class,
                reader.replay(MEETING, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> reader.replay(MEETING, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> reader.replay(MEETING, 0, 1001));
    }

    @Test
    void replayDetectsMiddleGapAndShortPageWithoutWholeMeetingScan() throws Exception {
        insertInput();
        insertFrame(frame(1, 0, (byte) 1));
        insertFrame(frame(3, 40, (byte) 3));
        execute("""
                UPDATE meeting_audio_input SET accepted_stream_sequence = 3,
                    accepted_audio_position_ms = 60
                 WHERE meeting_id = 'mtg_audio_reader'
                """);
        assertEquals(3, assertInstanceOf(DurableAudioInputPort.CursorFound.class,
                reader.readCursor(MEETING)).cursor().acceptedStreamSequence());
        assertInstanceOf(DurableAudioInputPort.FactInconsistentRead.class,
                reader.replay(MEETING, 1, 10));
        execute("""
                DELETE FROM meeting_audio_frame
                 WHERE meeting_id = 'mtg_audio_reader' AND stream_sequence = 3
                """);
        insertFrame(frame(2, 20, (byte) 2));
        assertInstanceOf(DurableAudioInputPort.FactInconsistentRead.class,
                reader.replay(MEETING, 0, 10));
    }

    @Test
    void corruptDigestAndWrongLastEndpointFailClosed() throws Exception {
        insertInput();
        insertFrame(frame(1, 0, (byte) 1));
        execute("""
                UPDATE meeting_audio_input SET accepted_stream_sequence = 1,
                    accepted_audio_position_ms = 20
                 WHERE meeting_id = 'mtg_audio_reader'
                """);
        execute("""
                UPDATE meeting_audio_frame SET content_digest = decode(repeat('00', 32), 'hex')
                 WHERE meeting_id = 'mtg_audio_reader' AND stream_sequence = 1
                """);
        assertInstanceOf(DurableAudioInputPort.FactInconsistentRead.class,
                reader.replay(MEETING, 0, 1));
        assertInstanceOf(DurableAudioInputPort.FactInconsistentRead.class,
                reader.readCursor(MEETING));

        updateDigest(frame(1, 0, (byte) 1));
        execute("""
                UPDATE meeting_audio_input SET accepted_audio_position_ms = 21
                 WHERE meeting_id = 'mtg_audio_reader'
                """);
        assertInstanceOf(DurableAudioInputPort.FactInconsistentRead.class,
                reader.readCursor(MEETING));
    }

    @Test
    void replayStaysOnOneSnapshotDuringConcurrentAppend() throws Exception {
        insertInput();
        insertFrame(frame(1, 0, (byte) 1));
        execute("""
                UPDATE meeting_audio_input SET accepted_stream_sequence = 1,
                    accepted_audio_position_ms = 20
                 WHERE meeting_id = 'mtg_audio_reader'
                """);
        AtomicBoolean appended = new AtomicBoolean();
        DataSource hooked = afterCursorQuery(source(schema), () -> {
            insertFrame(frame(2, 20, (byte) 2));
            execute("""
                    UPDATE meeting_audio_input SET accepted_stream_sequence = 2,
                        accepted_audio_position_ms = 40
                     WHERE meeting_id = 'mtg_audio_reader'
                    """);
            appended.set(true);
        });
        DurableAudioInputPort.FramePage oldSnapshot = assertInstanceOf(
                DurableAudioInputPort.FramePage.class,
                new JdbcAudioInputReader(hooked).replay(MEETING, 0, 10));
        assertEquals(true, appended.get());
        assertEquals(1, oldSnapshot.acceptedStreamSequence());
        assertEquals(1, oldSnapshot.frames().size());
        assertEquals(2, assertInstanceOf(DurableAudioInputPort.CursorFound.class,
                reader.readCursor(MEETING)).cursor().acceptedStreamSequence());
    }

    private static DataSource afterCursorQuery(DataSource source, ThrowingAction afterQuery) {
        AtomicBoolean once = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object value = forward(source, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (connectionProxy, connectionMethod,
                                    connectionArgs) -> {
                                Object prepared = forward(connection, connectionMethod,
                                        connectionArgs);
                                if (!connectionMethod.getName().equals("prepareStatement")
                                        || !((String) connectionArgs[0]).contains(
                                                "FROM meeting_audio_input")) return prepared;
                                PreparedStatement statement = (PreparedStatement) prepared;
                                return Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class},
                                        (statementProxy, statementMethod, statementArgs) -> {
                                            Object result = forward(statement, statementMethod,
                                                    statementArgs);
                                            if (statementMethod.getName().equals("executeQuery")
                                                    && once.compareAndSet(false, true)) {
                                                afterQuery.run();
                                            }
                                            return result;
                                        });
                            });
                });
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private void insertInput() throws Exception {
        execute("""
                INSERT INTO meeting_audio_input (meeting_id, writer_id, writer_epoch)
                VALUES ('mtg_audio_reader', '00000000-0000-0000-0000-000000000001', 1)
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
            statement.setInt(9, 2);
            statement.setInt(10, AudioFrameContentDigest.VERSION);
            statement.setBytes(11, AudioFrameContentDigest.sha256(frame));
            statement.executeUpdate();
        }
    }

    private void updateDigest(MeetingAudioFrame frame) throws Exception {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting_audio_frame SET content_digest = ?
                      WHERE meeting_id = ? AND stream_sequence = ?
                     """)) {
            statement.setBytes(1, AudioFrameContentDigest.sha256(frame));
            statement.setString(2, MEETING);
            statement.setLong(3, frame.streamSequence());
            statement.executeUpdate();
        }
    }

    private static MeetingAudioFrame frame(long sequence, long start, byte fill) {
        byte[] audio = new byte[640];
        java.util.Arrays.fill(audio, fill);
        return new MeetingAudioFrame(sequence, start, 20, audio, null, 1, 1, 0,
                Set.of(MeetingAudioFrame.AudioFrameFlag.AFTER_GAP));
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String migration(String filename) throws Exception {
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

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
