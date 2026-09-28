package com.czur.reference.application.finalization;

import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.CompletionRequest;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.FailureRequest;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在执行最终收口数据库事务前持久化的 canonical 终态操作 marker。
 *
 * <p>Artifact Store、权益 Provider 与 PostgreSQL 之间没有跨资源事务。Worker 在
 * 调用 {@code complete}/{@code fail} 前必须先把完整终态请求写入 Workflow 行；
 * 如果提交确认未知，后续 owner 只能按本 marker 恢复原请求，不能重新运行 Artifact、
 * 权益或失败裁决。marker 因而包含旧 lease 的私有 token、终态时间、checkpoint
 * 引用、READY descriptor 和领取时 attempt。</p>
 *
 * <p>编码采用固定字段顺序、严格 UTF-8 与无 padding URL-safe Base64。解码后会按
 * 当前不可变 checkpoint 复核 descriptor 集合，并重新编码逐字比较；损坏、伪造、
 * 非 canonical 或属于另一 checkpoint 的 marker 一律关闭式拒绝。诊断输出永不包含
 * token、binding、失败正文或 marker 正文。</p>
 */
public final class FinalizationTerminalOperationMarker {
    /** READY/READY_WITH_WARNINGS 终态请求的关闭式 marker code。 */
    public static final String SUCCESS_CODE =
            "FINALIZATION_TERMINAL_SUCCESS_PENDING_V1";

    /** FAILED/DEAD 终态请求的关闭式 marker code。 */
    public static final String FAILURE_CODE =
            "FINALIZATION_TERMINAL_FAILURE_PENDING_V2";

    private static final int MAGIC = 0x46544D31; // ASCII "FTM1"
    private static final int MAX_MARKER_BYTES = 1_048_576;
    private static final int MAX_TEXT_BYTES = 65_536;

    private final Kind kind;
    private final String checkpointType;
    private final String checkpointDigest;
    private final LeaseToken lease;
    private final Instant terminalAt;
    private final int leasedAttempt;
    private final List<ArtifactDescriptor> readyArtifacts;
    private final TerminalFailure failure;

