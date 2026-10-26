package com.copenhagen.merchantmock.security;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JCS 정규화 테스트 벡터(DESIGN.md 6.1절).
 *
 * <p>여기가 흔들리면 서명 검증이 간헐적으로 실패한다. 라이브러리를 바꾸거나 버전을 올릴 때
 * 이 파일이 먼저 깨져야 한다.
 */
class CanonicalJsonTest {

    @Test
    @DisplayName("키 순서만 다른 두 JSON 은 같은 바이트가 된다")
    void canonicalize_sameKeysDifferentOrder_producesSameBytes() {
        // given
        String first = "{\"b\":2,\"a\":1}";
        String second = "{\"a\":1,\"b\":2}";

        // when
        String canonicalFirst = CanonicalJson.canonicalize(first);
        String canonicalSecond = CanonicalJson.canonicalize(second);

        // then
        assertEquals(canonicalSecond, canonicalFirst);
        assertEquals("{\"a\":1,\"b\":2}", canonicalFirst);
    }

    @Test
    @DisplayName("공백과 줄바꿈을 없앤다")
    void canonicalize_withWhitespace_removesIt() {
        // given
        String spaced = "{\n  \"id\" : \"checkout-1\" ,\n  \"amount\" : 89000\n}";

        // when
        String canonical = CanonicalJson.canonicalize(spaced);

        // then
        assertEquals("{\"amount\":89000,\"id\":\"checkout-1\"}", canonical);
    }

    @Test
    @DisplayName("중첩 객체의 키도 정렬하고 배열 순서는 그대로 둔다")
    void canonicalize_nestedObjectAndArray_sortsKeysKeepsArrayOrder() {
        // given
        String json = "{\"lines\":[{\"q\":2,\"id\":\"line-2\"},{\"q\":1,\"id\":\"line-1\"}]}";

        // when
        String canonical = CanonicalJson.canonicalize(json);

        // then
        // 배열은 순서가 의미를 가지므로 정렬하지 않는다. 객체 키만 정렬된다.
        assertEquals("{\"lines\":[{\"id\":\"line-2\",\"q\":2},{\"id\":\"line-1\",\"q\":1}]}", canonical);
    }

    @Test
    @DisplayName("소수점이 붙은 정수 금액은 정수 표기로 고정된다")
    void canonicalize_integralAmountWrittenAsDecimal_normalizesToInteger() {
        // given
        // 금액은 minor unit 정수다(불변 조건 11). 어디선가 double 로 새어 들어와도
        // 정규화가 89000.0 을 89000 으로 고정한다. 그래도 double 을 쓰지 않는 것이 규칙이다.
        String json = "{\"amount\":89000.0}";

        // when
        String canonical = CanonicalJson.canonicalize(json);

        // then
        assertEquals("{\"amount\":89000}", canonical);
    }

    @Test
    @DisplayName("키는 UTF-16 코드유닛 순으로 정렬한다. 대문자가 소문자보다 앞이다")
    void canonicalize_mixedCaseKeys_sortsByCodeUnit() {
        // given
        String json = "{\"a\":1,\"B\":2,\"A\":3,\"b\":4}";

        // when
        String canonical = CanonicalJson.canonicalize(json);

        // then
        assertEquals("{\"A\":3,\"B\":2,\"a\":1,\"b\":4}", canonical);
    }

    @Test
    @DisplayName("정규화본을 다시 정규화해도 바이트가 그대로다")
    void canonicalize_alreadyCanonical_isIdempotent() {
        // given
        // 불변 조건 5 는 검증 시 재정규화를 금지한다. 금지의 이유는 이 함수가 흔들려서가 아니라
        // 객체에서 문자열을 다시 만드는 과정이 흔들리기 때문이다. 문자열을 다시 넣는 것은 안전하다.
        String canonical = CanonicalJson.canonicalize("{\"b\":2,\"a\":1}");

        // when
        String again = CanonicalJson.canonicalize(canonical);

        // then
        assertEquals(canonical, again);
    }

    @Test
    @DisplayName("빈 객체도 정규화된다")
    void canonicalize_emptyObject_returnsEmptyObject() {
        // given & when
        String canonical = CanonicalJson.canonicalize("{}");

        // then
        assertEquals("{}", canonical);
    }

    @Test
    @DisplayName("JSON 이 아니면 거절한다")
    void canonicalize_notJson_throws() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize("checkout-1"));
    }

    @Test
    @DisplayName("빈 입력은 거절한다")
    void canonicalize_blankInput_throws() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize("  "));
    }

    @Test
    @DisplayName("정규화 바이트는 UTF-8 이다")
    void canonicalBytes_koreanValue_encodesAsUtf8() {
        // given
        String json = "{\"title\":\"러닝화\"}";

        // when
        byte[] bytes = CanonicalJson.canonicalBytes(json);

        // then
        assertArrayEquals(CanonicalJson.canonicalize(json).getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    @DisplayName("정규화는 null 을 지우지 않는다. 키 생략은 응답을 만들 때 지켜야 한다")
    void canonicalize_nullValue_isKeptNotDropped() {
        // given
        // 17절 규칙은 "값이 없으면 키를 생략한다. null 금지" 다.
        // 정규화가 그 규칙을 강제해 주지 않는다는 것을 못 박아 둔다.
        String withNull = "{\"discount\":null,\"id\":\"checkout-1\"}";
        String omitted = "{\"id\":\"checkout-1\"}";

        // when
        String canonicalWithNull = CanonicalJson.canonicalize(withNull);
        String canonicalOmitted = CanonicalJson.canonicalize(omitted);

        // then
        assertEquals("{\"discount\":null,\"id\":\"checkout-1\"}", canonicalWithNull);
        assertNotEquals(canonicalOmitted, canonicalWithNull);
    }

    @Test
    @DisplayName("RFC 3339 초 단위 offset 표기는 그대로 유지된다")
    void canonicalize_rfc3339Timestamp_isKeptAsIs() {
        // given
        String json = "{\"expires_at\":\"2026-09-15T17:00:00+09:00\"}";

        // when
        String canonical = CanonicalJson.canonicalize(json);

        // then
        // 시각은 JSON 문자열이라 정규화가 손대지 않는다. 표기를 맞추는 것은 값을 만드는 쪽 책임이다.
        assertEquals("{\"expires_at\":\"2026-09-15T17:00:00+09:00\"}", canonical);
    }

    @Test
    @DisplayName("같은 순간이라도 시각 표기가 다르면 다른 바이트가 된다")
    void canonicalize_sameInstantDifferentNotation_producesDifferentBytes() {
        // given
        // 180 에서 실제로 났던 사고다. 같은 응답 안에서 하나는 +09:00 초 단위,
        // 하나는 DB 에서 그대로 나온 UTC 마이크로초였다. 서명이 붙으면 이건 검증 실패가 된다.
        String seoul = "{\"updated_at\":\"2026-09-15T17:00:00+09:00\"}";
        String utcMicros = "{\"updated_at\":\"2026-09-15T08:00:00.000000Z\"}";

        // when
        String canonicalSeoul = CanonicalJson.canonicalize(seoul);
        String canonicalUtc = CanonicalJson.canonicalize(utcMicros);

        // then
        assertNotEquals(canonicalUtc, canonicalSeoul);
    }
}
