package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.observability.PayLogCapture;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationDispatchObservabilityTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String FID = "synthetic-fcm-token-not-for-logs";

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void invalidInstallationFailureRecordsAttemptStageAndTaskContext() {
        NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        NotificationInstallationRepository installations = mock(NotificationInstallationRepository.class);
        NotificationSender sender = mock(NotificationSender.class);
        NotificationOutboxEvent event = event("request-123");
        NotificationDelivery delivery = delivery(1);
        when(outbox.claimNext(any(), any())).thenReturn(Optional.of(event));
        when(installations.findActiveFidsByUserId("user-1")).thenReturn(List.of(FID));
        when(outbox.claimNextDelivery(anyString(), any(), any()))
                .thenReturn(Optional.of(delivery), Optional.empty());
        when(outbox.markDeliveryFailed(any(), any(), anyString(), any(Boolean.class))).thenReturn(true);
        when(outbox.summarizeDeliveries("event-1"))
                .thenReturn(new NotificationOutboxRepository.DeliverySummary(false, true, null));
        when(outbox.markEventRetry(any(), any(), anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new NotificationDeliveryException(
                "FCM_FID_UNREGISTERED",
                true,
                null)).when(sender).send(anyString(), any());

        try (PayLogCapture logs = new PayLogCapture()) {
            boolean processed = service(outbox, installations, sender).processNext();

            assertTrue(processed);
            assertEquals(1, logs.count("notification.dispatch.start"));
            assertEquals(1, logs.count("notification.delivery.failed"));
            assertEquals(1, logs.count("notification.delivery.failure_recorded"));
            assertEquals(1, logs.count("notification.dispatch.complete"));
            assertTrue(logs.allText().contains("eventId=event-1"));
            assertTrue(logs.allText().contains("attempt=1"));
            assertTrue(logs.allText().contains("errorCode=FCM_FID_UNREGISTERED"));
            assertTrue(logs.allText().contains("status=RETRY"));
            assertFalse(logs.allText().contains(FID));
            assertTrue(logs.events().stream().anyMatch(eventLog ->
                    "request-123".equals(eventLog.getMDCPropertyMap().get(PayLogContext.REQUEST_ID))
                            && "event-1".equals(eventLog.getMDCPropertyMap().get(PayLogContext.PUSH_ID))
                            && PayLogContext.fingerprint(FID).equals(
                                    eventLog.getMDCPropertyMap().get(PayLogContext.FID_HASH))));
        }

        verify(installations).deactivate("user-1", FID, NOW);
    }

    @Test
    void transientFailureRecordsRetryOutcomeWithNextRunTime() {
        NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        NotificationInstallationRepository installations = mock(NotificationInstallationRepository.class);
        NotificationSender sender = mock(NotificationSender.class);
        NotificationOutboxEvent event = event("request-retry-123");
        NotificationDelivery delivery = delivery(2);
        when(outbox.claimNext(any(), any())).thenReturn(Optional.of(event));
        when(installations.findActiveFidsByUserId("user-1")).thenReturn(List.of(FID));
        when(outbox.claimNextDelivery(anyString(), any(), any()))
                .thenReturn(Optional.of(delivery), Optional.empty());
        when(outbox.markDeliveryFailed(any(), any(), anyString(), any(Boolean.class))).thenReturn(true);
        when(outbox.summarizeDeliveries("event-1"))
                .thenReturn(new NotificationOutboxRepository.DeliverySummary(
                        true,
                        false,
                        NOW.plusSeconds(60)));
        when(outbox.markEventRetry(any(), any(), anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new NotificationDeliveryException(
                "FCM_UNAVAILABLE",
                false,
                null)).when(sender).send(anyString(), any());

        try (PayLogCapture logs = new PayLogCapture()) {
            assertTrue(service(outbox, installations, sender).processNext());

            assertTrue(logs.allText().contains("attempt=2"));
            assertTrue(logs.allText().contains("errorCode=FCM_UNAVAILABLE"));
            assertTrue(logs.allText().contains("status=RETRY"));
            assertTrue(logs.allText().contains("nextRunAt=2026-09-17T00:01:00Z"));
            assertFalse(logs.allText().contains(FID));
        }
    }

    private NotificationDispatchService service(
            NotificationOutboxRepository outbox,
            NotificationInstallationRepository installations,
            NotificationSender sender) {
        return new NotificationDispatchService(
                outbox,
                installations,
                sender,
                CLOCK,
                new PayNotificationProperties(
                        new PayNotificationProperties.Fcm(false, "", ""),
                        new PayNotificationProperties.Worker(true, 5000, 10, 60, 60)));
    }

    private NotificationOutboxEvent event(String requestId) {
        return new NotificationOutboxEvent(
                "row-1",
                "event-1",
                NotificationEventType.PAYMENT_RESULT,
                "user-1",
                requestId,
                "approval-1",
                "payment-1",
                PaymentStatus.SUCCESS,
                "{}",
                NotificationOutboxStatus.RUNNING,
                3,
                NOW,
                NOW.plusSeconds(60),
                null,
                null,
                NOW);
    }

    private NotificationDelivery delivery(int attemptCount) {
        return new NotificationDelivery(
                "delivery-1",
                "event-1",
                FID,
                attemptCount,
                NOW.plusSeconds(60));
    }
}
