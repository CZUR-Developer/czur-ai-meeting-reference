package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner.Plan;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationCheckpoint.ArtifactMaterial;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalUsageFactStore;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.WorkflowStepStore.ClaimRequest;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.artifact.ArtifactStore.CompleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StageStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StoreState;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;
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
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.czur.reference.domain.capability.CapabilityIds.SUMMARY_STRUCTURED;
import static com.czur.reference.domain.capability.CapabilityIds.TRANSCRIPT_FINAL;

/**
 * 被 T14-08-12 门禁启动的真实子 JVM 探针。
 *
 * <p>探针不接受数据库密码、lease token、业务标识或文件根命令行参数；这些测试配置
 * 通过继承环境和权限为 0600 的临时控制文件传递。stdout/stderr 只输出固定 action 和
 * 稳定状态，不输出 URL、路径、会议、binding、checksum 或 token。</p>
 */
public final class T14FinalizationRecoveryProcessProbe {
    static final String MEETING_ID = "meeting-t14-finalization-recovery";
    static final Instant ACCEPTED_AT = Instant.parse("2026-09-28T03:00:00.123456Z");
    static final Instant FIRST_CLAIM_AT = ACCEPTED_AT.plusSeconds(1);
    static final Duration LEASE_DURATION = Duration.ofSeconds(10);
    static final String SOURCE_DIGEST =
            "sha256:9d78a1a5ae92341c35fdfd4678f7c51d"
                    + "8319e7fe81d9a66e39e3b6a9c38be62f";

    private static final String DATABASE_URL_ENV = "T14_FINALIZATION_RECOVERY_DATABASE_URL";
    private static final String ARTIFACT_ROOT_ENV = "T14_FINALIZATION_RECOVERY_ARTIFACT_ROOT";
    private static final String CONTROL_FILE_ENV = "T14_FINALIZATION_RECOVERY_CONTROL_FILE";
    private static final String EXECUTION_FACT_ENV =
            "T14_FINALIZATION_RECOVERY_EXECUTION_FACT_JSON";
    private static final long ARTIFACT_LIMIT = 1024 * 1024;
    private static final int CONTROL_MAGIC = 0x54313452; // T14R
    private static final int CONTROL_VERSION = 1;
    private static final int MAX_CONTROL_BYTES = 4096;
    private static final int MAX_CONTROL_FIELD_BYTES = 512;
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private T14FinalizationRecoveryProcessProbe() {
    }

