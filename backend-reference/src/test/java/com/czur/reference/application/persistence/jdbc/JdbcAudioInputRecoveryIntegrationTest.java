package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.AudioFrameContentDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 04B-2c 专属 PG16 门禁。脚本持有独立数据库并在 prepare/verify 两个测试 JVM
 * 之间真实 stop/start；子进程另起 JVM 竞争当前写者。所有裁决均重新从 JDBC 读取。
 */
@EnabledIfEnvironmentVariable(named = "T16_AUDIO_GATE_RUN_ID", matches = ".+")
class JdbcAudioInputRecoveryIntegrationTest {
    static final String CROSS = "audio_2c_cross";
    static final String MEETING = T15CreateRecoveryFixtures.meetingId(CROSS);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void twoJvmWriterReplacementFencesOldFrameAndCompletion() throws Exception {
        DataSource source = source();
        prepareMeeting(source, CROSS);
        JdbcDurableAudioInputPort port = port(source);
        DurableAudioInputPort.WriterBinding first = bind(port, MEETING);
        assertEquals(1, first.writerEpoch());
        assertInstanceOf(DurableAudioInputPort.Stored.class, port.append(first, frame(1, 0, (byte) 1)));

        // 会议行锁强制两个子 JVM 的真实 bind 同时在 PostgreSQL 等待。
        List<Probe> probes = new ArrayList<>();
        Process firstChild = null;
        Process secondChild = null;
        try (Connection lock = source.getConnection();
             ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout((int) PROCESS_TIMEOUT.toMillis());
            lock.setAutoCommit(false);
            try (PreparedStatement hold = lock.prepareStatement(
                    "SELECT meeting_id FROM meeting WHERE meeting_id=? FOR UPDATE")) {
                hold.setString(1, MEETING);
                try (ResultSet rows = hold.executeQuery()) { assertTrue(rows.next()); }
            }
            try {
                firstChild = startProbe(server.getLocalPort(), "a");
                secondChild = startProbe(server.getLocalPort(), "b");
                probes.add(acceptProbe(server, firstChild, secondChild));
                probes.add(acceptProbe(server, firstChild, secondChild));
                assertEquals(Set.of("a", "b"), Set.of(probes.get(0).role(), probes.get(1).role()));
                for (Probe probe : probes) probe.out().writeByte(1);
                for (Probe probe : probes) probe.out().flush();
                long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
                while (lockWaiters(source) < 2 && System.nanoTime() < deadline) {
                    assertTrue(firstChild.isAlive() && secondChild.isAlive(),
                            "writer JVM exited before both binds overlapped");
                    Thread.sleep(25);
                }
                assertEquals(2, lockWaiters(source), "both JVM binds must wait on the meeting row");
            } finally {
                lock.commit();
            }
            long firstEpoch = Long.parseLong(probes.get(0).in().readUTF());
            long secondEpoch = Long.parseLong(probes.get(1).in().readUTF());
            assertEquals(Set.of(2L, 3L), Set.of(firstEpoch, secondEpoch));
            assertEquals(3, epoch(source, MEETING));
            for (Probe probe : probes) probe.out().writeByte(1);
            for (Probe probe : probes) probe.out().flush();
            for (int i = 0; i < probes.size(); i++) {
                String result = probes.get(i).in().readUTF();
                assertEquals(i == 0 && firstEpoch == 3 || i == 1 && secondEpoch == 3
                        ? "STORED STORED" : "FENCED FENCED", result);
            }
            for (Probe probe : probes) {
                assertTrue(probe.process().waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                String output = new String(probe.process().getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                assertEquals(0, probe.process().exitValue(), output);
            }
        } finally {
            for (Probe probe : probes) {
                probe.socket().close();
            }
            if (firstChild != null && firstChild.isAlive()) firstChild.destroyForcibly();
            if (secondChild != null && secondChild.isAlive()) secondChild.destroyForcibly();
        }

        assertReject(DurableAudioInputPort.Rejection.FENCED,
                port.append(first, frame(3, 40, (byte) 3)));
        assertReject(DurableAudioInputPort.Rejection.FENCED,
                port.complete(first, completion(3, 60)));
        assertEquals(3, epoch(source, MEETING));
        assertCursor(port, MEETING, 2, 40, completion(2, 40));
        assertFrames(port, MEETING, List.of(frame(1, 0, (byte) 1), frame(2, 20, (byte) 3)));
    }

    @Test
    void commitFailureBeforeAndConfirmationLossAfterAreResolvedByReadOnlyFacts()
            throws Exception {
        for (Fault fault : Fault.values()) {
            String suffix = fault == Fault.BEFORE ? "audio_2c_before" : "audio_2c_after";
            String meeting = T15CreateRecoveryFixtures.meetingId(suffix);
            DataSource source = source();
            prepareMeeting(source, suffix);
            JdbcDurableAudioInputPort port = port(source);

            AtomicBoolean injected = new AtomicBoolean();
            assertInstanceOf(DurableAudioInputPort.Unknown.class,
                    port(faultOnce(source, fault, injected)).bindWriter(meeting,
                            DurableAudioInputPort.WriterConnectionId.newConnection()));
            assertTrue(injected.get(), "bind fault must reach commit");
            if (fault == Fault.BEFORE) {
                assertInstanceOf(DurableAudioInputPort.Missing.class, port.readCursor(meeting));
                assertEquals(0, inputRows(source, meeting));
            } else {
                assertCursor(port, meeting, 0, 0, null);
                assertEquals(1, epoch(source, meeting));
            }
            // 绑定未知时舍弃原连接 ID；新连接重新绑定，epoch 由已提交事实决定。
            DurableAudioInputPort.WriterBinding binding = bind(port, meeting);
            assertEquals(fault == Fault.BEFORE ? 1 : 2, binding.writerEpoch());

            MeetingAudioFrame frame = frame(1, 0, (byte) 7);
            injected.set(false);
            assertInstanceOf(DurableAudioInputPort.Unknown.class,
                    port(faultOnce(source, fault, injected)).append(binding, frame));
            assertTrue(injected.get(), "append fault must reach commit");
            long expectedSequence = fault == Fault.BEFORE ? 0 : 1;
            assertCursor(port, meeting, expectedSequence, expectedSequence * 20, null);
            assertFrames(port, meeting, fault == Fault.BEFORE ? List.of() : List.of(frame));
            assertEquals(fault == Fault.AFTER,
                    assertInstanceOf(DurableAudioInputPort.Stored.class,
                            port.append(binding, frame)).replayed());

            DurableAudioInputPort.Completion declaration = completion(1, 20);
            injected.set(false);
            assertInstanceOf(DurableAudioInputPort.Unknown.class,
                    port(faultOnce(source, fault, injected)).complete(binding, declaration));
            assertTrue(injected.get(), "completion fault must reach commit");
            assertCursor(port, meeting, 1, 20, fault == Fault.BEFORE ? null : declaration);
            assertEquals(fault == Fault.AFTER,
                    assertInstanceOf(DurableAudioInputPort.Stored.class,
                            port.complete(binding, declaration)).replayed());
            assertCursor(port, meeting, 1, 20, declaration);
        }
    }

    @Test
    void frameInsertThenCursorUpdateFailureRollsBackBothFacts() throws Exception {
        String suffix = "audio_2c_atomic";
        String meeting = T15CreateRecoveryFixtures.meetingId(suffix);
        DataSource source = source();
        prepareMeeting(source, suffix);
        JdbcDurableAudioInputPort port = port(source);
        DurableAudioInputPort.WriterBinding binding = bind(port, meeting);
        AtomicBoolean injected = new AtomicBoolean();

        assertInstanceOf(DurableAudioInputPort.Unavailable.class,
                port(failCursorUpdateOnce(source, injected)).append(
                        binding, frame(1, 0, (byte) 9)));
        assertTrue(injected.get(), "failure must occur after frame INSERT");
        assertEquals(0, frameRows(source, meeting));
        assertCursor(port, meeting, 0, 0, null);
        assertFrames(port, meeting, List.of());
        assertEquals(false, assertInstanceOf(DurableAudioInputPort.Stored.class,
                port.append(binding, frame(1, 0, (byte) 9))).replayed());
        assertEquals(1, frameRows(source, meeting));
    }

    /** 由专属脚本在 PostgreSQL 真正 stop/start 后的全新测试 JVM 调用。 */
    @Test
    void readCommittedFramesAfterPostgresRestart() throws Exception {
        JdbcDurableAudioInputPort port = port(source());
        assertCursor(port, MEETING, 2, 40, completion(2, 40));
        assertFrames(port, MEETING, List.of(frame(1, 0, (byte) 1), frame(2, 20, (byte) 3)));
        assertEquals(3, epoch(source(), MEETING));
    }

    static JdbcDurableAudioInputPort port(DataSource source) {
        return new JdbcDurableAudioInputPort(source, T15CreateRecoveryFixtures.DEPLOYMENT);
    }

    static PGSimpleDataSource source() {
        return T15CreateRecoveryFixtures.dataSource();
    }

    static MeetingAudioFrame frame(long sequence, long start, byte fill) {
        byte[] audio = new byte[640];
        Arrays.fill(audio, fill);
        return new MeetingAudioFrame(sequence, start, 20, audio, null, 0, 0, 0, Set.of());
    }

    static DurableAudioInputPort.Completion completion(long sequence, long end) {
        return new DurableAudioInputPort.Completion(sequence, end, "", 0, "complete-2c");
    }

    private static void prepareMeeting(DataSource source, String suffix) throws Exception {
        T15CreateRecoveryFixtures.insertReserve(source, suffix);
        assertInstanceOf(CompleteMeetingCreationUnitOfWork.Success.class,
                T15CreateRecoveryFixtures.unitOfWork(source).create(
                        T15CreateRecoveryFixtures.request(suffix, "audio-2c-" + suffix,
                                null, "test-token")));
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE meeting SET state='STARTING', input_state='INPUT_OPEN' WHERE meeting_id=?")) {
            statement.setString(1, T15CreateRecoveryFixtures.meetingId(suffix));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static DurableAudioInputPort.WriterBinding bind(JdbcDurableAudioInputPort port,
            String meeting) {
        return assertInstanceOf(DurableAudioInputPort.Bound.class,
                port.bindWriter(meeting,
                        DurableAudioInputPort.WriterConnectionId.newConnection())).binding();
    }

    private static void assertCursor(JdbcDurableAudioInputPort port, String meeting,
            long sequence, long position, DurableAudioInputPort.Completion completion) {
        DurableAudioInputPort.Cursor cursor = assertInstanceOf(DurableAudioInputPort.CursorFound.class,
                port.readCursor(meeting)).cursor();
        assertEquals(sequence, cursor.acceptedStreamSequence());
        assertEquals(position, cursor.acceptedAudioPositionMs());
        assertEquals(completion, cursor.completion());
    }

    private static void assertFrames(JdbcDurableAudioInputPort port, String meeting,
            List<MeetingAudioFrame> expected) throws Exception {
        DurableAudioInputPort.FramePage page = assertInstanceOf(DurableAudioInputPort.FramePage.class,
                port.replay(meeting, 0, 10));
        assertEquals(expected.size(), page.frames().size());
        assertEquals(expected.size(), page.acceptedStreamSequence());
        for (int i = 0; i < expected.size(); i++) {
            MeetingAudioFrame actual = page.frames().get(i);
            assertEquals(expected.get(i).streamSequence(), actual.streamSequence());
            assertEquals(expected.get(i).startPositionMs(), actual.startPositionMs());
            assertEquals(expected.get(i).durationMs(), actual.durationMs());
            assertArrayEquals(expected.get(i).audio(), actual.audio());
            assertArrayEquals(AudioFrameContentDigest.sha256(expected.get(i)),
                    AudioFrameContentDigest.sha256(actual));
        }
    }

    private static void assertReject(DurableAudioInputPort.Rejection expected, Object result) {
        assertEquals(expected, assertInstanceOf(DurableAudioInputPort.Rejected.class, result).reason());
    }

    private static long epoch(DataSource source, String meeting) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT writer_epoch FROM meeting_audio_input WHERE meeting_id=?")) {
            statement.setString(1, meeting);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static long inputRows(DataSource source, String meeting) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM meeting_audio_input WHERE meeting_id=?")) {
            statement.setString(1, meeting);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static long frameRows(DataSource source, String meeting) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM meeting_audio_frame WHERE meeting_id=?")) {
            statement.setString(1, meeting);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static Process startProbe(int port, String role) throws Exception {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path")),
                JdbcAudioInputWriterProbe.class.getName(), Integer.toString(port), role)
                .redirectErrorStream(true).start();
    }

    private static Probe acceptProbe(ServerSocket server, Process first, Process second)
            throws Exception {
        Socket socket = server.accept();
        socket.setSoTimeout((int) PROCESS_TIMEOUT.toMillis());
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        String role = in.readUTF();
        assertTrue(role.equals("a") || role.equals("b"));
        return new Probe(role, socket, in, out, role.equals("a") ? first : second);
    }

    private static long lockWaiters(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM pg_stat_activity
                      WHERE datname=current_database()
                        AND application_name IN ('t16-audio-probe-a', 't16-audio-probe-b')
                        AND state='active' AND wait_event_type='Lock'
                     """);
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private record Probe(String role, Socket socket, DataInputStream in,
                         DataOutputStream out, Process process) { }

    private enum Fault { BEFORE, AFTER }

    /** 只在 commit 边界注入一次；BEFORE 由关闭连接触发 PG 回滚。 */
    private static DataSource faultOnce(DataSource delegate, Fault fault, AtomicBoolean injected) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object value = forward(delegate, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (p, call, arguments) -> {
                                if (call.getName().equals("commit")
                                        && injected.compareAndSet(false, true)) {
                                    if (fault == Fault.AFTER) forward(connection, call, arguments);
                                    throw new SQLException("injected commit confirmation loss", "08006");
                                }
                                return forward(connection, call, arguments);
                            });
                });
    }

    private static DataSource failCursorUpdateOnce(DataSource delegate, AtomicBoolean injected) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object value = forward(delegate, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (p, call, arguments) -> {
                                Object statement = forward(connection, call, arguments);
                                if (!call.getName().equals("prepareStatement")
                                        || !((String) arguments[0]).contains(
                                        "UPDATE meeting_audio_input SET accepted_stream_sequence")) {
                                    return statement;
                                }
                                PreparedStatement prepared = (PreparedStatement) statement;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class},
                                        (q, operation, values) -> {
                                            if (operation.getName().equals("executeUpdate")
                                                    && injected.compareAndSet(false, true)) {
                                                throw new SQLException("injected cursor update failure", "08006");
                                            }
                                            return forward(prepared, operation, values);
                                        });
                            });
                });
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
