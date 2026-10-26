package com.copenhagen.merchantmock.support;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 감사 이벤트 한 줄의 모양(DESIGN.md 12.1절). 식별자는 MDC 에서 오고, 빈 값은 안 쓰고, 위험한 키는 거절한다.
 */
class AuditTest {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        // 서비스 단위 테스트가 MDC 에 checkoutId 를 넣고 지우지 않는다(요청 스레드라면 필터가 지운다). 같은 스레드에서
        // 이어 돌면 여기로 새어 들어오므로 시작할 때 비운다.
        MDC.clear();
        auditLogger = (Logger) LoggerFactory.getLogger(Audit.LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    @DisplayName("event 가 먼저, 그다음 MDC 식별자, 그다음 before·after·reason, 나머지는 with 순서다")
    void log_withContext_writesOrderedJson() throws Exception {
        MDC.put(LogContext.REQUEST_ID, "req-1");
        MDC.put(LogContext.CHECKOUT_ID, "co-1");

        Audit.event("payment.finished").before("PROCESSING").after("SUCCESS").reason(null)
                .with("http", 200).with(LogContext.PAYMENT_ID, "pay-1").log();

        assertEquals(1, appender.list.size());
        String line = appender.list.get(0).getFormattedMessage();
        Map<String, Object> json = objectMapper.readValue(line, JSON_OBJECT);
        assertEquals(List.of("event", "requestId", "checkoutId", "paymentId", "before", "after", "http"),
                List.copyOf(json.keySet()));
        assertEquals("payment.finished", json.get("event"));
        assertEquals("req-1", json.get("requestId"));
        assertEquals("pay-1", json.get("paymentId"), "with 로 준 식별자가 MDC 보다 우선한다");
        assertEquals("SUCCESS", json.get("after"));
        assertFalse(json.containsKey("reason"), "없는 값은 키도 쓰지 않는다");
        assertFalse(line.contains("\n"), "한 줄이어야 grep 이 된다");
    }

    @Test
    @DisplayName("MDC 가 비어 있으면 식별자 없이 event 만 남는다")
    void log_withoutContext_hasNoIdentifierKeys() throws Exception {
        Audit.event("checkout.expired").after("canceled").with("count", 3).log();

        Map<String, Object> json = objectMapper.readValue(appender.list.get(0).getFormattedMessage(), JSON_OBJECT);
        assertEquals(List.of("event", "after", "count"), List.copyOf(json.keySet()));
        assertEquals(3, json.get("count"));
    }

    @Test
    @DisplayName("credential·token·address 처럼 이름만으로 위험한 키는 첫 실행에서 거절한다")
    void with_forbiddenKey_throws() {
        assertThrows(IllegalArgumentException.class, () -> Audit.event("x").with("credentialId", "c"));
        assertThrows(IllegalArgumentException.class, () -> Audit.event("x").with("bearerToken", "t"));
        assertThrows(IllegalArgumentException.class, () -> Audit.event("x").with("shippingAddress", "a"));
        assertThrows(IllegalArgumentException.class, () -> Audit.event("x").with("privateKeyPem", "p"));
        assertTrue(appender.list.isEmpty(), "거절된 이벤트는 아무것도 쓰지 않는다");
    }
}
