package com.copenhagen.merchantmock.security;

import java.nio.charset.StandardCharsets;

import com.copenhagen.merchantmock.security.HttpMessageSignature.Components;
import com.copenhagen.merchantmock.security.HttpMessageSignature.Signed;
import com.copenhagen.merchantmock.support.TestSigningKeys;
import com.nimbusds.jose.jwk.ECKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 9421 서명·검증과 RFC 9530 다이제스트(DESIGN.md 6.6절).
 *
 * <p>다이제스트 벡터는 RFC 9530 부록의 것이다. 나머지는 왕복이다. ECDSA 는 비결정적이라 서명 값 자체를
 * 고정할 수 없고, 같은 키의 공개 절반으로 검증되는지와 어긋난 입력이 거절되는지를 본다.
 */
class HttpMessageSignatureTest {

    private static final ECKey KEY = TestSigningKeys.generate();
    private static final long CREATED = 1789520400L;
    private static final Components COMPONENTS = new Components(
            "POST", "core-service:8080", "/webhooks/ucp/orders",
            "sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:", "application/json");

    @Test
    @DisplayName("Content-Digest 는 RFC 9530 벡터와 같다")
    void contentDigest_rfc9530Vector_matches() {
        // given
        byte[] body = "{\"hello\": \"world\"}".getBytes(StandardCharsets.UTF_8);

        // when & then
        assertEquals("sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:",
                HttpMessageSignature.contentDigest(body));
    }

    @Test
    @DisplayName("Signature-Input 은 컴포넌트 다섯과 created·keyid 만 담는다")
    void sign_anyInput_formatsSignatureInputPerUcp() {
        // when
        Signed signed = HttpMessageSignature.sign(COMPONENTS, CREATED, KEY);

        // then
        assertEquals("sig1=(\"@method\" \"@authority\" \"@path\" \"content-digest\" \"content-type\")"
                + ";created=1789520400;keyid=\"" + TestSigningKeys.KID + "\"", signed.signatureInput());
        assertTrue(signed.signature().startsWith("sig1=:") && signed.signature().endsWith(":"));
        assertFalse(signed.signatureInput().contains("alg="), "UCP 는 alg 파라미터를 쓰지 않는다");
        assertEquals(CREATED, HttpMessageSignature.createdOf(signed.signatureInput()));
    }

    @Test
    @DisplayName("서명 바이트는 R‖S 64바이트다")
    void sign_anyInput_producesConcatSignature() {
        // when
        Signed signed = HttpMessageSignature.sign(COMPONENTS, CREATED, KEY);
        String base64 = signed.signature().substring("sig1=:".length(), signed.signature().length() - 1);

        // then
        assertEquals(64, java.util.Base64.getDecoder().decode(base64).length);
    }

    @Test
    @DisplayName("서명 베이스는 컴포넌트 한 줄씩 뒤에 @signature-params 가 붙는다")
    void signatureBase_anyInput_followsRfc9421Layout() {
        // when
        String base = HttpMessageSignature.signatureBase(COMPONENTS,
                HttpMessageSignature.signatureParams(CREATED, TestSigningKeys.KID));

        // then
        assertEquals("""
                "@method": POST
                "@authority": core-service:8080
                "@path": /webhooks/ucp/orders
                "content-digest": sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:
                "content-type": application/json
                "@signature-params": ("@method" "@authority" "@path" "content-digest" "content-type")\
                ;created=1789520400;keyid="merchant-a-es256-test\"""", base);
    }

    @Test
    @DisplayName("같은 키의 공개 절반으로 검증된다")
    void verify_signedWithMatchingKey_returnsTrue() {
        // given
        Signed signed = HttpMessageSignature.sign(COMPONENTS, CREATED, KEY);

        // when & then
        assertTrue(HttpMessageSignature.verify(COMPONENTS, signed.signatureInput(), signed.signature(),
                KEY.toPublicJWK()));
    }

    @Test
    @DisplayName("본문 다이제스트가 바뀌면 검증에 실패한다")
    void verify_tamperedDigest_returnsFalse() {
        // given
        Signed signed = HttpMessageSignature.sign(COMPONENTS, CREATED, KEY);
        Components tampered = new Components(COMPONENTS.method(), COMPONENTS.authority(), COMPONENTS.path(),
                "sha-256=:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=:", COMPONENTS.contentType());

        // when & then
        assertFalse(HttpMessageSignature.verify(tampered, signed.signatureInput(), signed.signature(),
                KEY.toPublicJWK()));
    }

    @Test
    @DisplayName("다른 키나 다른 kid 로는 검증되지 않는다")
    void verify_wrongKey_returnsFalse() {
        // given
        Signed signed = HttpMessageSignature.sign(COMPONENTS, CREATED, KEY);
        ECKey otherKey = TestSigningKeys.generate();
        ECKey renamed = new ECKey.Builder(KEY.toPublicJWK()).keyID("other-kid").build();

        // when & then
        assertFalse(HttpMessageSignature.verify(COMPONENTS, signed.signatureInput(), signed.signature(),
                otherKey.toPublicJWK()));
        assertFalse(HttpMessageSignature.verify(COMPONENTS, signed.signatureInput(), signed.signature(), renamed));
    }

    @Test
    @DisplayName("헤더 형식이 어긋나면 예외 없이 false 다")
    void verify_malformedHeaders_returnsFalse() {
        // when & then
        assertFalse(HttpMessageSignature.verify(COMPONENTS, "sig1=(\"@method\");keyid=\"x\"", "sig1=:abc:",
                KEY.toPublicJWK()));
        assertFalse(HttpMessageSignature.verify(COMPONENTS, null, null, KEY.toPublicJWK()));
        assertEquals(-1, HttpMessageSignature.createdOf("garbage"));
    }
}
