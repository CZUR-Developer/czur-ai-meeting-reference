package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖数据库的 JDBC 工作流步骤边界契约。 */
class JdbcWorkflowStepStoreContractTest {
    @Test
    void adapterKeepsConnectionScopedPrimitivesAndIndependentPublicOperations() {
        String source = readSource();
        for (String method : new String[]{"static RegisterResult register(",
                "static Optional<WorkflowStep> find(", "static List<ClaimedStep> claim(",
                "static RenewResult renew(", "static CompleteResult complete(",
                "static FailResult fail("}) {
            assertTrue(source.contains(method), method);
        }
        assertTrue(source.contains("connection.setAutoCommit(false)"));
        assertTrue(source.contains("connection.commit()"));
        assertTrue(source.contains("rollbackQuietly(connection)"));
        assertTrue(source.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(source.contains("workflow step operation requires autoCommit=false"));
        assertTrue(source.contains("leaseExpiry(request.now(), request.leaseDuration())"));
        assertTrue(source.contains("renewalExpiry(lease, renewedAt, leaseDuration)"));
        assertTrue(source.contains("renew must extend lease expiry"));
        assertTrue(source.indexOf("Instant expiry = renewalExpiry(")
                < source.indexOf("StoredStep current = queryStored(",
                source.indexOf("static RenewResult renew(")));
        assertFalse(source.contains("ClaimRequest request,\n            Instant expiry"));
        assertFalse(source.contains("Instant renewedAt,\n            Instant expiry"));
        assertFalse(source.contains("roundedMicrosecondTimestamp("));
    }

    @Test
    void sqlUsesFencingFieldsAndDoesNotLogSensitiveValues() {
        String source = readSource();
        for (String field : new String[]{"lease_owner", "lease_token", "lease_version",
                "lease_until", "updated_at", "initial_available_at", "next_attempt_at"}) {
            assertTrue(source.contains(field), field);
        }
        assertTrue(source.contains("status = 'LEASED'"));
        assertTrue(source.contains("lease.token().equals(current.leaseToken())"));
        assertTrue(source.contains("lease.claimedAt().equals(step.updatedAt())"));
        assertTrue(source.contains("initialAvailableAt.equals(candidate.nextAttemptAt())"));
        assertFalse(source.contains("failure.getMessage()"));
        assertFalse(source.contains("LOGGER.log(Level.INFO, \"JDBC 工作流步骤完成 status=COMPLETED meeting"));
    }

    @Test
    void claimSortsByReadyTimeAndStableKeyAndUsesExplicitWhitelist() {
        String source = readSource();
        assertTrue(source.contains("step_type = ANY (?::text[])"));
        assertTrue(source.contains("ORDER BY CASE WHEN status = 'PENDING'"));
        assertTrue(source.contains("meeting_id, step_type, step_key"));
        assertTrue(source.contains("allowedStepTypes().isEmpty()"));
    }

    @Test
    void timestampsAreRejectedInsteadOfRoundedAndRetryCannotMoveBeforeFailure() {
        String source = readSource();
        assertTrue(source.contains("value.getNano() % 1_000 != 0"));
        assertTrue(source.contains("must use database microsecond precision"));
        assertTrue(source.contains("nextAttemptAt.isBefore(failedAt)"));
        assertFalse(source.contains("(value.getNano() + 500) / 1_000"));
    }

    private static String readSource() {
        for (Path candidate : new Path[]{
                Path.of("..", "backend-core", "src", "main", "java", "com", "czur", "reference",
                        "application", "persistence", "jdbc", "JdbcWorkflowStepStore.java"),
                Path.of("backend-core", "src", "main", "java", "com", "czur",
                        "reference", "application", "persistence", "jdbc",
                        "JdbcWorkflowStepStore.java")}) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new IllegalStateException("JdbcWorkflowStepStore source unreadable", failure);
                }
            }
        }
        throw new IllegalStateException("JdbcWorkflowStepStore source file not found");
    }
}
