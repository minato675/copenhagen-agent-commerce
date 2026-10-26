package com.copenhagen.merchantmock.security;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Merchant Authorization. 체크아웃 조건에 찍는 도장이다(Jira 174, DESIGN.md 6.4절).
 *
 * <p>서명 대상은 Checkout 본문이 아니라 claim 여섯이다. Pay 의 {@code MerchantCheckoutVerifier} 가
 * {@code SignedJWT.parse()} 로 읽어 요청값과 대조하는 모양 그대로다. 헤더는 {@code alg}·{@code kid} 둘.
 * 서명 바이트는 Nimbus 가 R‖S 64바이트로 낸다. JCA 의 DER 이 아니다.
 *
 * <p>ECDSA 는 결정적이지 않다. 같은 claim 을 두 번 서명하면 다른 문자열이 나온다. 그래서 발급한 값을 그대로
 * 보관하고 다시 만들지 않는다(불변 조건 6번). 이 클래스는 만들기만 하고 보관은 {@code checkout_sessions} 몫이다.
 *
 * <p>시나리오 {@code INVALID_MERCHANT_SIGNATURE} 를 여기서 읽는다. 정상 서명을 만든 뒤 signature 세그먼트의
 * 첫 글자를 바꿔 돌려준다. 헤더와 claim 은 그대로라 Pay 가 파싱은 하고 검증에서 떨어뜨린다(설계서 23.7).
 */
@Component
public class MerchantAuthorizationSigner {

    static final String CLAIM_MERCHANT_ID = "merchantId";
    static final String CLAIM_CHECKOUT_ID = "checkoutId";
    static final String CLAIM_PURCHASE_REQUEST_ID = "purchaseRequestId";
    static final String CLAIM_AMOUNT_MINOR = "amountMinor";
    static final String CLAIM_CURRENCY = "currency";

    private static final Logger LOG = LoggerFactory.getLogger(MerchantAuthorizationSigner.class);

    private final SigningKeyProvider keys;
    private final ScenarioState scenario;

    public MerchantAuthorizationSigner(SigningKeyProvider keys, ScenarioState scenario) {
        this.keys = keys;
        this.scenario = scenario;
    }

    /** claim 여섯. {@code amountMinor} 는 체크아웃 totals 의 total, {@code expiresAt} 은 체크아웃 expires_at 이다. */
    public record Claims(
            String merchantId,
            String checkoutId,
            String purchaseRequestId,
            long amountMinor,
            String currency,
            Instant expiresAt) {

        public Claims {
            requireText(merchantId, CLAIM_MERCHANT_ID);
            requireText(checkoutId, CLAIM_CHECKOUT_ID);
            requireText(purchaseRequestId, CLAIM_PURCHASE_REQUEST_ID);
            requireText(currency, CLAIM_CURRENCY);
            if (amountMinor < 0) {
                throw new IllegalArgumentException("amountMinor 는 음수일 수 없다: " + amountMinor);
            }
            if (expiresAt == null) {
                throw new IllegalArgumentException("exp 가 비어 있다");
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("서명 claim " + name + " 이 비어 있다");
            }
        }
    }

    /** ES256 Compact JWS 문자열. 이 값을 그대로 저장하고 응답에 싣는다. */
    public String sign(Claims claims) {
        ECKey key = keys.requireSigningKey();
        JWTClaimsSet claimSet = new JWTClaimsSet.Builder()
                .claim(CLAIM_MERCHANT_ID, claims.merchantId())
                .claim(CLAIM_CHECKOUT_ID, claims.checkoutId())
                .claim(CLAIM_PURCHASE_REQUEST_ID, claims.purchaseRequestId())
                .claim(CLAIM_AMOUNT_MINOR, claims.amountMinor())
                .claim(CLAIM_CURRENCY, claims.currency())
                .expirationTime(Date.from(claims.expiresAt()))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claimSet);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Merchant Authorization 서명에 실패했다", exception);
        }
        String compact = jwt.serialize();
        if (scenario.is(Scenario.INVALID_MERCHANT_SIGNATURE)) {
            LOG.warn("시나리오 INVALID_MERCHANT_SIGNATURE. 서명 한 글자를 바꿔 돌려준다. checkoutId={}",
                    claims.checkoutId());
            return tamper(compact);
        }
        return compact;
    }

    /** 검증. 내 테스트가 내 서명을 공개키로 되풀이해 확인하고, Core 가 검증기를 짤 때 참조한다. */
    public static boolean verify(String compactJws, ECKey publicKey) {
        try {
            return SignedJWT.parse(compactJws).verify(new ECDSAVerifier(publicKey.toECPublicKey()));
        } catch (ParseException | JOSEException exception) {
            return false;
        }
    }

    /** 파싱은 되고 검증만 실패하는 문자열. signature 세그먼트의 첫 글자를 다른 base64url 글자로 바꾼다. */
    static String tamper(String compact) {
        int signatureStart = compact.lastIndexOf('.') + 1;
        char first = compact.charAt(signatureStart);
        char replaced = first == 'A' ? 'B' : 'A';
        return compact.substring(0, signatureStart) + replaced + compact.substring(signatureStart + 1);
    }
}
