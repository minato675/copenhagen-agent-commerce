package com.copenhagen.pay.payment.reconciliation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.copenhagen.pay.common.observability.PayLogCapture;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayWorkerTest {

    @Test
    void runBatch_emptyQueue_stopsImmediately() {
        ReconciliationService service = mock(ReconciliationService.class);
        new PayWorker(service, 10).runBatch();
        verify(service).processNext();
    }

    @Test
    void runBatch_fullQueue_respectsBatchLimit() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.processNext()).thenReturn(true);
        new PayWorker(service, 3).runBatch();
        verify(service, times(3)).processNext();
    }

    @Test
    void runBatch_oneFailure_continuesWithRemainingJobs() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.processNext()).thenThrow(new IllegalStateException(
                "PIN=123456 CARD=4111111111111111 Authorization=Bearer synthetic-token "
                        + "Cookie=synthetic-cookie credential=synthetic-credential "
                        + "FCM=synthetic-fcm-token")).thenReturn(false);
        new PayWorker(service, 3).runBatch();
        verify(service, times(2)).processNext();
    }

    @Test
    void runBatch_failureLogContainsStageAndWorkerTraceWithoutExceptionPayload() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.processNext()).thenThrow(new IllegalStateException(
                "sensitive-reconciliation-payload")).thenReturn(false);

        try (PayLogCapture logs = new PayLogCapture()) {
            new PayWorker(service, 3).runBatch();

            assertEquals(1, logs.count("pay.reconciliation.worker.item_failed"));
            assertTrue(logs.allText().contains("failureStage=PROCESS_NEXT"));
            assertTrue(logs.allText().contains("exceptionType=IllegalStateException"));
            assertFalse(logs.allText().contains("sensitive-reconciliation-payload"));
            assertTrue(logs.events().stream().anyMatch(event ->
                    event.getMDCPropertyMap().get("requestId") != null
                            && event.getMDCPropertyMap().get("requestId")
                            .startsWith("reconciliation-")));
        }
    }
}
