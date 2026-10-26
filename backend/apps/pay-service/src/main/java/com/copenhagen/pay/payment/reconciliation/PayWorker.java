package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.common.observability.PayLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "pay.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PayWorker {

    private static final Logger log = LoggerFactory.getLogger(PayWorker.class);

    private final ReconciliationService service;
    private final int batchSize;

    public PayWorker(
            ReconciliationService service,
            @Value("${pay.reconciliation.batch-size:10}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("reconciliation batch-size must be positive");
        }
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${pay.reconciliation.poll-delay-millis:5000}",
            initialDelayString = "${pay.reconciliation.poll-delay-millis:5000}")
    public void runBatch() {
        int processed = 0;
        log.debug("pay.reconciliation.worker.batch.start batchSize={}", batchSize);
        for (int i = 0; i < batchSize; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            boolean processedNext;
            try (PayLogContext.Scope ignored = PayLogContext.openWorker("reconciliation")) {
                try {
                    processedNext = service.processNext();
                } catch (RuntimeException exception) {
                    // A failed settlement rolls back; the next worker can recover after lease expiry.
                    log.error(
                            "pay.reconciliation.worker.item_failed failureStage=PROCESS_NEXT "
                                    + "exceptionType={}",
                            exception.getClass().getSimpleName());
                    continue;
                }
            }
            if (!processedNext) {
                break;
            }
            processed++;
        }
        log.info("pay.reconciliation.worker.batch.complete processed={}", processed);
    }
}
