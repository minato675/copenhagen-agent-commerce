package com.copenhagen.merchantmock.support;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MDC 키 다섯을 넣고 빼는 규칙(DESIGN.md 12.1절). 스레드를 넘어갈 때 잃지 않는지가 핵심이다.
 */
class LogContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("open 은 넣고 close 는 넣기 전 값으로 되돌린다")
    void open_thenClose_restoresPreviousValues() {
        MDC.put(LogContext.REQUEST_ID, "req-outer");

        try (LogContext.Scope scope = LogContext.open(LogContext.REQUEST_ID, "req-inner",
                LogContext.WEBHOOK_ID, "wh-1")) {
            assertEquals("req-inner", MDC.get(LogContext.REQUEST_ID));
            assertEquals("wh-1", MDC.get(LogContext.WEBHOOK_ID));
        }

        assertEquals("req-outer", MDC.get(LogContext.REQUEST_ID), "바깥 값으로 돌아온다");
        assertNull(MDC.get(LogContext.WEBHOOK_ID), "없던 키는 지워진다");
    }

    @Test
    @DisplayName("requestIdOrNew 는 요청 컨텍스트가 있으면 그 값, 없으면 새 값이다")
    void requestIdOrNew_withAndWithoutContext() {
        assertNotNull(LogContext.requestIdOrNew());
        assertNotEquals(LogContext.requestIdOrNew(), LogContext.requestIdOrNew(), "없으면 매번 새로 만든다");

        MDC.put(LogContext.REQUEST_ID, "req-1");
        assertEquals("req-1", LogContext.requestIdOrNew());
    }

    @Test
    @DisplayName("propagate 는 지금 스레드의 컨텍스트를 다른 스레드로 옮기고 그쪽 스레드를 더럽히지 않는다")
    void propagate_carriesContextIntoAnotherThread() throws InterruptedException {
        MDC.put(LogContext.REQUEST_ID, "req-1");
        MDC.put(LogContext.CHECKOUT_ID, "co-1");
        AtomicReference<String> seenRequestId = new AtomicReference<>();
        AtomicReference<String> seenCheckoutId = new AtomicReference<>();
        AtomicReference<String> afterRun = new AtomicReference<>("unset");
        Runnable task = LogContext.propagate(() -> {
            seenRequestId.set(MDC.get(LogContext.REQUEST_ID));
            seenCheckoutId.set(MDC.get(LogContext.CHECKOUT_ID));
        });

        Thread worker = new Thread(() -> {
            task.run();
            afterRun.set(MDC.get(LogContext.REQUEST_ID));
        });
        worker.start();
        worker.join();

        assertEquals("req-1", seenRequestId.get());
        assertEquals("co-1", seenCheckoutId.get());
        assertNull(afterRun.get(), "작업이 끝나면 워커 스레드의 MDC 는 비어 있어야 한다");
    }

    @Test
    @DisplayName("put 은 null 과 빈 값을 넣지 않고, clear 는 다섯 키만 지운다")
    void put_ignoresBlank_andClearLeavesOtherKeys() {
        LogContext.put(LogContext.PAYMENT_ID, null);
        LogContext.put(LogContext.ORDER_ID, " ");
        assertNull(MDC.get(LogContext.PAYMENT_ID));
        assertNull(MDC.get(LogContext.ORDER_ID));

        MDC.put("someone-elses-key", "keep");
        LogContext.put(LogContext.CHECKOUT_ID, "co-1");
        LogContext.clear();
        assertNull(MDC.get(LogContext.CHECKOUT_ID));
        assertEquals("keep", MDC.get("someone-elses-key"));
    }

    @Test
    @DisplayName("키·값 쌍이 아니면 거절한다")
    void open_oddArguments_throws() {
        assertThrows(IllegalArgumentException.class, () -> LogContext.open(LogContext.REQUEST_ID));
    }
}
