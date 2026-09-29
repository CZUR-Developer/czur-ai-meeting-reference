package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.finalization.FinalizationTerminalOperationMarker;
import com.czur.reference.application.finalization.MockFinalizationWorker;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.WorkflowStepStore.ClaimRequest;
import com.czur.reference.application.port.WorkflowStepStore.ClaimedStep;
import com.czur.reference.application.port.WorkflowStepStore.CompleteResult;
import com.czur.reference.application.port.WorkflowStepStore.CompleteStatus;
import com.czur.reference.application.port.WorkflowStepStore.FailResult;
import com.czur.reference.application.port.WorkflowStepStore.FailStatus;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.WorkflowStepStore.PersistLeaseMarkerRequest;
import com.czur.reference.application.port.WorkflowStepStore.PersistLeaseMarkerResult;
import com.czur.reference.application.port.WorkflowStepStore.PersistLeaseMarkerStatus;
import com.czur.reference.application.port.WorkflowStepStore.RegisterResult;
import com.czur.reference.application.port.WorkflowStepStore.RenewResult;
import com.czur.reference.application.port.WorkflowStepStore.RenewStatus;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P2-T14-09-10 由父测试启动的真实 Worker 子进程。
 *
 * <p>命令行只携带固定动作和固定故障窗口；数据库地址、持久文件根与旧 lease
 * 控制文件路径通过进程环境交接。环境中不允许出现 checkpoint、Artifact 正文、
 * meetingId、receipt、权益幂等键或终态请求。JVM-B 会重新构造正式 JDBC Store、
 * 持久文件 Store、reference-dev 权益 Provider、Completion 和
 * {@link MockFinalizationWorker}，恢复决定全部来自 PostgreSQL 与文件系统。</p>
 *
 * <p>JVM-A 在指定线性化点打印固定、不含业务值的状态行，随即使用
 * {@link Runtime#halt(int)} 真实终止。JVM-B 的 Workflow 包装器只用于在正式
 * {@code claim} 成功后验证旧 lease 的五项写操作均被栅栏拒绝；它不代替 Worker
 * 领取、完成或失败。</p>
 */
public final class MockFinalizationWorkerCrossJvmProcessProbe {
    private static final String DATABASE_URL_ENV =
            "T14_WORKER_CROSS_JVM_DATABASE_URL";
    private static final String ARTIFACT_ROOT_ENV =
            "T14_WORKER_CROSS_JVM_ARTIFACT_ROOT";
    private static final String CONTROL_FILE_ENV =
            "T14_WORKER_CROSS_JVM_CONTROL_FILE";
    private static final int CONTROL_MAGIC = 0x54313457; // T14W
    private static final int CONTROL_VERSION = 1;
    private static final int MAX_CONTROL_BYTES = 4096;
    private static final int MAX_CONTROL_FIELD_BYTES = 512;
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private MockFinalizationWorkerCrossJvmProcessProbe() {
    }

    public static void main(String[] arguments) {
        // 子进程 stdout/stderr 是门禁协议。关闭普通 JUL handler，避免未来日志参数
        // 或第三方 JDBC 诊断把数据库、业务 identity、路径或正文带入控制通道。
        java.util.logging.LogManager.getLogManager().reset();
        String action = arguments.length > 0 ? arguments[0] : "invalid";
        String scenarioText = arguments.length > 1 ? arguments[1] : "invalid";
        try {
            if (arguments.length != 2) {
                throw new IllegalArgumentException("action and scenario are required");
            }
            Scenario scenario = Scenario.fromCli(scenarioText);
            switch (action) {
                case "crash" -> crash(scenario);
                case "recover" -> recover(scenario);
                case "verify-idle" -> verifyIdle(scenario);
                default -> throw new IllegalArgumentException("unknown action");
            }
        } catch (Throwable failure) {
            System.err.println("PROBE_FAILED action=" + safeAction(action)
                    + " scenario=" + safeAction(scenarioText)
                    + " causeType=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void crash(Scenario scenario) throws Exception {
        DataSource dataSource = dataSource();
        JdbcWorkflowStepStore jdbcWorkflow = new JdbcWorkflowStepStore(dataSource);
        WorkflowStepStore workflow = new CrashWorkflowStore(
                jdbcWorkflow, controlFile(), scenario);
        ArtifactStore realArtifacts = artifactStore();
        EntitlementProvider realEntitlement = entitlementProvider(
                dataSource, T14WorkerCrossJvmTestSupport.FIRST_CLAIM_AT);
        FinalizationCompletionUnitOfWork realCompletion =
                new JdbcFinalizationCompletionUnitOfWork(dataSource);

        ArtifactStore artifacts = scenario == Scenario.ARTIFACT_PARTIAL
                ? new CrashAfterFirstArtifactStore(realArtifacts, scenario)
                : realArtifacts;
        EntitlementProvider entitlement = switch (scenario) {
            case ENTITLEMENT_COMMITTED ->
                    new CrashAfterEntitlementProvider(realEntitlement, scenario);
            case FAILURE_MARKER -> new TerminalFailureEntitlement(realEntitlement);
            default -> realEntitlement;
        };
        FinalizationCompletionUnitOfWork completion =
                scenario == Scenario.COMPLETION_COMMITTED
                        ? new CrashAfterCompletion(realCompletion, scenario)
                        : realCompletion;

        // 正常返回说明指定 halt 点没有被触发，必须关闭式失败。
        worker(
                workflow,
                artifacts,
                entitlement,
                completion,
                "worker-a",
                T14WorkerCrossJvmTestSupport.FIRST_CLAIM_AT)
                .runOnce();
        throw new AssertionError("configured crash boundary was not reached");
    }

    private static void recover(Scenario scenario) throws Exception {
        DataSource dataSource = dataSource();
        JdbcWorkflowStepStore jdbcWorkflow = new JdbcWorkflowStepStore(dataSource);
        FencingAfterClaimStore fenced = new FencingAfterClaimStore(
                jdbcWorkflow,
                dataSource,
                controlFile(),
                scenario != Scenario.COMPLETION_COMMITTED);
        ArtifactStore realArtifacts = artifactStore();
        EntitlementProvider realEntitlement = entitlementProvider(
                dataSource, T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT);

        // marker 恢复必须只读恢复旧终态请求。Guard 包裹已经重新构造的正式实现；
        // 一旦 Worker 错误回到 Artifact 或权益链，先于任何副作用直接失败。
        boolean markerRecovery = scenario == Scenario.SUCCESS_MARKER
                || scenario == Scenario.FAILURE_MARKER;
        ArtifactStore artifacts = markerRecovery
                ? new RejectingArtifactStore(realArtifacts)
                : realArtifacts;
        EntitlementProvider entitlement = markerRecovery
                ? new RejectingEntitlementProvider(realEntitlement)
                : realEntitlement;

        MockFinalizationWorker.RunResult result = worker(
                fenced,
                artifacts,
                entitlement,
                new JdbcFinalizationCompletionUnitOfWork(dataSource),
                "worker-b",
                T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT)
                .runOnce();
        if (scenario == Scenario.COMPLETION_COMMITTED) {
            require(result.idle(), "committed completion must be idle after restart");
            require(!fenced.fenceRequired(),
                    "terminal scenario must not claim a new lease");
            System.out.println("PROBE_RECOVERED scenario=" + scenario.cliName
                    + " result=IDLE fences=NOT_APPLICABLE");
            return;
        }
        require(result.claimedCount() == 1 && result.items().size() == 1,
                "recovery must claim exactly one workflow");
        MockFinalizationWorker.ItemStatus expected =
                scenario == Scenario.FAILURE_MARKER
                        ? MockFinalizationWorker.ItemStatus.FAILED_DEAD
                        : MockFinalizationWorker.ItemStatus.COMPLETED;
        require(result.items().getFirst().status() == expected,
                "recovery reached an unexpected terminal status");
        require(fenced.fenceVerified(),
                "old lease fencing was not verified during active takeover");
        System.out.println("PROBE_RECOVERED scenario=" + scenario.cliName
                + " result=" + expected + " fences=NOT_OWNER");
    }

    private static void verifyIdle(Scenario scenario) throws Exception {
        DataSource dataSource = dataSource();
        MockFinalizationWorker.RunResult result = worker(
                new JdbcWorkflowStepStore(dataSource),
                new RejectingArtifactStore(artifactStore()),
                new RejectingEntitlementProvider(entitlementProvider(
                        dataSource,
                        T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT.plusSeconds(1))),
                new JdbcFinalizationCompletionUnitOfWork(dataSource),
                "worker-c",
                T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT.plusSeconds(1))
                .runOnce();
        require(result.idle(), "terminal third run must be idle");
        System.out.println("PROBE_IDLE scenario=" + scenario.cliName
                + " result=IDLE snapshot=UNCHANGED");
    }

    private static MockFinalizationWorker worker(
            WorkflowStepStore workflow,
            ArtifactStore artifacts,
            EntitlementProvider entitlement,
            FinalizationCompletionUnitOfWork completion,
            String owner,
            Instant now) {
        return new MockFinalizationWorker(
                workflow,
                artifacts,
                entitlement,
                completion,
                new MockFinalizationWorker.Config(
                        owner,
                        Clock.fixed(now, ZoneOffset.UTC),
                        T14WorkerCrossJvmTestSupport.LEASE_DURATION,
                        Duration.ofSeconds(5),
                        T14WorkerCrossJvmTestSupport.RETRY_DELAY,
                        1,
                        3));
    }

    private static EntitlementProvider entitlementProvider(
            DataSource dataSource,
            Instant now) {
        return T14WorkerCrossJvmTestSupport.entitlementProvider(
                dataSource, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PersistentFileSystemMockArtifactStore artifactStore()
            throws Exception {
        return new PersistentFileSystemMockArtifactStore(
                absoluteEnvironmentPath(ARTIFACT_ROOT_ENV),
                T14WorkerCrossJvmTestSupport.ARTIFACT_LIMIT);
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

    private static String safeAction(String value) {
        return value != null && value.matches("[a-z-]{1,32}") ? value : "invalid";
    }

    private static void haltAt(Scenario scenario, String checkpoint) {
        System.out.println("PROBE_CRASH scenario=" + scenario.cliName
                + " checkpoint=" + checkpoint);
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(47);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private enum Scenario {
        ARTIFACT_PARTIAL("artifact-partial"),
        ENTITLEMENT_COMMITTED("entitlement-committed"),
        SUCCESS_MARKER("success-marker"),
        COMPLETION_COMMITTED("completion-committed"),
        FAILURE_MARKER("failure-marker");

        private final String cliName;

        Scenario(String cliName) {
            this.cliName = cliName;
        }

        private static Scenario fromCli(String value) {
            for (Scenario scenario : values()) {
                if (scenario.cliName.equals(value)) {
                    return scenario;
                }
            }
            throw new IllegalArgumentException("unknown scenario");
        }
    }

    /** 在第一次正式领取后保存旧 lease；控制文件不包含 Workflow key 或业务正文。 */
    private static class CrashWorkflowStore extends ForwardingWorkflowStore {
        private final Path controlFile;
        private final Scenario scenario;

        private CrashWorkflowStore(
                WorkflowStepStore delegate,
                Path controlFile,
                Scenario scenario) {
            super(delegate);
            this.controlFile = controlFile;
            this.scenario = scenario;
        }

        @Override
        public List<ClaimedStep> claim(ClaimRequest request) {
            List<ClaimedStep> claimed = super.claim(request);
            require(claimed.size() == 1, "JVM-A must claim exactly one workflow");
            try {
                writeLeaseControl(controlFile, claimed.getFirst().lease());
            } catch (Exception failure) {
                throw new IllegalStateException("old lease control write failed", failure);
            }
            return claimed;
        }

        @Override
        public PersistLeaseMarkerResult persistLeaseMarker(
                PersistLeaseMarkerRequest request) {
            PersistLeaseMarkerResult result = super.persistLeaseMarker(request);
            String expected = scenario == Scenario.SUCCESS_MARKER
                    ? FinalizationTerminalOperationMarker.SUCCESS_CODE
                    : scenario == Scenario.FAILURE_MARKER
                            ? FinalizationTerminalOperationMarker.FAILURE_CODE
                            : null;
            if (expected != null
                    && expected.equals(request.markerCode())
                    && result.status() == PersistLeaseMarkerStatus.PERSISTED) {
                haltAt(scenario, scenario == Scenario.SUCCESS_MARKER
                        ? "SUCCESS_MARKER_PERSISTED"
                        : "FAILURE_MARKER_PERSISTED");
            }
            return result;
        }
    }

    /** JVM-B 领取新 lease 后、Worker 业务副作用前验证旧 lease 的完整写栅栏。 */
    private static final class FencingAfterClaimStore extends ForwardingWorkflowStore {
        private final DataSource dataSource;
        private final Path controlFile;
        private final boolean fenceRequired;
        private boolean fenceVerified;

        private FencingAfterClaimStore(
                WorkflowStepStore delegate,
                DataSource dataSource,
                Path controlFile,
                boolean fenceRequired) {
            super(delegate);
            this.dataSource = dataSource;
            this.controlFile = controlFile;
            this.fenceRequired = fenceRequired;
        }

        @Override
        public List<ClaimedStep> claim(ClaimRequest request) {
            List<ClaimedStep> claimed = super.claim(request);
            if (claimed.isEmpty()) {
                require(!fenceRequired,
                        "a non-terminal recovery scenario failed to reclaim its lease");
                return claimed;
            }
            require(fenceRequired && claimed.size() == 1,
                    "JVM-B must reclaim exactly one non-terminal workflow");
            LeaseToken current = claimed.getFirst().lease();
            try {
                LeaseToken stale = readLeaseControl(controlFile, current.key());
                verifyStaleLeaseRejected(stale, current, claimed.getFirst().step());
            } catch (Exception failure) {
                throw new IllegalStateException("old lease fencing verification failed", failure);
            }
            fenceVerified = true;
            return claimed;
        }

        private void verifyStaleLeaseRejected(
                LeaseToken stale,
                LeaseToken current,
                WorkflowStep claimedStep) throws Exception {
            require(current.claimedAt().equals(stale.expiresAt()),
                    "takeover must happen exactly when the stale lease expires");
            Instant activeOperationAt = current.claimedAt().plusNanos(1_000);
            require(activeOperationAt.isBefore(current.expiresAt()),
                    "fencing probe time must be inside JVM-B lease");
            require(claimedStep.status() == WorkflowStep.Status.LEASED
                            && claimedStep.leaseOwner().equals(current.owner())
                            && claimedStep.leaseVersion() == current.leaseVersion()
                            && claimedStep.updatedAt().equals(current.claimedAt())
                            && claimedStep.leaseUntil().equals(current.expiresAt()),
                    "claim result does not describe the JVM-B active lease");
            // 连接级只读核对包含数据库私有 token，避免仅凭公开快照误认为 B 已接管。
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                require(JdbcWorkflowStepStore.activeLeaseMatches(connection, current),
                        "database does not contain the exact JVM-B lease");
                connection.rollback();
            }

            require(super.renew(
                            stale,
                            activeOperationAt,
                            T14WorkerCrossJvmTestSupport.LEASE_DURATION).status()
                            == RenewStatus.NOT_OWNER,
                    "stale renew was not fenced as NOT_OWNER");
            require(super.complete(stale, activeOperationAt).status()
                            == CompleteStatus.NOT_OWNER,
                    "stale complete was not fenced as NOT_OWNER");
            require(super.fail(
                            stale,
                            activeOperationAt,
                            activeOperationAt,
                            "STALE_OWNER",
                            "STALE_OWNER_REJECTED",
                            3).status() == FailStatus.NOT_OWNER,
                    "stale fail was not fenced as NOT_OWNER");

            WorkflowStep currentStep = super.find(stale.key()).orElseThrow();
            // PersistLeaseMarkerRequest 的冻结构造契约要求 markedAt 严格位于“请求所携
            // lease”的窗口内，不能把 JVM-B 时间与旧 lease 组合。这里的 Java 调用
            // 已发生在上面的 B full-token ACTIVE 证明之后；仅 marker 请求字段保留旧
            // lease 内最后 1us，以便真正到达 Store 并由当前 owner 裁决 NOT_OWNER。
            Instant staleMarkerAt = stale.expiresAt().minusNanos(1_000);
            require(!staleMarkerAt.isBefore(stale.claimedAt()),
                    "stale marker probe time must remain inside the old lease");
            PersistLeaseMarkerRequest marker = new PersistLeaseMarkerRequest(
                    stale,
                    staleMarkerAt,
                    currentStep.errorCode(),
                    currentStep.errorSummary(),
                    "STALE_OWNER_MARKER",
                    "STALE_OWNER_MARKER_REJECTED");
            require(super.persistLeaseMarker(marker).status()
                            == PersistLeaseMarkerStatus.NOT_OWNER,
                    "stale marker persist was not fenced as NOT_OWNER");
            require(super.recoverLeaseMarker(marker).status()
                            == PersistLeaseMarkerStatus.NOT_OWNER,
                    "stale marker recovery was not fenced as NOT_OWNER");
        }

        private boolean fenceRequired() {
            return fenceRequired;
        }

        private boolean fenceVerified() {
            return fenceVerified;
        }
    }

    private static final class CrashAfterFirstArtifactStore
            extends ForwardingArtifactStore {
        private final Scenario scenario;
        private int completed;

        private CrashAfterFirstArtifactStore(
                ArtifactStore delegate,
                Scenario scenario) {
            super(delegate);
            this.scenario = scenario;
        }

        @Override
        public CompleteResult completeWrite(
                WriteIntent intent,
                ContentClaim claim) throws ArtifactStoreException {
            CompleteResult result = super.completeWrite(intent, claim);
            completed++;
            if (completed == 1) {
                haltAt(scenario, "FIRST_ARTIFACT_READY");
            }
            return result;
        }
    }

    private static final class CrashAfterEntitlementProvider
            extends ForwardingEntitlementProvider {
        private final Scenario scenario;

        private CrashAfterEntitlementProvider(
                EntitlementProvider delegate,
                Scenario scenario) {
            super(delegate);
            this.scenario = scenario;
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            EntitlementProviderResult<UsageOperationResult> result =
                    super.commitUsage(request);
            require(result.isSuccess(), "entitlement commit did not succeed");
            haltAt(scenario, "ENTITLEMENT_COMMITTED");
            return result;
        }
    }

    private static final class CrashAfterCompletion extends ForwardingCompletion {
        private final Scenario scenario;

        private CrashAfterCompletion(
                FinalizationCompletionUnitOfWork delegate,
                Scenario scenario) {
            super(delegate);
            this.scenario = scenario;
        }

        @Override
        public Result complete(CompletionRequest request) {
            Result result = super.complete(request);
            require(result instanceof Success,
                    "completion did not reach a confirmed terminal result");
            haltAt(scenario, "COMPLETION_COMMITTED");
            return result;
        }
    }

    private static final class TerminalFailureEntitlement
            extends ForwardingEntitlementProvider {
        private TerminalFailureEntitlement(EntitlementProvider delegate) {
            super(delegate);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            return EntitlementProviderResult.failure(
                    new EntitlementProviderError(
                            EntitlementProviderError.Code.INTERNAL, false));
        }
    }

    /** marker 恢复路径的零副作用观察器；任何调用都直接使子进程失败。 */
    private static final class RejectingArtifactStore extends ForwardingArtifactStore {
        private RejectingArtifactStore(ArtifactStore delegate) {
            super(delegate);
        }

        private static AssertionError unexpected() {
            return new AssertionError("marker recovery accessed Artifact Store");
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec) {
            throw unexpected();
        }

        @Override
        public StageResult writeContent(WriteIntent intent, InputStream content) {
            throw unexpected();
        }

        @Override
        public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim) {
            throw unexpected();
        }

        @Override
        public StatResult stat(StorageBinding binding) {
            throw unexpected();
        }

        @Override
        public ReadHandle openRead(StorageBinding binding) {
            throw unexpected();
        }

        @Override
        public DeleteResult delete(StorageBinding binding) {
            throw unexpected();
        }
    }

    /** marker 恢复路径的权益零调用观察器。 */
    private static final class RejectingEntitlementProvider
            extends ForwardingEntitlementProvider {
        private RejectingEntitlementProvider(EntitlementProvider delegate) {
            super(delegate);
        }

        private static AssertionError unexpected() {
            return new AssertionError("marker recovery accessed Entitlement Provider");
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            throw unexpected();
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            throw unexpected();
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            throw unexpected();
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            throw unexpected();
        }
    }

    private static class ForwardingArtifactStore implements ArtifactStore {
        protected final ArtifactStore delegate;

        private ForwardingArtifactStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            return delegate.createWriteIntent(spec);
        }

        @Override
        public StageResult writeContent(WriteIntent intent, InputStream content)
                throws ArtifactStoreException {
            return delegate.writeContent(intent, content);
        }

        @Override
        public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                throws ArtifactStoreException {
            return delegate.completeWrite(intent, claim);
        }

        @Override
        public StatResult stat(StorageBinding binding) throws ArtifactStoreException {
            return delegate.stat(binding);
        }

        @Override
        public ReadHandle openRead(StorageBinding binding)
                throws ArtifactStoreException {
            return delegate.openRead(binding);
        }

        @Override
        public DeleteResult delete(StorageBinding binding)
                throws ArtifactStoreException {
            return delegate.delete(binding);
        }
    }

    private static class ForwardingEntitlementProvider implements EntitlementProvider {
        protected final EntitlementProvider delegate;

        private ForwardingEntitlementProvider(EntitlementProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            return delegate.authorizeStart(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            return delegate.reserveUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            return delegate.commitUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            return delegate.cancelReservation(request);
        }
    }

    private static class ForwardingCompletion
            implements FinalizationCompletionUnitOfWork {
        protected final FinalizationCompletionUnitOfWork delegate;

        private ForwardingCompletion(FinalizationCompletionUnitOfWork delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result complete(CompletionRequest request) {
            return delegate.complete(request);
        }

        @Override
        public Result fail(FailureRequest request) {
            return delegate.fail(request);
        }

        @Override
        public Result recoverCompletion(CompletionRequest request) {
            return delegate.recoverCompletion(request);
        }

        @Override
        public Result recoverFailure(FailureRequest request) {
            return delegate.recoverFailure(request);
        }
    }

    private static class ForwardingWorkflowStore implements WorkflowStepStore {
        protected final WorkflowStepStore delegate;

        private ForwardingWorkflowStore(WorkflowStepStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public RegisterResult register(WorkflowStep candidate) {
            return delegate.register(candidate);
        }

        @Override
        public Optional<WorkflowStep> find(WorkflowStep.Key key) {
            return delegate.find(key);
        }

        @Override
        public List<ClaimedStep> claim(ClaimRequest request) {
            return delegate.claim(request);
        }

        @Override
        public RenewResult renew(
                LeaseToken lease,
                Instant renewedAt,
                Duration leaseDuration) {
            return delegate.renew(lease, renewedAt, leaseDuration);
        }

        @Override
        public PersistLeaseMarkerResult persistLeaseMarker(
                PersistLeaseMarkerRequest request) {
            return delegate.persistLeaseMarker(request);
        }

        @Override
        public PersistLeaseMarkerResult recoverLeaseMarker(
                PersistLeaseMarkerRequest request) {
            return delegate.recoverLeaseMarker(request);
        }

        @Override
        public CompleteResult complete(LeaseToken lease, Instant completedAt) {
            return delegate.complete(lease, completedAt);
        }

        @Override
        public FailResult fail(
                LeaseToken lease,
                Instant failedAt,
                Instant nextAttemptAt,
                String errorCode,
                String errorSummary,
                int maxAttempts) {
            return delegate.fail(
                    lease,
                    failedAt,
                    nextAttemptAt,
                    errorCode,
                    errorSummary,
                    maxAttempts);
        }
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
        require(bytes.length <= MAX_CONTROL_BYTES, "lease control is too large");
        try {
            Files.createFile(path,
                    PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
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
            // Linux 总门禁另行精确检查 0600；其它文件系统仍保留 NOFOLLOW 校验。
        }
    }

    private static LeaseToken readLeaseControl(
            Path path,
            WorkflowStep.Key key) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) <= 0
                || Files.size(path) > MAX_CONTROL_BYTES) {
            throw new IllegalStateException("lease control is unsafe");
        }
        try {
            require(Files.getPosixFilePermissions(path).equals(PRIVATE_FILE_PERMISSIONS),
                    "lease control permissions are unsafe");
        } catch (UnsupportedOperationException ignored) {
            // Linux 总门禁精确验证权限；其它文件系统继续做结构和长度检查。
        }
        try (InputStream bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
             DataInputStream input = new DataInputStream(bytes)) {
            require(input.readInt() == CONTROL_MAGIC
                            && input.readInt() == CONTROL_VERSION,
                    "lease control version is invalid");
            String owner = readString(input);
            String token = readString(input);
            Instant claimedAt = Instant.parse(readString(input));
            Instant expiresAt = Instant.parse(readString(input));
            LeaseToken lease = new LeaseToken(
                    key,
                    owner,
                    token,
                    expiresAt,
                    claimedAt,
                    input.readLong());
            require(input.read() == -1, "lease control has trailing bytes");
            return lease;
        }
    }

    private static void writeString(DataOutputStream output, String value)
            throws IOException {
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
}
