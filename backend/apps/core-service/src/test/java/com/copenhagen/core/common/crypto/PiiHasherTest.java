package com.copenhagen.core.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiHasherTest {

    private static final String PEPPER = "test-only-pepper-value-at-least-32-chars";
    private static final String OTHER_PEPPER = "another-test-only-pepper-value-32-chars";

    private final PiiHasher hasher = new PiiHasher(PEPPER);

    @Test
    @DisplayName("해시는 소문자 hex 64자다")
    void hashEmail_returnsLowercaseHex64() {
        String hash = hasher.hashEmail("user@example.com");

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "소문자 hex 64자여야 한다: " + hash);
    }

    @Test
    @DisplayName("같은 이메일은 항상 같은 해시가 된다")
    void hashEmail_sameInput_isDeterministic() {
        assertEquals(hasher.hashEmail("user@example.com"), hasher.hashEmail("user@example.com"));
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백만 다른 이메일은 같은 해시가 된다")
    void hashEmail_differentCaseOrWhitespace_producesSameHash() {
        String expected = hasher.hashEmail("user@example.com");

        assertEquals(expected, hasher.hashEmail("USER@EXAMPLE.COM"));
        assertEquals(expected, hasher.hashEmail("  User@Example.Com  "));
    }

    @Test
    @DisplayName("다른 이메일은 다른 해시가 된다")
    void hashEmail_differentInput_producesDifferentHash() {
        assertNotEquals(hasher.hashEmail("user1@example.com"), hasher.hashEmail("user2@example.com"));
    }

    @Test
    @DisplayName("pepper가 다르면 같은 평문도 다른 해시가 된다")
    void hashEmail_differentPepper_producesDifferentHash() {
        PiiHasher otherHasher = new PiiHasher(OTHER_PEPPER);

        assertNotEquals(hasher.hashEmail("user@example.com"), otherHasher.hashEmail("user@example.com"));
    }

    @Test
    @DisplayName("전화번호는 구분자를 무시하고 같은 해시가 된다")
    void hashPhone_differentSeparators_producesSameHash() {
        String expected = hasher.hashPhone("+821012345678");

        assertEquals(expected, hasher.hashPhone("+82 10 1234 5678"));
        assertEquals(expected, hasher.hashPhone("+82-10-1234-5678"));
    }

    @Test
    @DisplayName("국가번호 유무는 다른 번호로 취급한다")
    void hashPhone_withAndWithoutCountryCode_producesDifferentHash() {
        assertNotEquals(hasher.hashPhone("+821012345678"), hasher.hashPhone("821012345678"));
    }

    @Test
    @DisplayName("null과 빈 값은 해시하지 않고 null을 반환한다")
    void hash_nullOrBlank_returnsNull() {
        assertNull(hasher.hashEmail(null));
        assertNull(hasher.hashEmail("   "));
        assertNull(hasher.hashPhone(null));
        assertNull(hasher.hashPhone("---"));
    }

    @Test
    @DisplayName("짧거나 비어 있는 pepper는 기동 시점에 거부한다")
    void constructor_weakPepper_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> new PiiHasher(null));
        assertThrows(IllegalStateException.class, () -> new PiiHasher("  "));
        assertThrows(IllegalStateException.class, () -> new PiiHasher("too-short-pepper"));
    }
}