    public static void main(String[] arguments) {
        // 子进程输出是门禁协议，不继承领域/Store 的常规 JUL 日志，避免把未来新增的
        // 业务字段或环境细节混入跨进程控制通道。
        java.util.logging.LogManager.getLogManager().reset();
        String action = arguments.length == 1 ? arguments[0] : "invalid";
        try {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("one action is required");
            }
            switch (action) {
                case "crash-after-ready" -> crashAfterReady();
                case "recover" -> recover();
                case "verify-drift" -> verifyDrift();
                default -> throw new IllegalArgumentException("unknown action");
            }
            System.out.println("PROBE_OK action=" + action);
        } catch (Throwable failure) {
            System.err.println("PROBE_FAILED action=" + action
                    + " causeType=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void crashAfterReady() throws Exception {
        DataSource dataSource = dataSource();
        FinalizationInputFact input = readDurableInput(dataSource);
        FinalizationExecutionFact executionFact = readFrozenExecutionFact();
        Plan plan = derive(input, executionFact, lockedReservation());
        JdbcWorkflowStepStore workflow = new JdbcWorkflowStepStore(dataSource);
        require(workflow.register(plan.workflowStep()).status()
                == WorkflowStepStore.RegisterStatus.APPLIED);
        LeaseToken lease = onlyClaim(workflow, "worker-a", FIRST_CLAIM_AT);
        require(persistFinalUsage(dataSource, plan.finalUsage())
                == FinalUsageFactStore.PersistStatus.APPLIED);

        PersistentFileSystemMockArtifactStore artifacts = artifactStore();
        for (ArtifactMaterial artifact : plan.artifacts()) {
            var prepared = artifacts.createWriteIntent(artifact.spec());
            require(prepared.status() == PrepareStatus.APPLIED);
            require(artifacts.writeContent(prepared.intent(), artifact.openContent()).status()
                    == StageStatus.APPLIED);
            require(artifacts.completeWrite(prepared.intent(), artifact.claim()).status()
                    == CompleteStatus.APPLIED);
            require(artifacts.stat(prepared.intent().binding()).state() == StoreState.READY);
        }

        writeLeaseControl(controlFile(), lease);
        System.out.println("PROBE_CRASH_READY workflow=LEASED artifacts=READY");
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(47);
    }

    private static void recover() throws Exception {
        DataSource dataSource = dataSource();
        JdbcWorkflowStepStore workflow = new JdbcWorkflowStepStore(dataSource);
        WorkflowStep stored = workflow.find(
                FinalizationCheckpoint.workflowKey(MEETING_ID)).orElseThrow();
        require(stored.status() == WorkflowStep.Status.LEASED);
        FinalizationCheckpoint checkpoint = decodeStoredCheckpoint(stored);

        // 第二 JVM 只读交叉核对各权威事实；不得用这些事实重新规划或读取
        // 当前 Profile/Reservation version。后续副作用材料全部来自 checkpoint。
        FinalizationInputFact durableInput = readDurableInput(dataSource);
        FinalUsageFact durableUsage = readFinalUsage(dataSource);
        FinalizationExecutionFact executionFact = readFrozenExecutionFact();
        require(checkpoint.meetingId().equals(durableInput.meetingId()));
        require(checkpoint.meetingId().equals(executionFact.meetingId()));
        require(checkpoint.inputBinding().equals(durableInput.inputBinding()));
        require(checkpoint.inputFactDigest().equals(
                FinalizationCheckpoint.inputFactDigest(durableInput)));
        require(checkpoint.executionFactDigest().equals(
                FinalizationExecutionFactJson.sha256(executionFact)));
        require(checkpoint.finalUsageFact().equals(durableUsage));
        require(checkpoint.commitUsageRequest().actual().equals(durableUsage.actual()));
        require(checkpoint.commitPlan().sourceInputBinding().equals(
                durableUsage.sourceInputBinding()));
        require(checkpoint.commitPlan().sourceInputDigest().equals(
                durableUsage.sourceInputDigest()));
        require(checkpoint.commitPlan().sourceAcceptedAt().equals(
                durableUsage.sourceAcceptedAt()));

        LeaseToken oldLease = readLeaseControl(controlFile(), stored.key());
        require(persistFinalUsage(dataSource, checkpoint.finalUsageFact())
                == FinalUsageFactStore.PersistStatus.REPLAYED);

        PersistentFileSystemMockArtifactStore artifacts = artifactStore();
        for (ArtifactMaterial artifact : checkpoint.artifacts()) {
            var prepared = artifacts.createWriteIntent(artifact.spec());
            require(prepared.status() == PrepareStatus.REPLAYED);
            require(artifacts.writeContent(prepared.intent(), artifact.openContent()).status()
                    == StageStatus.REPLAYED);
            require(artifacts.completeWrite(prepared.intent(), artifact.claim()).status()
                    == CompleteStatus.REPLAYED);
            require(artifacts.stat(prepared.intent().binding()).state() == StoreState.READY);
            try (var read = artifacts.openRead(prepared.intent().binding())) {
                byte[] content = read.content().readAllBytes();
                require(content.length == artifact.claim().size());
                require(java.util.Arrays.equals(
                        artifact.canonicalContentBytes(),
                        content));
            }
        }

        Instant reclaimedAt = oldLease.expiresAt();
        LeaseToken recoveryLease = onlyClaim(workflow, "worker-b", reclaimedAt);
        require(workflow.complete(oldLease, reclaimedAt).status()
                == WorkflowStepStore.CompleteStatus.NOT_OWNER);
        require(workflow.complete(recoveryLease, reclaimedAt.plusSeconds(1)).status()
                == WorkflowStepStore.CompleteStatus.COMPLETED);
        require(workflow.complete(oldLease, reclaimedAt.plusSeconds(2)).status()
                == WorkflowStepStore.CompleteStatus.ALREADY_COMPLETED);
        Files.delete(controlFile());
        System.out.println("PROBE_RECOVERED checkpoint=DECODED facts=VERIFIED"
                + " usage=REPLAYED artifacts=REPLAYED"
                + " staleBefore=NOT_OWNER workflow=COMPLETED staleAfter=ALREADY_COMPLETED");
    }

    private static void verifyDrift() throws Exception {
        DataSource dataSource = dataSource();
        FinalizationInputFact reread = readDurableInput(dataSource);
        Plan drifted = derive(reread, true);
        JdbcWorkflowStepStore workflow = new JdbcWorkflowStepStore(dataSource);
        WorkflowStep stored = workflow.find(
                FinalizationCheckpoint.workflowKey(MEETING_ID)).orElseThrow();
        FinalizationCheckpoint original = decodeStoredCheckpoint(stored);
        require(stored.key().equals(drifted.workflowStep().key()));
        require(!stored.contentDigest()
                .equals(drifted.workflowStep().contentDigest()));
        require(workflow.register(drifted.workflowStep()).status()
                == WorkflowStepStore.RegisterStatus.CONFLICT);

        PersistentFileSystemMockArtifactStore artifacts = artifactStore();
        for (int index = 0; index < original.artifacts().size(); index++) {
            ArtifactMaterial winner = original.artifacts().get(index);
            ArtifactMaterial conflict = drifted.artifacts().get(index);
            require(winner.spec().artifactId().equals(conflict.spec().artifactId()));
            require(!winner.spec().expectedChecksum().equals(conflict.spec().expectedChecksum()));
            var result = artifacts.createWriteIntent(conflict.spec());
            require(result.status() == PrepareStatus.CONFLICT);
            require(result.intent().spec().equals(winner.spec()));
            try (var read = artifacts.openRead(result.intent().binding())) {
                require(java.util.Arrays.equals(
                        winner.canonicalContent().getBytes(StandardCharsets.UTF_8),
                        read.content().readAllBytes()));
            }
        }
        System.out.println("PROBE_DRIFT workflow=CONFLICT artifacts=CONFLICT");
    }

    static FinalizationInputFact fixtureInput() {
        return new FinalizationInputFact(
                MEETING_ID,
                "input-binding-t14-v1",
                19,
                98_765,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                SOURCE_DIGEST);
    }

    static Plan derive(FinalizationInputFact input, boolean drifted) {
        return derive(input, fixtureExecutionFact(drifted), lockedReservation());
    }

    private static Plan derive(
            FinalizationInputFact input,
            FinalizationExecutionFact executionFact,
            UsageReservation reservation) {
        return new DeterministicMockFinalizationPlanner().derive(
                "t14-recovery-deployment",
                "LEGACY_UNOWNED",
                input,
                executionFact,
                reservation);
    }

    static FinalizationExecutionFact fixtureExecutionFact(boolean drifted) {
        ProductProfile profile = profile(drifted);
        CapabilityPlan capabilityPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(
                        "t14-deterministic-mock",
                        Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED)),
                profile,
                Set.of(SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                DeploymentLimits.NONE,
                11L);
        return FinalizationExecutionFact.freeze(
                MEETING_ID,
                ACCEPTED_AT.minusSeconds(60),
                null,
                profile,
                capabilityPlan);
    }

