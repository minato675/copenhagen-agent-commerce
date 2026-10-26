package com.copenhagen.core.auth.jwt;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Core가 발급한 Access Token을 검증한다. 보호 라우트는 아직 없다(순서 10부터 실사용). */
@Service
public class CoreJwtVerifier {

    private final CoreAuthJwtKeySet keySet;
    private final Clock clock;

    public CoreJwtVerifier(CoreAuthJwtKeySet keySet, Clock clock) {
        this.keySet = keySet;
        this.clock = clock;
    }

    public Optional<UUID> verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT token = SignedJWT.parse(bearerToken);
            JWSHeader header = token.getHeader();
            if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())
                    || !keySet.signingKeyId().equals(header.getKeyID())) {
                return Optional.empty();
            }

            JWSVerifier verifier = new ECDSAVerifier(keySet.publicKey());
            if (!token.verify(verifier)) {
                return Optional.empty();
            }

            JWTClaimsSet claims = token.getJWTClaimsSet();
            if (!hasValidClaims(claims)) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private boolean hasValidClaims(JWTClaimsSet claims) {
        if (!CoreJwtIssuer.ISSUER.equals(claims.getIssuer())) {
            return false;
        }
        if (!claims.getAudience().contains(CoreJwtIssuer.AUDIENCE)) {
            return false;
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            return false;
        }
        Date expiration = claims.getExpirationTime();
        Instant now = clock.instant();
        return expiration != null && expiration.toInstant().isAfter(now);
    }
}
