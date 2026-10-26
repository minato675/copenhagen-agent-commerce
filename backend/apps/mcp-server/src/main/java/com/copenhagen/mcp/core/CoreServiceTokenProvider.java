package com.copenhagen.mcp.core;

import com.copenhagen.mcp.tool.ToolErrorCode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 서비스 토큰만 만료 직전까지 캐시한다. 사용자 Agent 토큰은 캐시하지 않는다. */
public final class CoreServiceTokenProvider implements ServiceTokenProvider {
    private final ServiceAuthProperties properties;
    private final HttpClient client;
    private final McpJsonMapper mapper;
    private final Clock clock;
    private String cached;
    private Instant refreshAt = Instant.MIN;

    public CoreServiceTokenProvider(ServiceAuthProperties properties, HttpClient client,
            McpJsonMapper mapper, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Token endpoint redirects must be disabled");
        }
    }

    @Override
    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(refreshAt)) {
            return cached;
        }
        String body = "grant_type=client_credentials&client_id=" + encode(properties.clientId())
                + "&client_assertion_type=" + encode("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                + "&client_assertion=" + encode(assertion(now));
        HttpRequest request = HttpRequest.newBuilder(properties.tokenUri()).timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var pending = client.sendAsync(request, info -> new LimitedBodySubscriber(65536));
        try {
            var response = pending.get(3, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                throw unavailable();
            }
            Map<String, Object> value = mapper.readValue(response.body(), new TypeRef<>() { });
            if (!(value.get("access_token") instanceof String token) || !token.matches("[A-Za-z0-9._~-]{1,8192}")
                    || !"Bearer".equalsIgnoreCase(String.valueOf(value.get("token_type")))
                    || !(value.get("expires_in") instanceof Number ttl) || ttl.longValue() < 1
                    || ttl.doubleValue() != ttl.longValue() || ttl.longValue() > 300) {
                throw unavailable();
            }
            cached = token;
            refreshAt = now.plusSeconds(Math.max(0, ttl.longValue() - 30));
            return cached;
        } catch (ExecutionException | TimeoutException | IOException exception) {
            throw unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            pending.cancel(true);
        }
    }

    private String assertion(Instant now) {
        try {
            // 다음 발급 시 파일을 다시 읽어 개인키 교체를 반영한다.
            if (Files.size(properties.privateJwkFile()) > 65536) {
                throw unavailable();
            }
            JWK key = JWK.parse(Files.readString(properties.privateJwkFile()));
            JWSSigner signer;
            JWSAlgorithm algorithm;
            if (!key.isPrivate() || key.getKeyID() == null || key.getKeyID().isBlank()) {
                throw unavailable();
            }
            if (key instanceof RSAKey rsa && rsa.size() >= 2048) {
                signer = new RSASSASigner(rsa);
                algorithm = JWSAlgorithm.RS256;
            } else if (key instanceof ECKey ec && Curve.P_256.equals(ec.getCurve())) {
                signer = new ECDSASigner(ec);
                algorithm = JWSAlgorithm.ES256;
            } else {
                throw unavailable();
            }
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(properties.clientId())
                    .subject(properties.clientId()).audience(properties.tokenUri().toString())
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                    .jwtID(UUID.randomUUID().toString()).build();
            SignedJWT assertion = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(), claims);
            assertion.sign(signer);
            return assertion.serialize();
        } catch (IOException | ParseException | JOSEException exception) {
            throw unavailable();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static CoreClientException unavailable() {
        return new CoreClientException(ToolErrorCode.CORE_UNAVAILABLE);
    }
}
