package com.copenhagen.core.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessLogLineTest {

    @AfterEach
    void clearContext() {
        CoreLogContext.clear();
    }

    @Test
    @DisplayName("event가 먼저 오고 MDC의 requestId·userId가 이어 붙는다")
    void log_pullsContextKeysFromMdc() {
        CoreLogContext.put(CoreLogContext.REQUEST_ID, "core-req-42");
        CoreLogContext.put(CoreLogContext.USER_ID, "9c1b0000-0000-0000-0000-000000000001");

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            AccessLogLine.event("http.access").with("status", 200).log();

            assertThat(recorder.singleLine()).isEqualTo("{\"event\":\"http.access\","
                    + "\"requestId\":\"core-req-42\","
                    + "\"userId\":\"9c1b0000-0000-0000-0000-000000000001\","
                    + "\"status\":200}");
        }
    }

    @Test
    @DisplayName("MDC가 비어 있으면 그 키는 아예 쓰지 않는다")
    void log_withoutContext_omitsKeys() {
        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            AccessLogLine.event("http.access").with("status", 401).log();

            assertThat(recorder.singleLine()).isEqualTo("{\"event\":\"http.access\",\"status\":401}");
        }
    }

    @Test
    @DisplayName("null 값은 키째로 생략한다")
    void with_nullValue_omitsKey() {
        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            AccessLogLine.event("http.access")
                    .with("errorCode", null)
                    .with("status", 200)
                    .log();

            assertThat(recorder.singleLine()).doesNotContain("errorCode");
        }
    }

    @Test
    @DisplayName("민감정보로 보이는 키는 거절한다 — 본문·헤더가 로그로 새는 경로를 첫 실행에서 막는다")
    void with_forbiddenKey_throws() {
        AccessLogLine line = AccessLogLine.event("http.access");

        assertThatThrownBy(() -> line.with("password", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.with("Authorization", "Bearer x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.with("refreshToken", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.with("email", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.with("addressLine1", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.with("cardNumber", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("errorCode 값에 TOKEN이 들어가도 키 검사만 하므로 통과한다")
    void with_errorCodeValue_isAllowed() {
        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            AccessLogLine.event("http.access").with("errorCode", "AUTH_TOKEN_MISSING").log();

            assertThat(recorder.singleLine()).contains("\"errorCode\":\"AUTH_TOKEN_MISSING\"");
        }
    }
}
