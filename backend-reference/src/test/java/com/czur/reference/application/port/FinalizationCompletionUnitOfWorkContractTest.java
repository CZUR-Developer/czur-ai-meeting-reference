package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationTerminalFailureIdentity;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Completion 公共失败摘要的关闭式协议边界。 */
class FinalizationCompletionUnitOfWorkContractTest {
    @Test
    void terminalFailureRejectsUnknownDomainInvalidUtf8AndOversizeText() {
        assertThrows(IllegalArgumentException.class, () ->
                new TerminalFailure(
                        "NETWORK", "CODE", false, "diag", "summary"));
        assertThrows(IllegalArgumentException.class, () ->
                new TerminalFailure(
                        "INTERNAL", "bad-\ud800", false, "diag", "summary"));
        assertThrows(IllegalArgumentException.class, () ->
                new TerminalFailure(
                        "INTERNAL", "CODE", false, "diag", "x".repeat(4097)));
    }

    @Test
    void terminalFailureIdentityIsStableAndCoversEveryField() {
        TerminalFailure original = new TerminalFailure(
                "MODEL", "MODEL_FAILED", false, "diag", "summary");
        String identity = FinalizationTerminalFailureIdentity.of(original);

        assertEquals(identity, FinalizationTerminalFailureIdentity.of(original));
        assertTrue(FinalizationTerminalFailureIdentity.isValid(identity));
        assertNotEquals(identity, FinalizationTerminalFailureIdentity.of(
                new TerminalFailure("STORAGE", "MODEL_FAILED", false, "diag", "summary")));
        assertNotEquals(identity, FinalizationTerminalFailureIdentity.of(
                new TerminalFailure("MODEL", "OTHER", false, "diag", "summary")));
        assertNotEquals(identity, FinalizationTerminalFailureIdentity.of(
                new TerminalFailure("MODEL", "MODEL_FAILED", true, "diag", "summary")));
        assertNotEquals(identity, FinalizationTerminalFailureIdentity.of(
                new TerminalFailure("MODEL", "MODEL_FAILED", false, "other", "summary")));
        assertNotEquals(identity, FinalizationTerminalFailureIdentity.of(
                new TerminalFailure("MODEL", "MODEL_FAILED", false, "diag", "other")));
    }
}
