package com.copenhagen.mcp.core;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreServiceTokenProviderTest {
    @TempDir
    Path directory;

    @Test
    void accessToken_signsClientAssertionAndCachesServiceToken() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("mcp-test").generate();
        Path keyFile = directory.resolve("private.jwk");
        Files.writeString(keyFile, key.toJSONString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger count = new AtomicInteger();
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext("/oauth/token", exchange -> {
            count.incrementAndGet();
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"test-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token");
            var provider = new CoreServiceTokenProvider(new ServiceAuthProperties(true, uri, "copenhagen-mcp", keyFile),
                    HttpClient.newHttpClient(), McpJsonDefaults.getMapper(),
                    Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
            assertEquals("test-service-token", provider.accessToken());
            assertEquals("test-service-token", provider.accessToken());
            assertEquals(1, count.get());
            assertTrue(captured.get().contains("grant_type=client_credentials"));
            String assertion = URLDecoder.decode(captured.get().split("&client_assertion=")[1], StandardCharsets.UTF_8);
            SignedJWT jwt = SignedJWT.parse(assertion);
            assertTrue(jwt.verify(new RSASSAVerifier(key.toPublicJWK())));
            assertEquals("copenhagen-mcp", jwt.getJWTClaimsSet().getSubject());
            assertEquals(uri.toString(), jwt.getJWTClaimsSet().getAudience().getFirst());
            assertEquals(60000, jwt.getJWTClaimsSet().getExpirationTime().getTime()
                    - jwt.getJWTClaimsSet().getIssueTime().getTime());
        } finally {
            server.stop(0);
        }
    }
}
