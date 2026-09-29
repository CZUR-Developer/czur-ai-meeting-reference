package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.deletion.MeetingDeletionPlan;
import com.czur.reference.application.port.ArtifactDeletionItemUnitOfWork;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.DeleteResult;
import com.czur.reference.application.port.artifact.ArtifactStore.InspectionResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL connection-scoped fenced confirmation for one deletion item.
 *
 * <p>The workflow row is locked before any external Store call. The frozen plan and item
 * are then checked against the request, and READY metadata is checked against the binding
 * derived by the Store. A successful Store operation is followed by metadata removal and
 * the item DELETED audit update in the same local transaction. Meeting and Workflow terminal
 * state are deliberately outside this class.</p>
 */
public final class JdbcArtifactDeletionItemUnitOfWork
        implements ArtifactDeletionItemUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcArtifactDeletionItemUnitOfWork.class.getName());
    private static final String LOCK_ITEM_SQL = """
            SELECT artifact_id, artifact_type, revision, status, storage_binding, deleted_at
              FROM artifact_deletion_item
             WHERE meeting_id = ? AND ordinal = ?
             FOR UPDATE
            """;
    private static final String LOCK_METADATA_SQL = """
            SELECT artifact_id FROM artifact WHERE artifact_id = ? FOR UPDATE
            """;
    private static final String DELETE_METADATA_SQL = """
            DELETE FROM artifact
             WHERE artifact_id = ? AND storage_binding = ?
            """;
    private static final String UPDATE_ITEM_SQL = """
            UPDATE artifact_deletion_item
               SET status = 'DELETED', storage_binding = ?, deleted_at = ?
             WHERE meeting_id = ? AND ordinal = ? AND status = 'PENDING'
            """;

    private final DataSource dataSource;
    private final ArtifactStore artifactStore;

    public JdbcArtifactDeletionItemUnitOfWork(
            DataSource dataSource,
            ArtifactStore artifactStore) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    }

    @Override
    public Result delete(DeleteRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            transactionStarted = true;

            JdbcWorkflowStepStore.LeaseFenceResult fence =
                    JdbcWorkflowStepStore.lockActiveLease(
                            connection, request.lease(), request.deletedAt());
            Result rejected = fenceFailure(fence.status());
            if (rejected != null) {
                rollback(connection);
                return rejected;
            }

            MeetingDeletionPlan persisted = new JdbcArtifactDeletionPlanStore(connection)
                    .find(request.lease().key().meetingId())
                    .orElse(null);
            if (persisted == null) {
                rollback(connection);
                return failure(ErrorCode.PLAN_NOT_FOUND);
            }
            ArtifactDeletionPlan plan = persisted.plan();
            WorkflowStep expectedWorkflow = ArtifactDeletionPlanJson.workflowStep(plan);
            WorkflowStep actualWorkflow = fence.step();
            if (!expectedWorkflow.key().equals(actualWorkflow.key())
                    || !expectedWorkflow.checkpointType().equals(actualWorkflow.checkpointType())
                    || !expectedWorkflow.canonicalPayload().equals(actualWorkflow.canonicalPayload())
                    || !expectedWorkflow.contentDigest().equals(actualWorkflow.contentDigest())
                    || request.ordinal() >= plan.targets().size()) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }
            ArtifactDeletionTarget target = plan.targets().get(request.ordinal());
            if (!target.spec().equals(request.spec())) {
                rollback(connection);
                return failure(ErrorCode.SPEC_MISMATCH);
            }

            ItemRow item = lockItem(connection, request.lease().key().meetingId(), request.ordinal());
            if (item == null || !target.artifactId().equals(item.artifactId())
                    || !target.spec().artifactType().equals(item.artifactType())
                    || target.spec().revision() != item.revision()) {
                rollback(connection);
                return failure(ErrorCode.ITEM_MISMATCH);
            }

            InspectionResult inspection;
            try {
                inspection = artifactStore.inspect(request.spec());
            } catch (ArtifactStoreException failure) {
                rollback(connection);
                return storeFailure(failure);
            }

            // A committed item is the durable replay marker. The Store is read only to
            // confirm its binding/tombstone and is never called with deleteBySpec again.
            if (item.deleted()) {
                if (!item.storageBinding().equals(inspection.binding().value())
                        || inspection.state() != ArtifactStore.StoreState.DELETED) {
                    rollback(connection);
                    return failure(ErrorCode.BINDING_MISMATCH);
                }
                rollback(connection);
                return new Success(SuccessCode.REPLAYED, inspection.binding());
            }

            // Read metadata before the external Store call without retaining its row lock.
            // The row is locked and read again after Store success; this bounds database
            // lock duration while still rejecting a concurrent metadata replacement.
            ArtifactReadyMetadataStore.ReadyMetadata metadata =
                    readMetadata(connection, target.artifactId());
            if (metadata != null) {
                ArtifactDescriptor descriptor = metadata.artifact();
                if (!descriptor.intent().spec().equals(request.spec())
                        || !descriptor.intent().binding().equals(inspection.binding())) {
                    rollback(connection);
                    return failure(ErrorCode.BINDING_MISMATCH);
                }
                if (inspection.state() == ArtifactStore.StoreState.READY
                        && !descriptor.equals(inspection.artifact())) {
                    rollback(connection);
                    return failure(ErrorCode.BINDING_MISMATCH);
                }
            } else if (inspection.state() == ArtifactStore.StoreState.READY) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }

            DeleteResult deleted;
            try {
                deleted = artifactStore.deleteBySpec(request.spec());
            } catch (ArtifactStoreException failure) {
                rollback(connection);
                return storeFailure(failure);
            }
            if (!inspection.binding().equals(deleted.binding())) {
                rollback(connection);
                return failure(ErrorCode.BINDING_MISMATCH);
            }
            ArtifactReadyMetadataStore.ReadyMetadata lockedMetadata =
                    lockAndReadMetadata(connection, target.artifactId());
            if (!Objects.equals(metadata, lockedMetadata)) {
                rollback(connection);
                return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
            }
            if (lockedMetadata != null) {
                try (PreparedStatement statement = connection.prepareStatement(DELETE_METADATA_SQL)) {
                    statement.setString(1, target.artifactId());
                    statement.setString(2, deleted.binding().value());
                    if (statement.executeUpdate() != 1) {
                        rollback(connection);
                        return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_ITEM_SQL)) {
                statement.setString(1, deleted.binding().value());
                statement.setTimestamp(2, Timestamp.from(request.deletedAt()));
                statement.setString(3, request.lease().key().meetingId());
                statement.setInt(4, request.ordinal());
                if (statement.executeUpdate() != 1) {
                    rollback(connection);
                    return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
                }
            }
            commitStarted = true;
            connection.commit();
            LOGGER.log(Level.FINE, "artifact deletion item committed result=DELETED");
            return new Success(SuccessCode.DELETED, deleted.binding());
        } catch (SQLException failure) {
            if (commitStarted) {
                return failure(ErrorCode.OUTCOME_UNKNOWN);
            }
            rollback(connection);
            return failure(ErrorCode.STORAGE_FAILURE);
        } catch (RuntimeException failure) {
            if (commitStarted) {
                return failure(ErrorCode.OUTCOME_UNKNOWN);
            }
            rollback(connection);
            return failure(ErrorCode.SNAPSHOT_INCONSISTENT);
        } finally {
            close(connection);
        }
    }

    private static Result fenceFailure(JdbcWorkflowStepStore.LeaseFenceStatus status) {
        return switch (status) {
            case ACTIVE -> null;
            case NOT_FOUND -> failure(ErrorCode.NOT_FOUND);
            case NOT_OWNER -> failure(ErrorCode.NOT_OWNER);
            case LEASE_EXPIRED -> failure(ErrorCode.LEASE_EXPIRED);
            case ALREADY_COMPLETED -> failure(ErrorCode.ALREADY_COMPLETED);
            case ALREADY_DEAD -> failure(ErrorCode.ALREADY_DEAD);
        };
    }

    private static ItemRow lockItem(Connection connection, String meetingId, int ordinal)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ITEM_SQL)) {
            statement.setString(1, meetingId);
            statement.setInt(2, ordinal);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                ItemRow result = new ItemRow(
                        rows.getString("artifact_id"), rows.getString("artifact_type"),
                        rows.getInt("revision"), rows.getString("status"),
                        rows.getString("storage_binding"));
                if (rows.next()) {
                    throw new SQLException("duplicate deletion item");
                }
                return result;
            }
        }
    }

    private static ArtifactReadyMetadataStore.ReadyMetadata readMetadata(
            Connection connection, String artifactId) {
        return new JdbcArtifactReadyMetadataStore(connection)
                .findByArtifactId(artifactId).orElse(null);
    }

    private static ArtifactReadyMetadataStore.ReadyMetadata lockAndReadMetadata(
            Connection connection, String artifactId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_METADATA_SQL)) {
            statement.setString(1, artifactId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
            }
        }
        return new JdbcArtifactReadyMetadataStore(connection)
                .findByArtifactId(artifactId).orElseThrow(
                        () -> new SQLException("metadata disappeared"));
    }

    private static Result storeFailure(ArtifactStoreException failure) {
        return new Failure(
                failure.retryable() ? ErrorCode.STORE_FAILURE : ErrorCode.STORE_REJECTED,
                failure.retryable() ? ErrorCode.STORE_FAILURE.retryable()
                        : ErrorCode.STORE_REJECTED.retryable());
    }

    private static Failure failure(ErrorCode code) {
        return new Failure(code, code.retryable());
    }

    private static void rollback(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // The result remains a retryable storage failure on the caller's next run.
            }
        }
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // No data is exposed through cleanup diagnostics.
            }
        }
    }

    private record ItemRow(
            String artifactId,
            String artifactType,
            int revision,
            String status,
            String storageBinding) {
        private boolean deleted() {
            return "DELETED".equals(status) && storageBinding != null;
        }
    }
}