    private static UsageReservation lockedReservation() {
        Instant createdAt = ACCEPTED_AT.minusSeconds(120);
        MeetingGrant grant = new MeetingGrant(
                "grant-t14-finalization",
                "t14-recovery-deployment",
                "LEGACY_UNOWNED",
                "meeting-minutes/t14-mock-v1",
                4,
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                300_000,
                createdAt.minusSeconds(1),
                ACCEPTED_AT.plusSeconds(3600));
        return UsageReservation.reserved(
                        "reservation-t14-finalization",
                        grant,
                        MEETING_ID,
                        new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 120_000),
                        createdAt,
                        ACCEPTED_AT.plusSeconds(1800))
                .bind(MEETING_ID, createdAt.plusSeconds(1), 0);
    }

    private static ProductProfile profile(boolean drifted) {
        return new ProductProfile(
                "meeting-minutes/t14-mock-v1",
                4,
                Set.of(TRANSCRIPT_FINAL),
                Set.of(SUMMARY_STRUCTURED),
                Map.of(SUMMARY_STRUCTURED, Set.of(TRANSCRIPT_FINAL)),
                Map.of(),
                Map.of(),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("mock-transcript-v1"),
                        SUMMARY_STRUCTURED, List.of(
                                drifted ? "mock-summary-drift-v1" : "mock-summary-v1")),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        SUMMARY_STRUCTURED, List.of("summary.structured")));
    }

    private static LeaseToken onlyClaim(
            JdbcWorkflowStepStore workflow,
            String owner,
            Instant now) {
        List<WorkflowStepStore.ClaimedStep> claimed = workflow.claim(new ClaimRequest(
                Set.of("mock-finalization"), owner, now, LEASE_DURATION, 1));
        require(claimed.size() == 1);
        return claimed.getFirst().lease();
    }

    private static FinalUsageFactStore.PersistStatus persistFinalUsage(
            DataSource dataSource,
            FinalUsageFact fact) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockMeeting(connection, fact.meetingId());
                FinalUsageFactStore.PersistResult result =
                        JdbcFinalUsageFactStore.persist(connection, fact);
                connection.commit();
                return result.status();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static FinalizationInputFact readDurableInput(DataSource dataSource)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<FinalizationInputFact> fact =
                        JdbcDurableInputFactStore.find(connection, MEETING_ID);
                connection.rollback();
                return fact.orElseThrow(() ->
                        new IllegalStateException("durable input is unavailable"));
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static FinalUsageFact readFinalUsage(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                FinalUsageFact fact = JdbcFinalUsageFactStore.find(connection, MEETING_ID)
                        .orElseThrow(() ->
                                new IllegalStateException("final usage is unavailable"));
                connection.rollback();
                return fact;
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static FinalizationExecutionFact readFrozenExecutionFact() {
        return FinalizationExecutionFactJson.decode(requiredEnvironment(EXECUTION_FACT_ENV));
    }

    private static FinalizationCheckpoint decodeStoredCheckpoint(WorkflowStep stored) {
        require(stored.key().equals(FinalizationCheckpoint.workflowKey(MEETING_ID)));
        require(FinalizationCheckpoint.CHECKPOINT_TYPE.equals(stored.checkpointType()));
        FinalizationCheckpoint checkpoint =
                FinalizationCheckpointJson.decode(stored.canonicalPayload());
        require(stored.canonicalPayload().equals(
                FinalizationCheckpointJson.encode(checkpoint)));
        require(stored.contentDigest().equals(
                FinalizationCheckpointJson.sha256(checkpoint)));
        WorkflowStep definition = FinalizationCheckpointJson.workflowStep(checkpoint);
        require(definition.key().equals(stored.key()));
        require(definition.checkpointType().equals(stored.checkpointType()));
        require(definition.canonicalPayload().equals(stored.canonicalPayload()));
        require(definition.contentDigest().equals(stored.contentDigest()));
        return checkpoint;
    }

    private static void lockMeeting(Connection connection, String meetingId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT meeting_id FROM meeting WHERE meeting_id=? FOR UPDATE")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
                require(meetingId.equals(rows.getString(1)));
                require(!rows.next());
            }
        }
    }

    private static PersistentFileSystemMockArtifactStore artifactStore()
            throws Exception {
        return new PersistentFileSystemMockArtifactStore(
                absoluteEnvironmentPath(ARTIFACT_ROOT_ENV), ARTIFACT_LIMIT);
    }

    private static DataSource dataSource() {
        String url = requiredEnvironment(DATABASE_URL_ENV);
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalArgumentException("reserved database endpoint is forbidden");
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(requiredEnvironment("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"));
        return source;
    }

    private static Path controlFile() {
        return absoluteEnvironmentPath(CONTROL_FILE_ENV);
    }

    private static Path absoluteEnvironmentPath(String name) {
        Path path = Path.of(requiredEnvironment(name));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("probe path must be absolute");
        }
        return path.normalize();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required probe environment is unavailable");
        }
        return value;
    }

    private static void writeLeaseControl(Path path, LeaseToken lease) throws Exception {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("lease control already exists");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(CONTROL_MAGIC);
            output.writeInt(CONTROL_VERSION);
            writeString(output, lease.owner());
            writeString(output, lease.token());
            writeString(output, lease.claimedAt().toString());
            writeString(output, lease.expiresAt().toString());
            output.writeLong(lease.leaseVersion());
        }
        byte[] bytes = buffer.toByteArray();
        require(bytes.length <= MAX_CONTROL_BYTES);
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            Files.createFile(path);
        }
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer content = ByteBuffer.wrap(bytes);
            while (content.hasRemaining()) {
                channel.write(content);
            }
            channel.force(true);
        }
        try {
            Files.setPosixFilePermissions(path, PRIVATE_FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 环境仍拒绝 symlink/非普通文件；Linux 总门禁另外精确验证 0600。
        }
    }

    private static LeaseToken readLeaseControl(
            Path path,
            WorkflowStep.Key workflowKey) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) <= 0
                || Files.size(path) > MAX_CONTROL_BYTES) {
            throw new IllegalStateException("lease control is unsafe");
        }
        try {
            if (!Files.getPosixFilePermissions(path).equals(PRIVATE_FILE_PERMISSIONS)) {
                throw new IllegalStateException("lease control permissions are unsafe");
            }
        } catch (UnsupportedOperationException ignored) {
            // Linux 总门禁要求精确 0600；其它文件系统保留结构和 NOFOLLOW 校验。
        }
        try (InputStream bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
             DataInputStream input = new DataInputStream(bytes)) {
            if (input.readInt() != CONTROL_MAGIC || input.readInt() != CONTROL_VERSION) {
                throw new IllegalStateException("lease control version is invalid");
            }
            String owner = readString(input);
            String token = readString(input);
            Instant claimedAt = Instant.parse(readString(input));
            Instant expiresAt = Instant.parse(readString(input));
            LeaseToken lease = new LeaseToken(
                    workflowKey,
                    owner,
                    token,
                    expiresAt,
                    claimedAt,
                    input.readLong());
            if (input.read() != -1) {
                throw new IllegalStateException("lease control contains trailing data");
            }
            return lease;
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 0 || bytes.length > MAX_CONTROL_FIELD_BYTES) {
            throw new IOException("lease control field length is invalid");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_CONTROL_FIELD_BYTES) {
            throw new IOException("lease control field length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("lease control is truncated");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("probe assertion failed");
        }
    }
}
