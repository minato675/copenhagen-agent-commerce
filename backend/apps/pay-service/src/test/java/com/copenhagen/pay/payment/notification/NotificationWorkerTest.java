package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.observability.PayLogCapture;
import org.junit.jupiter.api.Test;

class NotificationWorkerTest {

    @Test
    void runBatch_failureLogContainsStageAndWorkerTraceWithoutExceptionPayload() {
        NotificationDispatchService service = mock(NotificationDispatchService.class);
        when(service.processNext()).thenThrow(new IllegalStateException(
                "PIN=123456 CARD=4111111111111111 Authorization=Bearer synthetic-token "
                        + "Cookie=synthetic-cookie credential=synthetic-credential "
                        + "FCM=synthetic-fcm-token")).thenReturn(false);
        PayNotificationProperties properties = new PayNotificationProperties(
                new PayNotificationProperties.Fcm(false, "", ""),
                new PayNotificationProperties.Worker(true, 5000, 3, 60, 60));

        try (PayLogCapture logs = new PayLogCapture()) {
            new NotificationWorker(service, properties).runBatch();

            assertEquals(1, logs.count("notification.worker.item_failed"));
            assertTrue(logs.allText().contains("failureStage=PROCESS_NEXT"));
            assertTrue(logs.allText().contains("exceptionType=IllegalStateException"));
            assertFalse(logs.allText().contains("PIN=123456"));
            assertTrue(logs.events().stream().anyMatch(event ->
                    event.getMDCPropertyMap().get("requestId") != null
                            && event.getMDCPropertyMap().get("requestId")
                            .startsWith("notification-")));
        }

        verify(service, times(2)).processNext();
    }
}
