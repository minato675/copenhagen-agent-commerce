package com.copenhagen.core.agent.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Agent Access Token(사용자 위임)과 Service JWT(client_credentials)를 발급한다. 두 토큰 모두
 * mcp-server {@code JwksMcpTokenVerifier}가 요구하는 형태(TTL 300초 고정, {@code scope} 공백구분
 * 문자열 클레임)를 따른다. {@code connection_id}는 사용자 위임 토큰에만 실린다 — Service JWT는
 * {@code JwksMcpTokenVerifier}의 검증 대상이 아니라 그 클레임이 없어도 문제되지 않는다(설계서 §4.1).
 */
@Service
public class AgentJwtIssuer {

    static final Duration ACCESS_TOKEN_TTL = Duration.ofSeconds(300);

    private final AgentJwtKeySet keySet;
    private final AgentOauthProperties properties;
    private final Clock clock;

    public AgentJwtIssuer(AgentJwtKeySet keySet, AgentOauthProperties properties, Clock clock) {
        this.keySet = keySet;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAgentToken issueAgentAccessToken(UUID userPublicId, String connectionPublicId, Set<String> scopes) {
        JWTClaimsSet.Builder claims = baseClaims(userPublicId.toString(), scopes)
                .claim("connection_id", connectionPublicId);
        return sign(claims);
    }

    public IssuedAgentToken issueServiceToken(String clientId, Set<String> scopes) {
        return sign(baseClaims(clientId, scopes));
    }

    private JWTClaimsSet.Builder baseClaims(String subject, Set<String> scopes) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ACCESS_TOKEN_TTL);
        return new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(properties.mcpResourceAudience())
                .subject(subject)
                .claim("scope", String.join(" ", scopes))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString());
    }

    private IssuedAgentToken sign(JWTClaimsSet.Builder claimsBuilder) {
        JWTClaimsSet claims = claimsBuilder.build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keySet.signingKeyId())
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT signedJwt = new SignedJWT(header, claims);
        try {
            signedJwt.sign(new ECDSASigner(keySet.signingKey()));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Agent token signing failed", exception);
        }
        return new IssuedAgentToken(signedJwt.serialize(), ACCESS_TOKEN_TTL.toSeconds());
    }
}
