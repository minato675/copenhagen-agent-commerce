package com.copenhagen.core.payment;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "core.payment.worker", name = "enabled", havingValue = "true")
public class PaidCartCleanupWorker {
    private static final Logger log = LoggerFactory.getLogger(PaidCartCleanupWorker.class);
    private final PaidCartCleanupRepository repository;
    private final Clock clock;

    public PaidCartCleanupWorker(PaidCartCleanupRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${core.payment.worker.poll-interval-seconds:3}000")
    public void poll() {
        for (long purchaseId : repository.pending()) {
            try {
                repository.cleanup(purchaseId, clock.instant());
            } catch (RuntimeException exception) {
                log.warn("Paid cart cleanup will retry: purchaseId={}", purchaseId, exception);
            }
        }
    }
}
