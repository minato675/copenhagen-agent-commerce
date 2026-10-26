package com.copenhagen.core.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutDigestsTest {

    private static final String JWS = "eyJhbGciOiJFUzI1NiJ9.eyJjaGVja291dElkIjoiYyJ9.c2ln";

    @Test
    @DisplayName("digest는 JWS 문자열의 SHA-256을 hex 64자로 돌려준다")
    void of_returnsHex64() {
        String digest = CheckoutDigests.of(JWS);

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Pay가 쓰는 외부 표현(sha256:base64url)과 같은 바이트를 가리킨다")
    void of_matchesPayExternalHash() throws Exception {
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(JWS.getBytes(StandardCharsets.UTF_8));
        String externalHash = "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(expected);

        // Pay CheckoutHashCodec.toInternalDigest 와 같은 변환이다.
        String internalFromExternal = HexFormat.of()
                .formatHex(Base64.getUrlDecoder().decode(externalHash.substring("sha256:".length())));

        assertThat(CheckoutDigests.of(JWS)).isEqualTo(internalFromExternal);
    }

    @Test
    @DisplayName("서명이 비어 있으면 digest를 만들지 않는다")
    void of_blankInput_throws() {
        assertThatThrownBy(() -> CheckoutDigests.of(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CheckoutDigests.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
