package com.copenhagen.core.common.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;

/** Core 요청과 로그인 작업의 공통 로그 컨텍스트입니다. */
public final class CoreLogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String APP_VERSION = "appVersion";
    public static final String OS_VERSION = "osVersion";
    public static final String PLATFORM = "platform";

    private static final List<String> KEYS = List.of(
            REQUEST_ID,
            USER_ID,
            APP_VERSION,
            OS_VERSION,
            PLATFORM);
    private static final int FINGERPRINT_LENGTH = 16;

    private CoreLogContext() {
    }

    public static String get(String key) {
        return MDC.get(key);
    }

    public static String requestIdOrNew() {
        String requestId = MDC.get(REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            MDC.put(REQUEST_ID, requestId);
        }
        return requestId;
    }

    public static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    public static void putFingerprint(String key, String value) {
        put(key, fingerprint(value));
    }

    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    public static void clear() {
        KEYS.forEach(MDC::remove);
    }

    public static Scope open(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("로그 컨텍스트는 키·값 쌍이어야 합니다.");
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
