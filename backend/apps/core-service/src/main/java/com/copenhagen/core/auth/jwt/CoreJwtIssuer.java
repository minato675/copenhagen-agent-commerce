package com.copenhagen.core.auth.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** 로그인 성공 시 Access Token(JWT)을 발급한다. 정책은 §11.2.2·§11.2.4 확정 사항 그대로다. */
@Service
public class CoreJwtIssuer {

    static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);
    static final String ISSUER = "copenhagen-core";
    static final String AUDIENCE = "pay-service";

    private final CoreAuthJwtKeySet keySet;
    private final Clock clock;

    public CoreJwtIssuer(CoreAuthJwtKeySet keySet, Clock clock) {
        this.keySet = keySet;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UUID subjectPublicId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ACCESS_TOKEN_TTL);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subjectPublicId.toString())
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keySet.signingKeyId())
                .type(JOSEObjectType.JWT)
                .build();

        SignedJWT signedJwt = new SignedJWT(header, claims);
        try {
            signedJwt.sign(new ECDSASigner(keySet.signingKey()));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Access token signing failed", exception);
        }

        return new IssuedAccessToken(signedJwt.serialize(), ACCESS_TOKEN_TTL.toSeconds());
    }
}
