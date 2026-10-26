package com.copenhagen.merchantmock.paymenthandler;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 몰별 Pay 토큰(393, DESIGN.md 3.4절). Pay 는 토큰 하나를 merchantId 하나에 매핑하므로 몰 셋이면 토큰 셋이다. */
class PayPropertiesTest {

    private static PayProperties props(String fallback, String json) {
        return new PayProperties("http://pay-service:8080", fallback, json,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(30), 10);
    }

    @Test
    @DisplayName("JSON 에 있는 몰은 그 토큰, 없는 몰은 fallback, 둘 다 없으면 빈 문자열")
    void tokenFor_resolvesPerMall() {
        // given
        PayProperties withJson = props("fallback-token", "{\"merchant-a\":\"tok-a\",\"merchant-b\":\" tok-b \"}");
        PayProperties none = props("", "");

        // when & then
        assertEquals("tok-a", withJson.tokenFor("merchant-a"));
        assertEquals("tok-b", withJson.tokenFor("merchant-b"));
        assertEquals("fallback-token", withJson.tokenFor("merchant-c"));
        assertEquals("", none.tokenFor("merchant-a"));
    }

    @Test
    @DisplayName("형식이 깨진 JSON 은 기동에서 잡힌다")
    void constructor_malformedJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> props("", "{not json"));
    }
}
