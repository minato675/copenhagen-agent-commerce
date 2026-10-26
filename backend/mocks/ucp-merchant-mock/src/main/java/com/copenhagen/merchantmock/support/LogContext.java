package com.copenhagen.merchantmock.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;

/**
 * 로그 컨텍스트(MDC) 키 다섯과 그것을 넣고 빼는 방법(DESIGN.md 12.1절, Jira 187).
 *
 * <p>요청 스레드는 {@link RequestIdFilter} 가 {@code requestId} 를 넣고 끝에 전부 지운다. 서비스는 알게 된
 * 식별자를 {@link #put} 으로 더한다. 워커와 커밋 뒤 발송 스레드에는 요청 컨텍스트가 없으므로 {@link #open} 으로
 * 범위를 열고 {@link #propagate} 로 요청의 컨텍스트를 다른 스레드에 넘긴다.
 *
 * <p>추적 키는 {@code X-Request-Id} 하나다. 팀 어디에도 traceId 를 만들거나 보내는 코드가 없어 없는 것을
 * 발명하지 않는다. 받은 값이 있으면 그대로 쓰고 없으면 만든다. Pay 호출과 웹훅에 같은 값을 실어 보낸다.
 */
public final class LogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String CHECKOUT_ID = "checkoutId";
    public static final String PAYMENT_ID = "paymentId";
    public static final String ORDER_ID = "orderId";
    public static final String WEBHOOK_ID = "webhookId";

    /** 로그 패턴과 감사 이벤트가 읽는 순서다. */
    public static final List<String> KEYS = List.of(REQUEST_ID, CHECKOUT_ID, PAYMENT_ID, ORDER_ID, WEBHOOK_ID);

    private LogContext() {
    }

    public static String get(String key) {
        return MDC.get(key);
    }

    /** 요청 컨텍스트가 있으면 그 값, 없으면(워커·테스트) 새 UUID. 밖으로 나가는 호출에 싣는 값이다. */
    public static String requestIdOrNew() {
        String current = MDC.get(REQUEST_ID);
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    /** null 이나 빈 값은 넣지 않는다. 패턴의 {@code %X{key:-}} 가 빈 칸으로 찍는다. */
    public static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    /** 다섯 키만 지운다. 다른 라이브러리가 넣은 MDC 는 건드리지 않는다. */
    public static void clear() {
        KEYS.forEach(MDC::remove);
    }

    /**
     * 키·값 쌍을 넣고 닫을 때 이전 값으로 되돌리는 범위. {@code try (Scope s = LogContext.open(...))} 로 쓴다.
     * 워커가 행 하나를 처리하는 동안만 식별자를 붙일 때 쓴다.
     */
    public static Scope open(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("키·값 쌍이어야 한다: " + keyValues.length);
        }
        Map<String, String> previous = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = keyValues[i];
            previous.put(key, MDC.get(key));
            MDC.remove(key);
            put(key, keyValues[i + 1]);
        }
        return new Scope(previous);
    }

    /** 워커 배치 한 건에 새 requestId 를 연다. 접두어로 어느 워커인지 알 수 있다. */
    public static Scope openWorker(String prefix) {
        return open(REQUEST_ID, prefix + "-" + UUID.randomUUID());
    }

    /**
     * 지금 스레드의 MDC 를 복사해 두고, 넘겨받은 작업을 다른 스레드에서 돌릴 때 그 복사본을 깔아 준다.
     * 커밋 뒤 발송 스레드가 요청의 {@code requestId} 를 이어받는 경로다(8.6절).
     */
    public static Runnable propagate(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> before = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            }
            try {
                task.run();
            } finally {
                if (before == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(before);
                }
            }
        };
    }

    /** {@link #open} 이 돌려주는 범위. 닫으면 넣기 전 값으로 돌아간다. */
    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
