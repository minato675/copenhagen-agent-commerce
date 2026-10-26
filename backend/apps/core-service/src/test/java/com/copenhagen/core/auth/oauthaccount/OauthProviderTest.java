package com.copenhagen.core.auth.oauthaccount;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OauthProviderTest {

    @Test
    @DisplayName("value()는 저장에 사용하는 대문자 provider 문자열을 반환한다")
    void value_google_returnsUppercaseString() {
        assertEquals("GOOGLE", OauthProvider.GOOGLE.value());
    }

    @Test
    @DisplayName("from()은 저장된 문자열로 provider를 역매핑한다")
    void from_google_returnsGoogleProvider() {
        assertEquals(OauthProvider.GOOGLE, OauthProvider.from("GOOGLE"));
    }

    @Test
    @DisplayName("from()은 대소문자를 구분하지 않는다")
    void from_lowercaseValue_returnsGoogleProvider() {
        assertEquals(OauthProvider.GOOGLE, OauthProvider.from("google"));
    }

    @Test
    @DisplayName("정의되지 않은 provider 문자열은 예외를 던진다")
    void from_unknownValue_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> OauthProvider.from("kakao"));
    }
}
