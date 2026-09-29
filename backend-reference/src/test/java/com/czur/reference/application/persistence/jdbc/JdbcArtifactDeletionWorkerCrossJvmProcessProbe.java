package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.port.ArtifactDeletionItemUnitOfWork;
import com.czur.reference.application.port.ArtifactDeletionTerminalUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real JVM crash probe for the artifact-deletion item and terminal transactions. */
public final class JdbcArtifactDeletionWorkerCrossJvmProcessProbe {
    private static final String DATABASE_ENV = "T14_DELETION_CROSS_JVM_DATABASE_URL";
    private static final String ROOT_ENV = "T14_DELETION_CROSS_JVM_ARTIFACT_ROOT";
    private static final String CONTROL_ENV = "T14_DELETION_CROSS_JVM_CONTROL_FILE";
    private static final int CONTROL_MAGIC = 0x54314457; // T1DW
    private static final int CONTROL_VERSION = 1;
    private static final int MAX_CONTROL_BYTES = 4096;
    private static final int MAX_FIELD_BYTES = 512;
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Instant ACCEPTED = Instant.parse("2026-09-29T10:00:00.123456Z");
    private static final Instant FIRST_CLAIM = ACCEPTED.plusSeconds(1);
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static String phase = "START";

    private JdbcArtifactDeletionWorkerCrossJvmProcessProbe() {
    }

    public static void main(String[] arguments) {
        java.util.logging.LogManager.getLogManager().reset();
        String action = arguments.length == 1 ? arguments[0] : "invalid";
        try {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("one action is required");
            }
            switch (action) {
                case "crash-item" -> crashItem();
                case "crash-terminal" -> crashTerminal();
                case "recover-item" -> recoverItem();
                case "recover-terminal" -> recoverTerminal();
                default -> throw new IllegalArgumentException("unknown action");
            }
        } catch (Throwable failure) {
            System.err.println("PROBE_FAILED action=" + safeAction(action)
                    + " phase=" + phase
                    + " causeType=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void crashItem() throws Exception {
        phase = "FIXTURE";
        Fixture fixture = fixture();
        phase = "CLAIM";
        WorkflowStepStore.LeaseToken lease = claim(fixture.workflow(), "deletion-a", FIRST_CLAIM);
        phase = "CONTROL_WRITE";
        writeLeaseControl(controlFile(), lease);
        phase = "ITEM_COMMIT_HALT";
        new JdbcArtifactDeletionItemUnitOfWork(
                haltingDataSource(fixture.dataSource()), fixture.artifacts()).delete(
                        new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                                lease, 0, fixture.spec(), FIRST_CLAIM.plusSeconds(1)));
        throw new AssertionError("item commit halt boundary was not reached");
    }

