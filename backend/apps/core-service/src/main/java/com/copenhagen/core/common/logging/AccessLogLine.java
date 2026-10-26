package com.copenhagen.core.common.logging;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청·응답 기록 한 건. 한 줄 JSON으로 {@code access} 로거에 남긴다(이슈 374, 구현순서 26).
 *
 * <p>모양은 {@code {"event":..., "requestId":..., "userId":..., "method":..., "path":..., "status":...}}.
 * {@code requestId}·{@code userId}는 MDC에서 자동으로 가져오고 {@link #with}로 덮을 수 있다. 값이 없는 키는
 * 쓰지 않는다.
 *
 * <p><b>본문은 싣지 않는다.</b> {@code backend/docs/code-convention.md} §로깅이 "요청·응답 DTO 전체"를
 * 금지하고, Core 요청 본문에는 비밀번호·Refresh Token·이메일·배송지가 실제로 들어 있다. 이름만으로 위험이
 * 보이는 키는 {@link #with}가 거절한다 — 실수를 컴파일이 아니라 첫 실행에서 잡는 장치다
 * ({@code ucp-merchant-mock}의 {@code Audit.Event.with}와 같다).
 *
 * <p>정적이다. 로거가 정적인 것과 같은 이유고, 주입으로 만들면 필터·인터셉터의 생성자가 같이 바뀐다.
 */
public final class AccessLogLine {

    public static final String LOGGER_NAME = "access";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** MDC에서 자동으로 끌어오는 키. {@code errorCode}는 부르는 쪽이 필요할 때만 명시적으로 넣는다. */
    private static final String[] CONTEXT_KEYS = {CoreLogContext.REQUEST_ID, CoreLogContext.USER_ID};

    private static final String[] FORBIDDEN_KEY_PARTS = {
        "password", "token", "authorization", "cookie", "secret", "pin", "card", "cvc",
        "email", "phone", "address", "recipient", "credential", "privatekey", "private_key"
    };

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private AccessLogLine(String event) {
        fields.put("event", event);
        for (String key : CONTEXT_KEYS) {
            String value = CoreLogContext.get(key);
            if (value != null) {
                fields.put(key, value);
            }
        }
    }

    public static AccessLogLine event(String event) {
        return new AccessLogLine(event);
    }

    /** 식별자나 개수 같은 짧은 값만. 민감정보로 보이는 키는 거절한다. null 값은 무시한다. */
    public AccessLogLine with(String key, Object value) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String part : FORBIDDEN_KEY_PARTS) {
            if (lower.contains(part)) {
                throw new IllegalArgumentException("요청·응답 로그에 실을 수 없는 키다: " + key);
            }
        }
        if (value != null) {
            fields.put(key, value);
        }
        return this;
    }

    public void log() {
        LOG.info(toJson(fields));
    }

    private static String toJson(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JacksonException exception) {
            // 값이 전부 문자열·숫자라 여기 올 일이 없다. 와도 로그 때문에 본 흐름을 깨지는 않는다.
            return "{\"event\":\"" + fields.get("event") + "\",\"error\":\"serialization\"}";
        }
    }
}
