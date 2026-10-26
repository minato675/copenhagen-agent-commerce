package com.copenhagen.merchantmock.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * checkoutDigest 테스트 벡터(DESIGN.md 6.3절).
 *
 * <p>기대값은 손으로 계산해 박아 둔 것이다. 구현을 고쳐 값이 달라지면 여기서 잡힌다.
 * Core 와 벡터를 교환할 때 이 값을 그대로 보낸다.
 */
class CheckoutDigestTest {

    @Test
    @DisplayName("알려진 입력에 알려진 다이제스트가 나온다")
    void of_knownCanonicalString_returnsKnownDigest() {
        // given
        String canonical = "{\"a\":1}";

        // when
        String digest = CheckoutDigest.of(canonical);

        // then
        assertEquals("sha256:AVq9f1zFei3ZS3WQ8ErYCEJzkF7jPsXOvq5iJ2qX-GI", digest);
    }

    @Test
    @DisplayName("체크아웃 모양의 정규화본에도 고정된 값이 나온다")
    void of_checkoutShapedCanonicalString_returnsKnownDigest() {
        // given
        String canonical = "{\"amount\":89000,\"currency\":\"KRW\",\"id\":\"checkout-1\"}";

        // when
        String digest = CheckoutDigest.of(canonical);

        // then
        assertEquals("sha256:ed1RKeAS06Kw9qOKlaqmV9y8eMki0SNCvw1e14VmXdM", digest);
    }

    @Test
    @DisplayName("빈 객체의 다이제스트도 고정이다")
    void of_emptyObject_returnsKnownDigest() {
        // given & when
        String digest = CheckoutDigest.of("{}");

        // then
        assertEquals("sha256:RBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o", digest);
    }

    @Test
    @DisplayName("접두어가 붙고 base64url 무패딩이다")
    void of_anyInput_hasPrefixAndNoPadding() {
        // given & when
        String digest = CheckoutDigest.of("{\"id\":\"checkout-1\"}");

        // then
        assertTrue(digest.startsWith("sha256:"));
        assertFalse(digest.contains("="));
        // base64url 은 표준 base64 의 + / 대신 - _ 를 쓴다. URL 과 JSON 에 그대로 실린다.
        assertFalse(digest.contains("+"));
        assertFalse(digest.contains("/"));
        // SHA-256 32바이트를 무패딩 base64 로 쓰면 43자다. 접두어 7자를 더해 50자.
        assertEquals(50, digest.length());
    }

    @Test
    @DisplayName("정규화를 거치지 않으면 키 순서만 달라도 다이제스트가 갈린다")
    void of_sameDataDifferentOrder_differsWithoutCanonicalization() {
        // given
        // 정규화가 왜 서명 앞에 와야 하는지를 고정한다. 같은 의미의 JSON 두 개다.
        String first = "{\"b\":2,\"a\":1}";
        String second = "{\"a\":1,\"b\":2}";

        // when
        String rawFirst = CheckoutDigest.of(first);
        String rawSecond = CheckoutDigest.of(second);

        // then
        assertNotEquals(rawSecond, rawFirst);
        assertEquals(
                CheckoutDigest.of(CanonicalJson.canonicalize(second)),
                CheckoutDigest.of(CanonicalJson.canonicalize(first)));
    }

    @Test
    @DisplayName("같은 값이면 대조가 통과한다")
    void matches_sameValue_returnsTrue() {
        // given
        String digest = CheckoutDigest.of("{\"id\":\"checkout-1\"}");

        // when & then
        assertTrue(CheckoutDigest.matches(digest, digest));
    }

    @Test
    @DisplayName("한 글자만 달라도 대조가 실패한다")
    void matches_differentValue_returnsFalse() {
        // given
        String mine = CheckoutDigest.of("{\"id\":\"checkout-1\"}");
        String theirs = CheckoutDigest.of("{\"id\":\"checkout-2\"}");

        // when & then
        assertFalse(CheckoutDigest.matches(mine, theirs));
    }

    @Test
    @DisplayName("null 이 오면 대조는 실패로 본다")
    void matches_nullValue_returnsFalse() {
        // given
        String digest = CheckoutDigest.of("{}");

        // when & then
        assertFalse(CheckoutDigest.matches(digest, null));
        assertFalse(CheckoutDigest.matches(null, digest));
        assertFalse(CheckoutDigest.matches(null, null));
    }

    @Test
    @DisplayName("빈 입력은 거절한다")
    void of_blankInput_throws() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> CheckoutDigest.of(""));
        assertThrows(IllegalArgumentException.class, () -> CheckoutDigest.of(null));
    }
}
