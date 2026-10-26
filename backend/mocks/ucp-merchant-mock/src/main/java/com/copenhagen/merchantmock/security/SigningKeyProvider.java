package com.copenhagen.merchantmock.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ES256 서명 키를 로딩하고 공개키를 Profile 에 넘긴다(DESIGN.md 3.5절).
 *
 * <p>공개키와 개인키를 갈라서 다룬다. 공개키는 노출돼도 되므로 application.yml 에 두고,
 * 개인키는 환경변수로 주입한 파일 경로나 PEM 문자열에서 읽는다(설계서 18.5).
 *
 * <p><b>개인키가 없으면 기동 때 임시 키 쌍을 만든다(175, 2026-09-17).</b> {@code ready_for_complete} 가 곧
 * 서명이라 개인키 없이는 체크아웃이 하나도 완료로 갈 수 없다. 설정된 공개 JWK 는 개인키가 같이 있을 때만
 * 쓴다 — 서명할 수 없는 공개키를 Profile 에 실으면 거짓말이다. 임시 키는 재기동하면 바뀌므로 이전 기동의
 * JWS 는 검증되지 않는다. 체크아웃 TTL 이 5분이라 감수한다. Pay 의 {@code MandateKeySet} 과 같은 관행이다.
 */
@Component
public class SigningKeyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeyProvider.class);
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";
    private static final String PAIR_CHECK_PAYLOAD = "merchant-mock-keypair-check";

    private final ECKey publicJwk;
    private final ECKey signingKey;
    private final boolean ephemeral;

    public SigningKeyProvider(MerchantProperties properties) {
        MerchantProperties.Signing signing = properties.signing();
        String pem = readPrivateKeyPem(signing);
        if (pem == null) {
            this.signingKey = generateEphemeral(signing.kid());
            this.publicJwk = this.signingKey.toPublicJWK();
            this.ephemeral = true;
            LOG.warn("서명 개인키 미설정. 임시 키 쌍을 만들었다. 재기동하면 키가 바뀌어 이전 기동의 서명은 검증되지 않는다. "
                    + "배포는 MERCHANT_SIGNING_PRIVATE_KEY_PEM 을 넣는다. kid={}", signing.kid());
            return;
        }
        ECKey configured = parsePublicJwk(signing);
        if (configured == null) {
            throw new IllegalStateException("개인키만 설정됐다. merchant.signing.public-key-jwk 도 함께 채워야 한다");
        }
        this.signingKey = buildSigningKey(pem, configured);
        this.publicJwk = configured;
        this.ephemeral = false;
    }

    /** Profile 의 keys[] 에 실을 공개키. 임시 키든 설정 키든 항상 하나 있다. */
    public Optional<Map<String, Object>> publicJwk() {
        return Optional.of(new LinkedHashMap<>(publicJwk.toPublicJWK().toJSONObject()));
    }

    /** 서명에 쓸 개인키. Merchant Authorization(6.4절)과 웹훅 서명(6.6절)이 부른다. */
    public ECKey requireSigningKey() {
        return signingKey;
    }

    /** 항상 true 다. 개인키가 없으면 임시 키를 만들기 때문이다. 부르는 쪽의 분기는 남겨 둔다. */
    public boolean canSign() {
        return true;
    }

    /** 기동 때 만든 임시 키인지. 로그와 테스트가 본다. */
    public boolean isEphemeral() {
        return ephemeral;
    }

    private static ECKey generateEphemeral(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("임시 서명 키를 만들 수 없다", exception);
        }
    }

    private static ECKey parsePublicJwk(MerchantProperties.Signing signing) {
        String json = signing.publicKeyJwk();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ECKey parsed = ECKey.parse(json);
            if (parsed.getKeyID() == null) {
                parsed = new ECKey.Builder(parsed).keyID(signing.kid()).build();
            }
            LOG.info("공개키 JWK 로딩 완료. kid={} crv={}", parsed.getKeyID(), parsed.getCurve());
            return parsed;
        } catch (ParseException exception) {
            throw new IllegalStateException("merchant.signing.public-key-jwk 를 EC JWK 로 읽을 수 없다", exception);
        }
    }

    private static ECKey buildSigningKey(String pem, ECKey publicJwk) {
        ECPrivateKey privateKey = decodePkcs8(pem);
        ECKey key = new ECKey.Builder(publicJwk).privateKey(privateKey).build();
        verifyKeyPair(key);
        LOG.info("서명 개인키 로딩 완료. kid={}", key.getKeyID());
        return key;
    }

    private static String readPrivateKeyPem(MerchantProperties.Signing signing) {
        String inline = signing.privateKeyPem();
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String location = signing.privateKeyLocation();
        if (location == null || location.isBlank()) {
            return null;
        }
        Path path = Path.of(location);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("서명 개인키 파일을 읽을 수 없다: " + location);
        }
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("서명 개인키 파일 읽기에 실패했다: " + location, exception);
        }
    }

    /** PKCS#8 PEM 만 받는다. openssl pkcs8 -topk8 -nocrypt 로 변환한 파일이다. */
    private static ECPrivateKey decodePkcs8(String pem) {
        String body = pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new IllegalStateException("개인키 PEM 이 비어 있다. PKCS#8 형식인지 확인한다");
        }
        try {
            byte[] der = Base64.getDecoder().decode(body);
            KeyFactory factory = KeyFactory.getInstance("EC");
            return (ECPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException exception) {
            // base64 깨짐, PKCS#8 이 아닌 DER, EC 가 아닌 키 순서로 각각 걸린다.
            throw new IllegalStateException(
                    "개인키를 PKCS#8 EC 키로 읽을 수 없다. BEGIN PRIVATE KEY 헤더가 필요하다", exception);
        }
    }

    /** 공개키와 개인키가 같은 쌍인지 서명 왕복으로 확인한다. 틀린 키를 붙이면 기동에서 잡는다. */
    private static void verifyKeyPair(ECKey key) {
        try {
            JWSObject probe = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
                    new Payload(PAIR_CHECK_PAYLOAD));
            probe.sign(new ECDSASigner(key.toECPrivateKey(), Curve.P_256));
            if (!probe.verify(new ECDSAVerifier(key.toECPublicKey()))) {
                throw new IllegalStateException("공개키와 개인키가 같은 쌍이 아니다");
            }
        } catch (JOSEException exception) {
            throw new IllegalStateException("서명 키 쌍 검증에 실패했다", exception);
        }
    }
}
