package com.copenhagen.merchantmock.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 감사 이벤트. 상태 전이 하나를 한 줄 JSON 으로 {@code audit} 로거에 남긴다(DESIGN.md 12.1절, Jira 187).
 *
 * <p>모양은 {@code {"event":..., "requestId":..., "checkoutId":..., "before":..., "after":..., "reason":..., ...}}.
 * 식별자 다섯은 MDC 에서 자동으로 가져오고 {@link Event#with} 로 덮을 수 있다. 값이 없는 키는 쓰지 않는다.
 *
 * <p>Artifact 원문은 싣지 않는다. 해시와 kid 만이다(설계서 22.3). {@code credentialId}·개인키·배송지처럼
 * 이름만으로 위험이 보이는 키는 {@link Event#with} 가 거절한다. 실수를 컴파일이 아니라 첫 실행에서 잡는 장치다.
 *
 * <p>정적이다. 로거가 정적인 것과 같은 이유고, 주입으로 만들면 생성자가 있는 클래스 여덟과 테스트 일곱이 같이 바뀐다.
 * 새 의존성도 없다 — 팀 어느 서비스도 JSON 인코더 라이브러리를 쓰지 않고 시연에 필요한 것은 grep 이 되는 한 줄이다.
 */
public final class Audit {

    public static final String LOGGER_NAME = "audit";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String[] FORBIDDEN_KEY_PARTS = {
        "credential", "token", "secret", "pem", "privatekey", "private_key", "address", "card", "pin", "cvc"
    };

    private Audit() {
    }

    public static Event event(String name) {
        return new Event(name);
    }

    /** 이벤트 하나. {@link #log()} 를 부르기 전까지는 아무것도 쓰지 않는다. */
    public static final class Event {

        private final String name;
        private final Map<String, Object> extras = new LinkedHashMap<>();
        private String before;
        private String after;
        private String reason;

        private Event(String name) {
            this.name = name;
        }

        public Event before(Object state) {
            this.before = state == null ? null : state.toString();
            return this;
        }

        public Event after(Object state) {
            this.after = state == null ? null : state.toString();
            return this;
        }

        public Event reason(String reason) {
            this.reason = reason;
            return this;
        }

        /** 식별자나 개수 같은 짧은 값만. 민감정보로 보이는 키는 거절한다(18.10). */
        public Event with(String key, Object value) {
            String lower = key.toLowerCase(Locale.ROOT);
            for (String part : FORBIDDEN_KEY_PARTS) {
                if (lower.contains(part)) {
                    throw new IllegalArgumentException("감사 로그에 실을 수 없는 키다: " + key);
                }
            }
            if (value != null) {
                extras.put(key, value);
            }
            return this;
        }

        public void log() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("event", name);
            for (String key : LogContext.KEYS) {
                Object value = extras.containsKey(key) ? extras.get(key) : LogContext.get(key);
                if (value != null) {
                    fields.put(key, value);
                }
            }
            if (before != null) {
                fields.put("before", before);
            }
            if (after != null) {
                fields.put("after", after);
            }
            if (reason != null) {
                fields.put("reason", reason);
            }
            extras.forEach((key, value) -> {
                if (!LogContext.KEYS.contains(key)) {
                    fields.put(key, value);
                }
            });
            LOG.info(toJson(fields));
        }

        private static String toJson(Map<String, Object> fields) {
            try {
                return MAPPER.writeValueAsString(fields);
            } catch (JacksonException e) {
                // 값이 전부 문자열·숫자라 여기 올 일이 없다. 와도 감사 로그 때문에 본 흐름을 깨지는 않는다.
                return "{\"event\":\"" + fields.get("event") + "\",\"error\":\"serialization\"}";
            }
        }
    }
}
