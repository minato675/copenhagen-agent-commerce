package com.copenhagen.mcp.auth;

import com.copenhagen.mcp.core.LimitedBodySubscriber;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Core 공개키만 신뢰한다. 토큰의 jku/x5u로 키를 조회하지 않는다. */
public final class JwksMcpTokenVerifier implements McpTokenVerifier {
    private final AgentJwtProperties properties;
    private final HttpClient client;
    private final Clock clock;
    private JWKSet keys;
    private Instant refreshedAt = Instant.MIN;

    public JwksMcpTokenVerifier(AgentJwtProperties properties, HttpClient client, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.clock = clock;
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("JWKS redirects must be disabled");
        }
    }

    @Override
    public VerifiedMcpToken verify(String accessToken) {
        try {
            SignedJWT token = SignedJWT.parse(accessToken);
            if (!Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256).contains(token.getHeader().getAlgorithm())
                    || token.getHeader().getKeyID() == null || token.getHeader().getCriticalParams() != null) {
                throw invalid();
            }
            JWK key = key(token.getHeader().getKeyID());
            if (key == null || key.isPrivate()
                    || (key.getKeyUse() != null && !com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(key.getKeyUse()))
                    || (key.getKeyOperations() != null
                            && !key.getKeyOperations().contains(com.nimbusds.jose.jwk.KeyOperation.VERIFY))
                    || (key.getAlgorithm() != null && !token.getHeader().getAlgorithm().equals(key.getAlgorithm()))
                    || !verifySignature(token, key)) {
                throw invalid();
            }
            JWTClaimsSet claims = token.getJWTClaimsSet();
            String scope = claims.getStringClaim("scope");
            String connection = claims.getStringClaim("connection_id");
            if (claims.getSubject() == null || claims.getSubject().isBlank()
                    || connection == null || connection.isBlank() || scope == null || scope.isBlank()
                    || claims.getIssueTime() == null || claims.getExpirationTime() == null
                    || claims.getIssueTime().toInstant().isAfter(clock.instant())
                    || !claims.getExpirationTime().after(claims.getIssueTime())
                    || Duration.between(claims.getIssueTime().toInstant(),
                            claims.getExpirationTime().toInstant()).getSeconds() > 300) {
                throw invalid();
            }
            Set<String> scopes = Arrays.stream(scope.split(" +")).collect(Collectors.toUnmodifiableSet());
            if (!Set.of("catalog:read", "cart:read", "cart:write", "purchase:handoff").containsAll(scopes)) {
                throw invalid();
            }
            // 연결 해제 후에도 기존 토큰은 원래 만료까지 허용하는 최종 계약을 따른다.
            return new VerifiedMcpToken(true, claims.getIssuer(), Set.copyOf(claims.getAudience()),
                    claims.getExpirationTime().toInstant(),
                    claims.getNotBeforeTime() == null ? null : claims.getNotBeforeTime().toInstant(),
                    claims.getSubject(), connection, true, scopes);
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private synchronized JWK key(String keyId) {
        Instant now = clock.instant();
        if (keys == null || now.isAfter(refreshedAt.plusSeconds(300))
                || (keys.getKeyByKeyId(keyId) == null && now.isAfter(refreshedAt.plusSeconds(10)))) {
            HttpRequest request = HttpRequest.newBuilder(properties.jwksUri()).timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json").GET().build();
            var pending = client.sendAsync(request, info -> new LimitedBodySubscriber(65536));
            try {
                HttpResponse<byte[]> response = pending.get(3, TimeUnit.SECONDS);
                if (response.statusCode() != 200) {
                    throw new McpAuthException(McpAuthError.AUTH_PROVIDER_UNAVAILABLE);
                }
                keys = JWKSet.parse(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
                refreshedAt = now;
            } catch (ExecutionException | TimeoutException | ParseException exception) {
                throw new McpAuthException(McpAuthError.AUTH_PROVIDER_UNAVAILABLE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new McpAuthException(McpAuthError.AUTH_PROVIDER_UNAVAILABLE);
            } finally {
                pending.cancel(true);
            }
        }
        return keys.getKeyByKeyId(keyId);
    }

    private static boolean verifySignature(SignedJWT token, JWK key) throws JOSEException {
        if (JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm()) && key instanceof RSAKey rsa) {
            return rsa.size() >= 2048 && token.verify(new RSASSAVerifier(rsa));
        }
        if (JWSAlgorithm.ES256.equals(token.getHeader().getAlgorithm()) && key instanceof ECKey ec) {
            return Curve.P_256.equals(ec.getCurve()) && token.verify(new ECDSAVerifier(ec));
        }
        return false;
    }

    private static McpAuthException invalid() {
        return new McpAuthException(McpAuthError.INVALID_TOKEN);
    }
}
