package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.common.observability.PayLogContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatchService {

    private static final int MAX_DELIVERY_ATTEMPTS = 5;
    private static final String DELIVERY_FAILED = "FCM_DELIVERY_FAILED";
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationInstallationRepository installationRepository;
    private final NotificationSender sender;
    private final Clock clock;
    private final PayNotificationProperties properties;

    public NotificationDispatchService(
            NotificationOutboxRepository outboxRepository,
            NotificationInstallationRepository installationRepository,
            NotificationSender sender,
            Clock clock,
            PayNotificationProperties properties) {
        this.outboxRepository = outboxRepository;
        this.installationRepository = installationRepository;
        this.sender = sender;
        this.clock = clock;
        this.properties = properties;
    }

    public boolean processNext() {
        Instant now = clock.instant();
        Optional<NotificationOutboxEvent> claimed = outboxRepository.claimNext(
                now,
                now.plus(Duration.ofSeconds(properties.worker().leaseDurationSeconds())));
        if (claimed.isEmpty()) {
            log.debug("notification.outbox.poll.empty");
            return false;
        }

        NotificationOutboxEvent event = claimed.orElseThrow();
        String requestId = event.requestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = PayLogContext.get(PayLogContext.REQUEST_ID);
            if (requestId == null || requestId.isBlank()) {
                requestId = "notification-" + UUID.randomUUID();
            }
        }
        try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.REQUEST_ID, requestId,
                PayLogContext.USER_ID, PayLogContext.fingerprint(event.userId()),
                PayLogContext.APPROVAL_ID, event.approvalId(),
                PayLogContext.PAYMENT_ID, event.paymentId(),
                PayLogContext.EVENT_ID, event.eventId(),
                PayLogContext.PUSH_ID, event.eventId())) {
            List<String> fids = installationRepository.findActiveFidsByUserId(event.userId());
            log.info(
                    "notification.dispatch.start eventId={} eventType={} attempt={} "
                            + "installationCount={}",
                    event.eventId(),
                    event.eventType(),
                    event.attemptCount(),
                    fids.size());
            outboxRepository.ensureDeliveries(event.eventId(), fids, now);
            if (fids.isEmpty()) {
                outboxRepository.markEventRetry(event,
                        now.plusSeconds(properties.worker().retryDelaySeconds()), "NO_ACTIVE_INSTALLATION");
                return true;
            }
            outboxRepository.pruneInactiveDeliveries(event.eventId());
            dispatchDeliveries(event);
            completeEvent(event);
            return true;
        }
    }

    private void dispatchDeliveries(NotificationOutboxEvent event) {
        while (!Thread.currentThread().isInterrupted()) {
            Instant now = clock.instant();
            Optional<NotificationDelivery> claimed = outboxRepository.claimNextDelivery(
                    event.eventId(),
                    now,
                    now.plus(Duration.ofSeconds(properties.worker().leaseDurationSeconds())));
            if (claimed.isEmpty()) {
                return;
            }

            NotificationDelivery delivery = claimed.orElseThrow();
            try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.FID_HASH, PayLogContext.fingerprint(delivery.fid()))) {
                log.info(
                        "notification.delivery.start eventId={} attempt={}",
                        event.eventId(),
                        delivery.attemptCount());
                try {
                    sender.send(delivery.fid(), toMessage(event));
                    if (!outboxRepository.markDeliverySent(delivery, clock.instant())) {
                        log.warn("notification.delivery.finish_conflict");
                        return;
                    }
                    log.info(
                            "notification.delivery.success eventId={} attempt={}",
                            event.eventId(),
                            delivery.attemptCount());
                } catch (NotificationDeliveryException exception) {
                    handleFailure(event, delivery, exception.errorCode(), exception.invalidInstallation());
                } catch (RuntimeException exception) {
                    log.error(
                            "notification.delivery.runtime_failed exceptionType={}",
                            exception.getClass().getSimpleName());
                    handleFailure(event, delivery, DELIVERY_FAILED, false);
                }
            }
        }
    }

    private void handleFailure(
            NotificationOutboxEvent event,
            NotificationDelivery delivery,
            String errorCode,
            boolean invalidInstallation) {
        log.warn(
                "notification.delivery.failed eventId={} attempt={} errorCode={} "
                        + "invalidInstallation={}",
                event.eventId(),
                delivery.attemptCount(),
                errorCode,
                invalidInstallation);
        if (invalidInstallation) {
            installationRepository.deactivate(event.userId(), delivery.fid(), clock.instant());
        }
        boolean exhausted = invalidInstallation || delivery.attemptCount() >= MAX_DELIVERY_ATTEMPTS;
        Instant nextRunAt = clock.instant().plusSeconds(properties.worker().retryDelaySeconds());
        boolean updated = outboxRepository.markDeliveryFailed(delivery, nextRunAt, errorCode, exhausted);
        log.info(
                "notification.delivery.failure_recorded eventId={} exhausted={} updated={}",
                event.eventId(),
                exhausted,
                updated);
    }

    private void completeEvent(NotificationOutboxEvent event) {
        NotificationOutboxRepository.DeliverySummary summary =
                outboxRepository.summarizeDeliveries(event.eventId());
        if (!summary.hasRetryableDeliveries() && !summary.hasExhaustedDeliveries()) {
            boolean updated = outboxRepository.markEventSent(event, clock.instant());
            log.info(
                    "notification.dispatch.complete eventId={} status=SENT exhausted={} updated={}",
                    event.eventId(),
                    summary.hasExhaustedDeliveries(),
                    updated);
            return;
        }
        Instant nextRunAt = summary.nextRunAt() == null
                ? clock.instant().plusSeconds(properties.worker().retryDelaySeconds())
                : summary.nextRunAt();
        boolean updated = outboxRepository.markEventRetry(event, nextRunAt, DELIVERY_FAILED);
        log.info(
                "notification.dispatch.complete eventId={} status=RETRY nextRunAt={} updated={}",
                event.eventId(),
                nextRunAt,
                updated);
    }

    private NotificationMessage toMessage(NotificationOutboxEvent event) {
        if (event.eventType() == NotificationEventType.PURCHASE_HANDOFF) {
            String purchaseId = new tools.jackson.databind.ObjectMapper()
                    .readTree(event.payloadJson()).path("purchaseRequestId").asString();
            UUID.fromString(purchaseId);
            return new NotificationMessage(event.eventId(), event.eventType(), null, null,
                    null, "OPEN_PURCHASE", purchaseId);
        }
        String status = event.paymentStatus() == null ? null : event.paymentStatus().name();
        String nextAction = event.eventType() == NotificationEventType.APPROVAL_REQUESTED
                ? "OPEN_APPROVAL"
                : NotificationMessage.nextAction(event.paymentStatus());
        return new NotificationMessage(
                event.eventId(),
                event.eventType(),
                event.approvalId(),
                event.paymentId(),
                status,
                nextAction);
    }
}