    private FinalizationTerminalOperationMarker(
            Kind kind,
            String checkpointType,
            String checkpointDigest,
            LeaseToken lease,
            Instant terminalAt,
            int leasedAttempt,
            List<ArtifactDescriptor> readyArtifacts,
            TerminalFailure failure) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.checkpointType = requireText("checkpointType", checkpointType);
        this.checkpointDigest = requireText("checkpointDigest", checkpointDigest);
        this.lease = Objects.requireNonNull(lease, "lease");
        this.terminalAt = requirePersistentInstant("terminalAt", terminalAt);
        if (leasedAttempt < 0) {
            throw invalidMarker();
        }
        this.leasedAttempt = leasedAttempt;
        Objects.requireNonNull(readyArtifacts, "readyArtifacts");
        for (ArtifactDescriptor descriptor : readyArtifacts) {
            Objects.requireNonNull(descriptor, "readyArtifacts item");
        }
        this.readyArtifacts = List.copyOf(readyArtifacts);
        if ((kind == Kind.FAILURE) != (failure != null)) {
            throw invalidMarker();
        }
        this.failure = failure;
    }

    /** 从新的成功终态请求冻结 marker；attempt 来自领取该 lease 时的步骤快照。 */
    public static FinalizationTerminalOperationMarker forCompletion(
            CompletionRequest request,
            int leasedAttempt) {
        Objects.requireNonNull(request, "request");
        FinalizationTerminalOperationMarker marker = new FinalizationTerminalOperationMarker(
                Kind.SUCCESS,
                FinalizationCheckpoint.CHECKPOINT_TYPE,
                FinalizationCheckpointJson.sha256(request.checkpoint()),
                request.lease(),
                request.completedAt(),
                leasedAttempt,
                request.readyArtifacts(),
                null);
        marker.validateAgainstCheckpoint(request.checkpoint());
        return marker;
    }

    /** 从新的失败终态请求冻结 marker；FailureRequest 已携带领取时 attempt。 */
    public static FinalizationTerminalOperationMarker forFailure(
            FailureRequest request) {
        Objects.requireNonNull(request, "request");
        FinalizationTerminalOperationMarker marker = new FinalizationTerminalOperationMarker(
                Kind.FAILURE,
                FinalizationCheckpoint.CHECKPOINT_TYPE,
                FinalizationCheckpointJson.sha256(request.checkpoint()),
                request.lease(),
                request.failedAt(),
                request.leasedAttempt(),
                request.readyArtifacts(),
                request.failure());
        marker.validateAgainstCheckpoint(request.checkpoint());
        return marker;
    }

    /**
     * 从 Workflow 的 error pair 解码并复核 marker。
     *
     * @throws IllegalArgumentException marker 不完整、不 canonical 或与 checkpoint 冲突
     */
    public static FinalizationTerminalOperationMarker decode(
            String markerCode,
            String encoded,
            FinalizationCheckpoint checkpoint) {
        try {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Kind kind = switch (markerCode) {
                case SUCCESS_CODE -> Kind.SUCCESS;
                case FAILURE_CODE -> Kind.FAILURE;
                default -> throw invalidMarker();
            };
            if (encoded == null || encoded.isBlank()
                    || encoded.length() > encodedLengthLimit()) {
                throw invalidMarker();
            }
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (raw.length == 0 || raw.length > MAX_MARKER_BYTES) {
                throw invalidMarker();
            }
            FinalizationTerminalOperationMarker marker;
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(raw))) {
                if (input.readInt() != MAGIC || input.readUnsignedByte() != kind.tag) {
                    throw invalidMarker();
                }
                String checkpointType = readText(input);
                String checkpointDigest = readText(input);
                WorkflowStep.Key key = new WorkflowStep.Key(
                        readText(input), readText(input), readText(input));
                LeaseToken lease = new LeaseToken(
                        key,
                        readText(input),
                        readText(input),
                        readInstant(input),
                        readInstant(input),
                        input.readLong());
                Instant terminalAt = readInstant(input);
                int leasedAttempt = input.readInt();
                int artifactCount = input.readInt();
                if (artifactCount < 0
                        || artifactCount > FinalizationCheckpoint.MAX_ARTIFACTS) {
                    throw invalidMarker();
                }
                List<ArtifactDescriptor> artifacts = new ArrayList<>(artifactCount);
                for (int index = 0; index < artifactCount; index++) {
                    artifacts.add(readDescriptor(input));
                }
                TerminalFailure failure = null;
                if (kind == Kind.FAILURE) {
                    int retryableTag = input.readUnsignedByte();
                    if (retryableTag != 0 && retryableTag != 1) {
                        throw invalidMarker();
                    }
                    failure = new TerminalFailure(
                            readText(input),
                            readText(input),
                            retryableTag == 1,
                            readText(input),
                            readText(input));
                }
                if (input.read() != -1) {
                    throw invalidMarker();
                }
                marker = new FinalizationTerminalOperationMarker(
                        kind,
                        checkpointType,
                        checkpointDigest,
                        lease,
                        terminalAt,
                        leasedAttempt,
                        artifacts,
                        failure);
            }
            marker.validateAgainstCheckpoint(checkpoint);
            if (!markerCode.equals(marker.markerCode())
                    || !encoded.equals(marker.encodedSummary())) {
                throw invalidMarker();
            }
            return marker;
        } catch (EOFException truncated) {
            throw invalidMarker();
        } catch (IOException | RuntimeException invalid) {
            throw invalidMarker();
        }
    }

    public Kind kind() {
        return kind;
    }

    public String markerCode() {
        return kind == Kind.SUCCESS ? SUCCESS_CODE : FAILURE_CODE;
    }

    public String encodedSummary() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(kind.tag);
                writeText(output, checkpointType);
                writeText(output, checkpointDigest);
                writeText(output, lease.key().meetingId());
                writeText(output, lease.key().stepType());
                writeText(output, lease.key().stepKey());
                writeText(output, lease.owner());
                writeText(output, lease.token());
                writeInstant(output, lease.expiresAt());
                writeInstant(output, lease.claimedAt());
                output.writeLong(lease.leaseVersion());
                writeInstant(output, terminalAt);
                output.writeInt(leasedAttempt);
                output.writeInt(readyArtifacts.size());
                for (ArtifactDescriptor descriptor : readyArtifacts) {
                    writeDescriptor(output, descriptor);
                }
                if (kind == Kind.FAILURE) {
                    output.writeByte(failure.retryable() ? 1 : 0);
                    writeText(output, failure.domain());
                    writeText(output, failure.code());
                    writeText(output, failure.diagnosticId());
                    writeText(output, failure.workflowSummary());
                }
            }
            byte[] raw = bytes.toByteArray();
            if (raw.length == 0 || raw.length > MAX_MARKER_BYTES) {
                throw invalidMarker();
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (IOException | RuntimeException invalid) {
            throw invalidMarker();
        }
    }

    public LeaseToken lease() {
        return lease;
    }

    public Instant terminalAt() {
        return terminalAt;
    }

    public int leasedAttempt() {
        return leasedAttempt;
    }

    public List<ArtifactDescriptor> readyArtifacts() {
        return readyArtifacts;
    }

    public TerminalFailure failure() {
        return failure;
    }

    /** 使用 marker 中的旧请求身份恢复成功终态。 */
    public CompletionRequest completionRequest(FinalizationCheckpoint checkpoint) {
        if (kind != Kind.SUCCESS) {
            throw invalidMarker();
        }
        validateAgainstCheckpoint(checkpoint);
        return new CompletionRequest(lease, checkpoint, readyArtifacts, terminalAt);
    }

    /** 使用 marker 中的旧请求身份恢复失败终态。 */
    public FailureRequest failureRequest(FinalizationCheckpoint checkpoint) {
        if (kind != Kind.FAILURE) {
            throw invalidMarker();
        }
        validateAgainstCheckpoint(checkpoint);
        return new FailureRequest(
                lease,
                checkpoint,
                readyArtifacts,
                failure,
                terminalAt,
                leasedAttempt);
    }

    private void validateAgainstCheckpoint(FinalizationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!FinalizationCheckpoint.CHECKPOINT_TYPE.equals(checkpointType)
                || !checkpointDigest.equals(FinalizationCheckpointJson.sha256(checkpoint))
                || !lease.key().equals(checkpoint.workflowKey())) {
            throw invalidMarker();
        }
        int previousIndex = -1;
        Set<String> artifactIds = new HashSet<>();
        Set<String> bindings = new HashSet<>();
        for (ArtifactDescriptor descriptor : readyArtifacts) {
            int checkpointIndex = checkpointIndex(checkpoint, descriptor);
            if (checkpointIndex <= previousIndex
                    || !artifactIds.add(descriptor.intent().spec().artifactId())
                    || !bindings.add(descriptor.intent().binding().value())) {
                throw invalidMarker();
            }
            previousIndex = checkpointIndex;
        }
        if (kind == Kind.SUCCESS
                && readyArtifacts.size() != checkpoint.artifacts().size()) {
            throw invalidMarker();
        }
    }

    private static int checkpointIndex(
            FinalizationCheckpoint checkpoint,
            ArtifactDescriptor descriptor) {
        for (int index = 0; index < checkpoint.artifacts().size(); index++) {
            FinalizationCheckpoint.ArtifactMaterial material =
                    checkpoint.artifacts().get(index);
            if (material.spec().equals(descriptor.intent().spec())
                    && material.claim().equals(descriptor.content())) {
                return index;
            }
        }
        return -1;
    }

    private static void writeDescriptor(
            DataOutputStream output,
            ArtifactDescriptor descriptor) throws IOException {
        WriteIntent intent = descriptor.intent();
        WriteIntentSpec spec = intent.spec();
        writeText(output, intent.intentId());
        writeText(output, intent.binding().value());
        writeText(output, spec.artifactId());
        writeText(output, spec.meetingId());
        writeText(output, spec.artifactType());
        writeText(output, spec.mediaType());
        if (spec.language() == null) {
            output.writeByte(0);
        } else {
            output.writeByte(1);
            writeText(output, spec.language());
        }
        output.writeInt(spec.revision());
        output.writeLong(spec.expectedSize());
        writeText(output, spec.expectedChecksum());
        output.writeLong(descriptor.content().size());
        writeText(output, descriptor.content().checksum());
    }

    private static ArtifactDescriptor readDescriptor(DataInputStream input)
            throws IOException {
        String intentId = readText(input);
        StorageBinding binding = new StorageBinding(readText(input));
        String artifactId = readText(input);
        String meetingId = readText(input);
        String artifactType = readText(input);
        String mediaType = readText(input);
        int languageTag = input.readUnsignedByte();
        if (languageTag != 0 && languageTag != 1) {
            throw invalidMarker();
        }
        String language = languageTag == 1 ? readText(input) : null;
        WriteIntentSpec spec = new WriteIntentSpec(
                artifactId,
                meetingId,
                artifactType,
                mediaType,
                language,
                input.readInt(),
                input.readLong(),
                readText(input));
        ContentClaim claim = new ContentClaim(input.readLong(), readText(input));
        return new ArtifactDescriptor(new WriteIntent(intentId, binding, spec), claim);
    }

    private static void writeInstant(DataOutputStream output, Instant value)
            throws IOException {
        Instant persistent = requirePersistentInstant("marker instant", value);
        output.writeLong(persistent.getEpochSecond());
        output.writeInt(persistent.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        try {
            return requirePersistentInstant(
                    "marker instant",
                    Instant.ofEpochSecond(input.readLong(), input.readInt()));
        } catch (RuntimeException invalid) {
            throw invalidMarker();
        }
    }

    private static void writeText(DataOutputStream output, String value)
            throws IOException {
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(requireText("marker text", value)));
        } catch (CharacterCodingException malformed) {
            throw new IOException("invalid marker UTF-16 input", malformed);
        }
        byte[] utf8 = new byte[encoded.remaining()];
        encoded.get(utf8);
        if (utf8.length > MAX_TEXT_BYTES) {
            throw invalidMarker();
        }
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_TEXT_BYTES) {
            throw invalidMarker();
        }
        byte[] utf8 = input.readNBytes(length);
        if (utf8.length != length) {
            throw new EOFException("truncated marker text");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("invalid marker UTF-8", malformed);
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Instant requirePersistentInstant(String name, Instant value) {
        Objects.requireNonNull(value, name);
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    name + " must use PostgreSQL microsecond precision");
        }
        return value;
    }

    private static int encodedLengthLimit() {
        return ((MAX_MARKER_BYTES + 2) / 3) * 4;
    }

    private static IllegalArgumentException invalidMarker() {
        return new IllegalArgumentException("terminal operation marker is invalid");
    }

    @Override
    public String toString() {
        return "FinalizationTerminalOperationMarker[kind=" + kind
                + ", lease=redacted, checkpoint=redacted, terminalAt=" + terminalAt
                + ", leasedAttempt=" + leasedAttempt
                + ", artifactCount=" + readyArtifacts.size()
                + ", failure=redacted]";
    }

    /** marker 关闭式区分成功和失败终态请求。 */
    public enum Kind {
        SUCCESS(1),
        FAILURE(2);

        private final int tag;

        Kind(int tag) {
            this.tag = tag;
        }
    }
}
