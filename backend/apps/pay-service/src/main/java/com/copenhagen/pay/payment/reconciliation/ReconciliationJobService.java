package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationJobService {

    public static final int MAX_ATTEMPTS = 5;

    private final ReconciliationJobRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration retryDelay;

    public ReconciliationJobService(
            ReconciliationJobRepository repository,
            Clock clock,
            @Value("${pay.reconciliation.lease-duration-seconds:60}") long leaseSeconds,
            @Value("${pay.reconciliation.retry-delay-seconds:60}") long retrySeconds) {
        if (leaseSeconds <= 0 || retrySeconds <= 0) {
            throw new IllegalArgumentException("reconciliation durations must be positive");
        }
        this.repository = repository;
        this.clock = clock;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.retryDelay = Duration.ofSeconds(retrySeconds);
    }

    @Transactional
    public Optional<ReconciliationJob> claimNext() {
        // Match PostgreSQL microsecond precision so the lease can be compared exactly.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return repository.claimNext(now, now.plus(leaseDuration));
    }

    @Transactional
    public boolean markSucceeded(ReconciliationJob job) {
        Instant now = clock.instant();
        return repository.complete(job, ReconciliationJobStatus.SUCCEEDED, now, now, null);
    }

    @Transactional
    public boolean markCancelled(ReconciliationJob job) {
        Instant now = clock.instant();
        return repository.complete(job, ReconciliationJobStatus.CANCELLED, now, now, "PAYMENT_NOT_UNKNOWN");
    }

    @Transactional
    public boolean markFailed(ReconciliationJob job) {
        Instant now = clock.instant();
        ReconciliationJobStatus status = job.attemptCount() >= MAX_ATTEMPTS
                ? ReconciliationJobStatus.EXHAUSTED : ReconciliationJobStatus.FAILED;
        return repository.complete(job, status, now.plus(retryDelay), now, "PG_LOOKUP_UNCONFIRMED");
    }
}
