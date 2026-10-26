package com.copenhagen.pay.payment.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReconciliationJobPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void pendingJobIsClaimableWhenDue() {
        assertTrue(ReconciliationJobPolicy.isClaimable(
                ReconciliationJobStatus.PENDING,
                NOW.minusSeconds(1),
                null,
                NOW));
        assertFalse(ReconciliationJobPolicy.isClaimable(
                ReconciliationJobStatus.PENDING,
                NOW.plusSeconds(1),
                null,
                NOW));
    }

    @Test
    void runningJobIsClaimableWhenLeaseExpired() {
        assertTrue(ReconciliationJobPolicy.isClaimable(
                ReconciliationJobStatus.RUNNING,
                NOW.plusSeconds(60),
                NOW.minusSeconds(1),
                NOW));
        assertFalse(ReconciliationJobPolicy.isClaimable(
                ReconciliationJobStatus.RUNNING,
                NOW,
                NOW.plusSeconds(1),
                NOW));
    }

    @Test
    void failedJobIsClaimableWhenRetryIsDue() {
        assertTrue(ReconciliationJobPolicy.isClaimable(
                ReconciliationJobStatus.FAILED,
                NOW.minusSeconds(1),
                null,
                NOW));
        assertFalse(ReconciliationJobPolicy.isClaimable(
                ReconciliationJobStatus.FAILED,
                NOW.plusSeconds(1),
                null,
                NOW));
    }

    @Test
    void maxAttemptsEndsRetry() {
        assertFalse(ReconciliationJobPolicy.isExhausted(4, 5));
        assertTrue(ReconciliationJobPolicy.isExhausted(5, 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReconciliationJobPolicy.isExhausted(0, 0));
    }
}
