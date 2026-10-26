package com.copenhagen.core.internalapi.auth;

import java.time.Clock;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.copenhagen.core.agent.jwt.AgentJwtKeySet;
import com.copenhagen.core.agent.jwt.AgentOauthProperties;

/**
 * Core가 발급한 Agent OAuth 토큰(Service JWT·Agent Access Token)을 검증한다.
 *
 * <p>{@code aud}는 MCP canonical URL 그대로 수용한다(설계서 D-C). Core 전용 audience를 새로 만들면
 * 이미 배포된 mcp-server를 다시 배포해야 하고, 내부망 전용 경로인 데다 client·연동을 따로 확인하므로
 * audience 재사용으로 충분하다.
 */
@Service
public class InternalApiTokenVerifier {

    private final AgentJwtKeySet keySet;
    private final AgentOauthProperties properties;
    private final Clock clock;

    public InternalApiTokenVerifier(AgentJwtKeySet keySet, AgentOauthProperties properties, Clock coreClock) {
        this.keySet = keySet;
        this.properties = properties;
        this.clock = coreClock;
    }

    public Optional<VerifiedInternalToken> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new ECDSAVerifier(keySet.publicKey()))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.issuer().equals(claims.getIssuer())
                    || claims.getAudience() == null
                    || !claims.getAudience().contains(properties.mcpResourceAudience())
                    || claims.getSubject() == null || claims.getSubject().isBlank()) {
                return Optional.empty();
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null || !expiration.toInstant().isAfter(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedInternalToken(
                    claims.getSubject(),
                    scopes(claims.getStringClaim("scope")),
                    claims.getStringClaim("connection_id")));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Set<String> scopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(scope.trim().split("\s+")));
    }
}
