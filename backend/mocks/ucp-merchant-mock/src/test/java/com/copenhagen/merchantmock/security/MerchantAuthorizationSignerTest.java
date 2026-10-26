package com.copenhagen.merchantmock.security;

import java.time.Instant;
import java.util.Base64;

import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.copenhagen.merchantmock.security.MerchantAuthorizationSigner.Claims;
import com.copenhagen.merchantmock.support.TestSigningKeys;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merchant Authorization 서명(Jira 174, DESIGN.md 6.4절).
 *
 * <p>Jira 174 완료 조건 셋이 여기 있다. Nimbus ECDSAVerifier 로 풀린다, 한 글자만 바꿔도 검증이 실패한다,
 * 같은 입력을 두 번 서명하면 값이 달라진다(그래서 첫 서명만 저장한다).
 */
class MerchantAuthorizationSignerTest {

    private static final Instant EXP = Instant.parse("2026-09-17T04:05:00Z");

    private ECKey key;
    private ScenarioState scenario;
    private MerchantAuthorizationSigner signer;

    @BeforeEach
    void setUp() {
        key = TestSigningKeys.generate();
        scenario = new ScenarioState(Scenario.NORMAL);
        signer = new MerchantAuthorizationSigner(TestSigningKeys.providerSigningWith(key), scenario);
    }

    private static Claims claims() {
        return new Claims("merchant-a", "checkout-1", "pr-1", 92000L, "KRW", EXP);
    }

    @Test
    @DisplayName("Compact JWS 세 세그먼트이고 헤더는 alg·kid 둘이다")
    void sign_claims_producesCompactJwsWithEs256AndKid() throws Exception {
        // when
        String jws = signer.sign(claims());

        // then
        assertEquals(3, jws.split("\\.").length);
        SignedJWT parsed = SignedJWT.parse(jws);
        assertEquals("ES256", parsed.getHeader().getAlgorithm().getName());
        assertEquals(TestSigningKeys.KID, parsed.getHeader().getKeyID());
        assertEquals(2, parsed.getHeader().toJSONObject().size(), "헤더는 alg 와 kid 만");
    }

    @Test
    @DisplayName("Pay 가 요구하는 claim 여섯이 그 이름 그대로 실린다")
    void sign_claims_carriesSixClaimsPayVerifierReads() throws Exception {
        // when
        JWTClaimsSet set = SignedJWT.parse(signer.sign(claims())).getJWTClaimsSet();

        // then
        assertEquals("merchant-a", set.getStringClaim("merchantId"));
        assertEquals("checkout-1", set.getStringClaim("checkoutId"));
        assertEquals("pr-1", set.getStringClaim("purchaseRequestId"));
        assertEquals(92000L, set.getLongClaim("amountMinor"));
        assertEquals("KRW", set.getStringClaim("currency"));
        assertEquals(EXP, set.getExpirationTime().toInstant());
        assertEquals(6, set.getClaims().size(), "checkoutHash claim 은 넣지 않는다(6.2절)");
    }

    @Test
    @DisplayName("내 공개키로 검증된다. Core 와 Pay 가 Profile keys[] 로 하는 일이다")
    void sign_claims_verifiesWithPublicKey() throws Exception {
        // when
        String jws = signer.sign(claims());

        // then
        assertTrue(SignedJWT.parse(jws).verify(new ECDSAVerifier(key.toECPublicKey())));
        assertTrue(MerchantAuthorizationSigner.verify(jws, key.toPublicJWK()));
    }

    @Test
    @DisplayName("서명 바이트는 R‖S 64바이트다. DER 이 아니다")
    void sign_claims_signatureIsConcatenatedRs() {
        // when
        String signature = signer.sign(claims()).split("\\.")[2];

        // then
        assertEquals(64, Base64.getUrlDecoder().decode(signature).length);
    }

    @Test
    @DisplayName("한 글자만 바꿔도 검증이 실패한다")
    void verify_tamperedSignature_fails() {
        // given
        String jws = signer.sign(claims());

        // when
        String tampered = MerchantAuthorizationSigner.tamper(jws);

        // then
        assertNotEquals(jws, tampered);
        assertFalse(MerchantAuthorizationSigner.verify(tampered, key.toPublicJWK()));
    }

    @Test
    @DisplayName("다른 키의 공개키로는 검증되지 않는다")
    void verify_otherPublicKey_fails() {
        // when
        String jws = signer.sign(claims());

        // then
        assertFalse(MerchantAuthorizationSigner.verify(jws, TestSigningKeys.generate().toPublicJWK()));
    }

    @Test
    @DisplayName("같은 입력을 두 번 서명하면 값이 다르다. 그래서 첫 서명만 저장한다(불변 조건 6번)")
    void sign_sameClaimsTwice_producesDifferentStrings() {
        // when
        String first = signer.sign(claims());
        String second = signer.sign(claims());

        // then
        assertNotEquals(first, second);
        assertTrue(MerchantAuthorizationSigner.verify(first, key.toPublicJWK()));
        assertTrue(MerchantAuthorizationSigner.verify(second, key.toPublicJWK()));
    }

    @Test
    @DisplayName("시나리오 INVALID_MERCHANT_SIGNATURE 면 파싱은 되고 검증만 실패하는 서명이 나간다")
    void sign_invalidSignatureScenario_returnsParsableButUnverifiable() throws Exception {
        // given
        scenario.set(Scenario.INVALID_MERCHANT_SIGNATURE);

        // when
        String jws = signer.sign(claims());

        // then
        SignedJWT parsed = SignedJWT.parse(jws);
        assertEquals("checkout-1", parsed.getJWTClaimsSet().getStringClaim("checkoutId"), "claim 은 그대로다");
        assertFalse(MerchantAuthorizationSigner.verify(jws, key.toPublicJWK()));
    }

    @Test
    @DisplayName("필수 claim 이 비어 있으면 서명하지 않는다")
    void claims_blankRequired_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Claims("merchant-a", "checkout-1", "", 92000L, "KRW", EXP));
        assertThrows(IllegalArgumentException.class,
                () -> new Claims("merchant-a", "checkout-1", "pr-1", -1L, "KRW", EXP));
        assertThrows(IllegalArgumentException.class,
                () -> new Claims("merchant-a", "checkout-1", "pr-1", 92000L, "KRW", null));
    }
}
