package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private ReconciliationJobRepository repository;
    private ReconciliationJobService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReconciliationJobRepository.class);
        service = new ReconciliationJobService(repository, Clock.fixed(NOW, ZoneOffset.UTC), 60, 30);
    }

    @Test
    void failure_fifthAttempt_exhaustsWithoutSixthLookup() {
        ReconciliationJob job = job(5);
        when(repository.complete(job, ReconciliationJobStatus.EXHAUSTED, NOW.plusSeconds(30), NOW,
                "PG_LOOKUP_UNCONFIRMED")).thenReturn(true);
        assertTrue(service.markFailed(job));
        verify(repository).complete(job, ReconciliationJobStatus.EXHAUSTED, NOW.plusSeconds(30), NOW,
                "PG_LOOKUP_UNCONFIRMED");
    }

    @Test
    void failure_fourthAttempt_schedulesRetry() {
        ReconciliationJob job = job(4);
        service.markFailed(job);
        verify(repository).complete(job, ReconciliationJobStatus.FAILED, NOW.plusSeconds(30), NOW,
                "PG_LOOKUP_UNCONFIRMED");
    }

    @Test
    void completion_lostLease_doesNotReportSuccess() {
        assertFalse(service.markSucceeded(job(1)));
        verify(repository).complete(job(1), ReconciliationJobStatus.SUCCEEDED, NOW, NOW, null);
    }

    @Test
    void claim_usesConfiguredLease() {
        when(repository.claimNext(NOW, NOW.plusSeconds(60))).thenReturn(Optional.of(job(1)));
        assertTrue(service.claimNext().isPresent());
    }

    private ReconciliationJob job(int count) {
        return new ReconciliationJob("job-1", "attempt-1", ReconciliationJobStatus.RUNNING,
                count, NOW.plusSeconds(60));
    }
}
