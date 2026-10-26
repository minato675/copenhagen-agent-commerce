package com.copenhagen.merchantmock.checkout;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 멱등 키에서 체크아웃 id 를 만드는 규칙(DESIGN.md 8.1절). */
class CheckoutIdsTest {

    @Test
    @DisplayName("같은 키면 같은 id 가 나온다")
    void fromIdempotencyKey_sameKey_returnsSameId() {
        // when & then
        assertEquals(
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"),
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"));
        assertEquals(
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"),
                CheckoutIds.fromIdempotencyKey("  checkout-create-abc  "), "앞뒤 공백은 무시한다");
    }

    @Test
    @DisplayName("다른 키면 다른 id 가 나온다")
    void fromIdempotencyKey_differentKey_returnsDifferentId() {
        // when & then
        assertNotEquals(
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"),
                CheckoutIds.fromIdempotencyKey("checkout-create-abd"));
    }

    @Test
    @DisplayName("키가 없으면 매번 다른 랜덤 id 가 나온다")
    void fromIdempotencyKey_blankKey_returnsRandomId() {
        // when & then
        assertNotEquals(CheckoutIds.fromIdempotencyKey(null), CheckoutIds.fromIdempotencyKey(null));
        assertNotEquals(CheckoutIds.fromIdempotencyKey(""), CheckoutIds.fromIdempotencyKey(" "));
    }

    @Test
    @DisplayName("만들어진 id 는 UUIDv5 형식이다")
    void fromIdempotencyKey_anyKey_returnsUuidV5() {
        // when
        UUID id = UUID.fromString(CheckoutIds.fromIdempotencyKey("checkout-create-abc"));

        // then
        assertEquals(5, id.version());
        assertEquals(2, id.variant(), "RFC 4122 variant");
    }

    @Test
    @DisplayName("RFC 4122 부록 벡터와 맞는다")
    void v5_rfc4122Vector_matchesKnownValue() {
        // given
        // RFC 4122 의 DNS 네임스페이스로 "www.example.com" 을 v5 하면 이 값이다. 공식 벡터라 구현 회귀를 잡는다.
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        // when & then
        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2", CheckoutIds.v5(dns, "www.example.com").toString());
    }
}
