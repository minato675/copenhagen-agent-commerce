package com.copenhagen.core.common.logging;

import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;

/**
 * 로그 컨텍스트(MDC) 키와 그것을 넣고 빼는 방법(이슈 374, 구현순서 26).
 *
 * <p>{@code ucp-merchant-mock}의 {@code LogContext}(이슈 187)를 Core에 옮긴 것이다. 키는 셋뿐이다 —
 * {@code requestId}는 {@link CoreRequestIdFilter}가 요청 시작에 넣고 끝에 전부 지운다. {@code userId}는
 * 인증 필터가 토큰을 검증한 뒤 더한다. {@code errorCode}는 오류 응답을 쓰는 지점이 더한다.
 *
 * <p>추적 키는 {@code X-Request-Id} 하나다. Merchant mock·Pay가 이미 같은 헤더를 쓰고 있어 세 서비스 로그를
 * 한 값으로 이어 볼 수 있다. traceId를 새로 만들지 않는 이유는 팀 어디에도 그것을 만들거나 전파하는 코드가
 * 없기 때문이다.
 *
 * <p>Core에는 백그라운드 워커가 없으므로 mock에 있는 {@code open}·{@code propagate}는 옮기지 않았다.
 * 필요해지는 시점에 더한다.
 */
public final class CoreLogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String ERROR_CODE = "errorCode";

    /** 로그 패턴과 access 줄이 읽는 순서다. */
    public static final List<String> KEYS = List.of(REQUEST_ID, USER_ID, ERROR_CODE);

    private CoreLogContext() {
    }

    public static String get(String key) {
        return MDC.get(key);
    }

    /** 요청 컨텍스트가 있으면 그 값, 없으면(테스트·기동 중 호출) 새 UUID. 밖으로 나가는 호출에 싣는 값이다. */
    public static String requestIdOrNew() {
        String current = MDC.get(REQUEST_ID);
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    /** null이나 빈 값은 넣지 않는다. 로그 패턴의 {@code %X{key:-}}가 빈 칸으로 찍는다. */
    public static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    /** 세 키만 지운다. 다른 라이브러리가 넣은 MDC는 건드리지 않는다. */
    public static void clear() {
        KEYS.forEach(MDC::remove);
    }
}