    private static void crashTerminal() throws Exception {
        phase = "FIXTURE";
        Fixture fixture = fixture();
        phase = "CLAIM";
        WorkflowStepStore.LeaseToken lease = claim(fixture.workflow(), "deletion-a", FIRST_CLAIM);
        phase = "CONTROL_WRITE";
        writeLeaseControl(controlFile(), lease);
        phase = "ITEM_DELETE";
        ArtifactDeletionItemUnitOfWork.Result item =
                new JdbcArtifactDeletionItemUnitOfWork(
                        fixture.dataSource(), fixture.artifacts()).delete(
                                new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                                        lease, 0, fixture.spec(), FIRST_CLAIM.plusSeconds(1)));
        assertEquals(ArtifactDeletionItemUnitOfWork.SuccessCode.DELETED,
                assertInstanceOf(ArtifactDeletionItemUnitOfWork.Success.class, item).code());
        phase = "TERMINAL_COMMIT_HALT";
        new JdbcArtifactDeletionTerminalUnitOfWork(
                haltingDataSource(fixture.dataSource())).complete(
                        new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                                lease, FIRST_CLAIM.plusSeconds(2)));
        throw new AssertionError("terminal commit halt boundary was not reached");
    }

    private static void recoverItem() throws Exception {
        phase = "FIXTURE";
        Fixture fixture = fixture();
        phase = "CONTROL_READ";
        WorkflowStepStore.LeaseToken stale = readLeaseControl(controlFile(), fixture.key());
        phase = "RECLAIM";
        WorkflowStepStore.LeaseToken current = claim(
                fixture.workflow(), "deletion-b", stale.expiresAt());
        Instant activeAt = current.claimedAt().plusSeconds(1);
        Instant staleAt = stale.expiresAt().minusNanos(1_000);
        phase = "STALE_ITEM";
        ArtifactDeletionItemUnitOfWork.Result staleItem =
                new JdbcArtifactDeletionItemUnitOfWork(
                        fixture.dataSource(), fixture.artifacts()).delete(
                                new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                                        stale, 0, fixture.spec(), staleAt));
        assertEquals(ArtifactDeletionItemUnitOfWork.ErrorCode.NOT_OWNER,
                assertInstanceOf(ArtifactDeletionItemUnitOfWork.Failure.class, staleItem).code());
        phase = "STALE_TERMINAL";
        ArtifactDeletionTerminalUnitOfWork.Result staleTerminal =
                new JdbcArtifactDeletionTerminalUnitOfWork(fixture.dataSource()).complete(
                        new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(stale, staleAt));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.ErrorCode.NOT_OWNER,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Failure.class,
                        staleTerminal).code());

        phase = "ITEM_REPLAY";
        ArtifactDeletionItemUnitOfWork.Result replay =
                new JdbcArtifactDeletionItemUnitOfWork(
                        fixture.dataSource(), fixture.artifacts()).delete(
                                new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                                        current, 0, fixture.spec(), activeAt));
        assertEquals(ArtifactDeletionItemUnitOfWork.SuccessCode.REPLAYED,
                assertInstanceOf(ArtifactDeletionItemUnitOfWork.Success.class, replay).code());
        phase = "TERMINAL_COMPLETE";
        ArtifactDeletionTerminalUnitOfWork.Result completed =
                new JdbcArtifactDeletionTerminalUnitOfWork(fixture.dataSource()).complete(
                        new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                                current, activeAt.plusSeconds(1)));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.SuccessCode.COMPLETED,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class,
                        completed).code());
        phase = "CONTROL_DELETE";
        Files.delete(controlFile());
        System.out.println("PROBE_RECOVERED scenario=item result=COMPLETED"
                + " replay=REPLAYED stale=NOT_OWNER");
    }

    private static void recoverTerminal() throws Exception {
        phase = "FIXTURE";
        Fixture fixture = fixture();
        phase = "CONTROL_READ";
        WorkflowStepStore.LeaseToken stale = readLeaseControl(controlFile(), fixture.key());
        phase = "RECLAIM_CHECK";
        List<WorkflowStepStore.ClaimedStep> claimed = fixture.workflow().claim(
                new WorkflowStepStore.ClaimRequest(
                        Set.of(ArtifactDeletionPlan.STEP_TYPE), "deletion-b",
                        stale.expiresAt(), LEASE, 1));
        assertTrue(claimed.isEmpty(), "a committed terminal workflow must not be reclaimed");
        Instant staleAt = stale.expiresAt().minusNanos(1_000);
        phase = "TERMINAL_RECOVER";
        ArtifactDeletionTerminalUnitOfWork.Result replay =
                new JdbcArtifactDeletionTerminalUnitOfWork(fixture.dataSource()).recover(
                        new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(stale, staleAt));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.SuccessCode.REPLAYED,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class, replay).code());
        phase = "STALE_ITEM";
        ArtifactDeletionItemUnitOfWork.Result staleItem =
                new JdbcArtifactDeletionItemUnitOfWork(
                        fixture.dataSource(), fixture.artifacts()).delete(
                                new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                                        stale, 0, fixture.spec(), staleAt));
        assertEquals(ArtifactDeletionItemUnitOfWork.ErrorCode.ALREADY_COMPLETED,
                assertInstanceOf(ArtifactDeletionItemUnitOfWork.Failure.class, staleItem).code());
        phase = "STALE_TERMINAL";
        ArtifactDeletionTerminalUnitOfWork.Result staleComplete =
                new JdbcArtifactDeletionTerminalUnitOfWork(fixture.dataSource()).complete(
                        new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(stale, staleAt));
        assertEquals(ArtifactDeletionTerminalUnitOfWork.SuccessCode.REPLAYED,
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class,
                        staleComplete).code());
        phase = "CONTROL_DELETE";
        Files.delete(controlFile());
        System.out.println("PROBE_RECOVERED scenario=terminal result=IDLE"
                + " replay=REPLAYED stale=TERMINAL");
    }

    private static Fixture fixture() throws Exception {
        DataSource source = dataSource();
        WorkflowStepStore workflow = new JdbcWorkflowStepStore(source);
        WorkflowStep row = onlyWorkflow(source);
        ArtifactDeletionPlan plan = ArtifactDeletionPlanJson.decode(row.canonicalPayload());
        return new Fixture(source, workflow, row.key(), plan.targets().getFirst().spec(),
                new PersistentFileSystemMockArtifactStore(root(), 4096));
    }

    private static WorkflowStep row(DataSource source, WorkflowStep.Key key) {
        return new JdbcWorkflowStepStore(source).find(key).orElseThrow();
    }

    private static WorkflowStep onlyWorkflow(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT meeting_id, step_type, step_key FROM workflow_step")) {
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("workflow is missing");
                WorkflowStep.Key key = new WorkflowStep.Key(
                        rows.getString(1), rows.getString(2), rows.getString(3));
                if (rows.next()) throw new IllegalStateException("workflow is not unique");
                return row(source, key);
            }
        }
    }

    private static WorkflowStepStore.LeaseToken claim(
            WorkflowStepStore workflow, String owner, Instant now) {
        List<WorkflowStepStore.ClaimedStep> claimed = workflow.claim(
                new WorkflowStepStore.ClaimRequest(
                        Set.of(ArtifactDeletionPlan.STEP_TYPE), owner, now, LEASE, 1));
        if (claimed.size() != 1) throw new IllegalStateException("workflow claim count mismatch");
        return claimed.getFirst().lease();
    }

    private static DataSource haltingDataSource(DataSource delegate) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (source, method, args) -> {
                    Object value = method.invoke(delegate, args);
                    if (!"getConnection".equals(method.getName())) return value;
                    Connection connection = (Connection) value;
                    return java.lang.reflect.Proxy.newProxyInstance(
                            Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, call, callArgs) -> {
                                if ("commit".equals(call.getName())) {
                                    call.invoke(connection, callArgs);
                                    System.out.println("PROBE_CRASH checkpoint=COMMIT");
                                    System.out.flush();
                                    System.err.flush();
                                    Runtime.getRuntime().halt(47);
                                }
                                return call.invoke(connection, callArgs);
                            });
                });
    }

    private static DataSource dataSource() {
        String url = required(DATABASE_ENV);
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalArgumentException("reserved database endpoint is forbidden");
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        return source;
    }

    private static Path root() {
        return absolutePath(ROOT_ENV);
    }

    private static Path controlFile() {
        return absolutePath(CONTROL_ENV);
    }

    private static Path absolutePath(String variable) {
        Path path = Path.of(required(variable));
        if (!path.isAbsolute()) throw new IllegalArgumentException("probe path is not absolute");
        return path.normalize();
    }

    private static String required(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing environment");
        return value;
    }

    private static String safeAction(String value) {
        return value != null && value.matches("[a-z-]{1,32}") ? value : "invalid";
    }

    private static void writeLeaseControl(Path path, WorkflowStepStore.LeaseToken lease)
            throws Exception {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("control exists");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(CONTROL_MAGIC);
            output.writeInt(CONTROL_VERSION);
            writeString(output, lease.owner());
            writeString(output, lease.token());
            writeString(output, lease.claimedAt().toString());
            writeString(output, lease.expiresAt().toString());
            output.writeLong(lease.leaseVersion());
        }
        if (bytes.size() > MAX_CONTROL_BYTES) throw new IOException("control too large");
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS); }
        catch (UnsupportedOperationException ignored) { }
    }

    private static WorkflowStepStore.LeaseToken readLeaseControl(
            Path path, WorkflowStep.Key key) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path) || Files.size(path) <= 0
                || Files.size(path) > MAX_CONTROL_BYTES) throw new IllegalStateException("unsafe control");
        try { if (!Files.getPosixFilePermissions(path).equals(PRIVATE_PERMISSIONS))
                throw new IllegalStateException("unsafe control permissions"); }
        catch (UnsupportedOperationException ignored) { }
        try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
             DataInputStream input = new DataInputStream(stream)) {
            if (input.readInt() != CONTROL_MAGIC || input.readInt() != CONTROL_VERSION)
                throw new IllegalStateException("invalid control version");
            String owner = readString(input);
            String token = readString(input);
            Instant claimedAt = Instant.parse(readString(input));
            Instant expiresAt = Instant.parse(readString(input));
            WorkflowStepStore.LeaseToken lease = new WorkflowStepStore.LeaseToken(
                    key, owner, token, expiresAt, claimedAt, input.readLong());
            if (input.read() != -1) throw new IllegalStateException("control has trailing bytes");
            return lease;
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 0 || bytes.length > MAX_FIELD_BYTES) throw new IOException("field length");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FIELD_BYTES) throw new IOException("field length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated control");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record Fixture(
            DataSource dataSource,
            WorkflowStepStore workflow,
            WorkflowStep.Key key,
            WriteIntentSpec spec,
            PersistentFileSystemMockArtifactStore artifacts) {
    }
}
