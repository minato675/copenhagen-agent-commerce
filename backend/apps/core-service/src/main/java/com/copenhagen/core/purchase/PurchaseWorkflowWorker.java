package com.copenhagen.core.purchase;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "core.payment.worker", name = "enabled", havingValue = "true")
public class PurchaseWorkflowWorker {
    private final PurchaseWorkflowRepository repository;
    private final PurchaseWorkflowService service;
    private final Clock clock;

    public PurchaseWorkflowWorker(PurchaseWorkflowRepository repository, PurchaseWorkflowService service, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${core.payment.worker.poll-interval-seconds:3}000",
            initialDelayString = "${core.payment.worker.poll-interval-seconds:3}000")
    public void poll() {
        for (PurchaseWorkflowRepository.Row row : repository.due(clock.instant())) {
            service.process(row);
        }
    }
}
