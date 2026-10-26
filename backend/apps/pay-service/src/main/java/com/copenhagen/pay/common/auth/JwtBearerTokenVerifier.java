package com.copenhagen.pay.common.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class JwtBearerTokenVerifier implements PayTokenVerifier {

    private static final Set<JWSAlgorithm> SUPPORTED_ALGORITHMS = Set.of(
            JWSAlgorithm.RS256,
            JWSAlgorithm.RS384,
            JWSAlgorithm.RS512,
            JWSAlgorithm.ES256,
            JWSAlgorithm.ES384,
            JWSAlgorithm.ES512);

    private final PayJwtProperties properties;
    private final Clock clock;
    private final JWKSet jwkSet;

    public JwtBearerTokenVerifier(PayJwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.jwkSet = loadJwkSet(properties);
    }

    @Override
    public Optional<PayPrincipal> verify(String bearerToken) {
        if (!enabled() || bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }

        try {
            SignedJWT token = SignedJWT.parse(bearerToken);
            JWSHeader header = token.getHeader();
            JWK key = findVerificationKey(header);
            if (!isSupported(header, key)) {
                return Optional.empty();
            }

            JWSVerifier verifier = createVerifier(key);
            if (!token.verify(verifier)) {
                return Optional.empty();
            }

            JWTClaimsSet claims = token.getJWTClaimsSet();
            if (!hasValidClaims(claims)) {
                return Optional.empty();
            }

            return Optional.of(new PayPrincipal(PayPrincipal.Type.USER, claims.getSubject()));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    private JWKSet loadJwkSet(PayJwtProperties jwtProperties) {
        if (!jwtProperties.enabled()) {
            return new JWKSet(java.util.List.of());
        }
        if (jwtProperties.issuer().isBlank()
                || jwtProperties.audience().isBlank()
                || jwtProperties.jwkSetJson().isBlank()) {
            throw new IllegalStateException(
                    "JWT verification requires issuer, audience, and JWK set JSON");
        }
        try {
            return JWKSet.parse(jwtProperties.jwkSetJson());
        } catch (ParseException exception) {
            throw new IllegalStateException("JWT JWK set JSON is invalid", exception);
        }
    }

    private JWK findVerificationKey(JWSHeader header) {
        String keyId = header.getKeyID();
        if (keyId == null || keyId.isBlank()) {
            return null;
        }
        return jwkSet.getKeyByKeyId(keyId);
    }

    private boolean isSupported(JWSHeader header, JWK key) {
        if (key == null || !SUPPORTED_ALGORITHMS.contains(header.getAlgorithm())) {
            return false;
        }
        if (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) {
            return false;
        }
        return key.getAlgorithm() == null || key.getAlgorithm().equals(header.getAlgorithm());
    }

    private JWSVerifier createVerifier(JWK key) throws Exception {
        if (key instanceof ECKey ecKey) {
            return new ECDSAVerifier(ecKey.toECPublicKey());
        }
        if (key instanceof RSAKey rsaKey) {
            return new RSASSAVerifier(rsaKey.toRSAPublicKey());
        }
        throw new IllegalArgumentException("JWT signing key must be an EC or RSA public key");
    }

    private boolean hasValidClaims(JWTClaimsSet claims) {
        if (!properties.issuer().equals(claims.getIssuer())) {
            return false;
        }
        if (!claims.getAudience().contains(properties.audience())) {
            return false;
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            return false;
        }

        Instant now = clock.instant();
        Instant skewedNow = now.plusSeconds(properties.clockSkewSeconds());
        Date expiration = claims.getExpirationTime();
        if (expiration == null
                || expiration.toInstant().isBefore(now.minusSeconds(properties.clockSkewSeconds()))) {
            return false;
        }

        Date notBefore = claims.getNotBeforeTime();
        return notBefore == null || !notBefore.toInstant().isAfter(skewedNow);
    }
}
