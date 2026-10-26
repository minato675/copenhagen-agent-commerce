package com.copenhagen.core.agent.token;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.copenhagen.core.agent.client.OauthClient;

/**
 * RFC 7523 {@code private_key_jwt} client assertion 검증. mcp-server {@code CoreServiceTokenProvider}가
 * 만드는 assertion(RS256 ≥2048bit 또는 ES256 P-256, {@code iss=sub=client_id}, {@code aud}=token endpoint,
 * {@code jti})을 그대로 반영한다.
 */
@Component
public class ClientAssertionValidator {

    private final OauthClientAssertionJtiRepository jtiRepository;
    private final Clock clock;

    public ClientAssertionValidator(OauthClientAssertionJtiRepository jtiRepository, Clock clock) {
        this.jtiRepository = jtiRepository;
        this.clock = clock;
    }

    /** 검증에 실패하면 {@link InvalidClientAssertionException}을 던진다. */
    public void validate(OauthClient client, String assertion, String expectedAudience) {
        if (client.clientJwks() == null || client.clientJwks().isBlank()) {
            throw new InvalidClientAssertionException("client has no registered JWKS");
        }
        SignedJWT token = parse(assertion);
        JWK key = resolveKey(client, token);
        verifySignature(token, key);

        JWTClaimsSet claims = claims(token);
        String issuer = claims.getIssuer();
        String subject = claims.getSubject();
        if (issuer == null || !issuer.equals(client.clientId()) || subject == null || !subject.equals(issuer)) {
            throw new InvalidClientAssertionException("iss/sub must equal client_id");
        }
        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(expectedAudience)) {
            throw new InvalidClientAssertionException("aud must be the token endpoint");
        }
        Date expirationTime = claims.getExpirationTime();
        Instant now = clock.instant();
        if (expirationTime == null || !expirationTime.toInstant().isAfter(now)) {
            throw new InvalidClientAssertionException("assertion expired");
        }
        String jti = claims.getJWTID();
        if (jti == null || jti.isBlank()) {
            throw new InvalidClientAssertionException("jti is required");
        }
        if (!jtiRepository.tryConsume(client.id(), jti, expirationTime.toInstant())) {
            throw new InvalidClientAssertionException("assertion already used");
        }
    }

    private static SignedJWT parse(String assertion) {
        try {
            return SignedJWT.parse(assertion);
        } catch (ParseException exception) {
            throw new InvalidClientAssertionException("malformed assertion");
        }
    }

    private static JWTClaimsSet claims(SignedJWT token) {
        try {
            return token.getJWTClaimsSet();
        } catch (ParseException exception) {
            throw new InvalidClientAssertionException("malformed claims");
        }
    }

    private JWK resolveKey(OauthClient client, SignedJWT token) {
        String keyId = token.getHeader().getKeyID();
        if (keyId == null) {
            throw new InvalidClientAssertionException("kid is required");
        }
        JWKSet jwkSet;
        try {
            jwkSet = JWKSet.parse(client.clientJwks());
        } catch (ParseException exception) {
            throw new InvalidClientAssertionException("client_jwks is malformed");
        }
        JWK key = jwkSet.getKeyByKeyId(keyId);
        if (key == null || key.isPrivate()) {
            throw new InvalidClientAssertionException("unknown key id");
        }
        return key;
    }

    private static void verifySignature(SignedJWT token, JWK key) {
        try {
            if (JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm()) && key instanceof RSAKey rsa) {
                if (rsa.size() < 2048 || !token.verify(new RSASSAVerifier(rsa))) {
                    throw new InvalidClientAssertionException("invalid RSA signature");
                }
                return;
            }
            if (JWSAlgorithm.ES256.equals(token.getHeader().getAlgorithm()) && key instanceof ECKey ec) {
                if (!Curve.P_256.equals(ec.getCurve()) || !token.verify(new ECDSAVerifier(ec))) {
                    throw new InvalidClientAssertionException("invalid EC signature");
                }
                return;
            }
            throw new InvalidClientAssertionException("unsupported algorithm");
        } catch (JOSEException exception) {
            throw new InvalidClientAssertionException("signature verification failed");
        }
    }
}
