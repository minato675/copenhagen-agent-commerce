package com.copenhagen.mcp.core;

import com.copenhagen.mcp.auth.McpIdentity;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Core 최종 계약 전용 경계. 목적지와 헤더는 모델 입력으로 받지 않는다. */
public final class InternalCoreClient {
    private static final Logger log = LoggerFactory.getLogger(InternalCoreClient.class);
    private static final Set<String> ERROR_CODES = Set.of("CART_VERSION_CONFLICT", "QUANTITY_LIMIT_EXCEEDED",
            "IDEMPOTENCY_KEY_CONFLICT", "CART_ITEM_NOT_FOUND", "PRODUCT_NOT_FOUND", "VARIANT_NOT_FOUND",
            "OUT_OF_STOCK", "CART_EMPTY", "CART_ITEM_LIMIT_EXCEEDED", "ACCESS_DENIED", "INVALID_ARGUMENT");
    private final CoreProperties properties;
    private final HttpClient client;
    private final ServiceTokenProvider tokens;
    private final McpJsonMapper mapper;

    public InternalCoreClient(CoreProperties properties, HttpClient client,
            ServiceTokenProvider tokens, McpJsonMapper mapper) {
        this.properties = properties;
        this.client = client;
        this.tokens = tokens;
        this.mapper = mapper;
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Core redirects must be disabled");
        }
        String host = properties.baseUrl().getHost();
        if (!"https".equals(properties.baseUrl().getScheme())
                && !Set.of("core-service", "localhost", "127.0.0.1", "[::1]").contains(host)) {
            throw new IllegalArgumentException("Core credentials require HTTPS outside the local service network");
        }
    }

    public Map<String, Object> exchange(McpIdentity identity, String method, String path,
            Map<String, Object> query, Map<String, Object> body, String idempotencyKey) {
        if (!path.matches("/internal/v1/(?:[A-Za-z0-9_:~-]+/?)*")
                || !Set.of("GET", "POST", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("Invalid internal API request");
        }
        boolean write = !method.equals("GET");
        if (write && (idempotencyKey == null || !UUID.fromString(idempotencyKey).toString().equals(idempotencyKey))) {
            throw new IllegalArgumentException("UUID idempotency key is required");
        }
        String requestId = MDC.get("mcpRequestId");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        if (identity.agentAccessToken() == null || !Instant.now().isBefore(identity.expiresAt())) {
            throw new CoreApiException("AUTHENTICATION_REQUIRED", "NOT_APPLIED", requestId);
        }
        String suffix = query.isEmpty() ? "" : "?" + query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(String.valueOf(entry.getValue())))
                .collect(Collectors.joining("&"));
        URI destination = properties.baseUrl().resolve(path + suffix);
        long tokenStarted = System.nanoTime();
        String serviceToken;
        try {
            serviceToken = tokens.accessToken();
        } finally {
            com.copenhagen.mcp.diagnostics.DiagnosticSessions.stage("service_token", tokenStarted);
        }
        log.info("MCP timing: requestId={}, stage=service_token, elapsedMs={}",
                requestId, (System.nanoTime() - tokenStarted) / 1_000_000);
        HttpRequest.Builder builder = HttpRequest.newBuilder(destination).timeout(properties.requestTimeout())
                .header("Accept", "application/json").header("Authorization", "Bearer " + serviceToken)
                .header("X-Agent-Authorization", "Bearer " + identity.agentAccessToken())
                .header("X-Request-Id", requestId);
        if (write) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        try {
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json").method(method,
                        HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            }
        } catch (IOException exception) {
            throw new CoreApiException("INVALID_ARGUMENT", "NOT_APPLIED", requestId);
        }
        long coreStarted = System.nanoTime();
        var pending = client.sendAsync(builder.build(),
                info -> new LimitedBodySubscriber(properties.maxResponseBytes()));
        String unknown = write ? "UNKNOWN" : "NOT_APPLICABLE";
        try {
            var response = pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = "CORE_SERVICE_ERROR";
                try {
                    Map<String, Object> value = mapper.readValue(response.body(), new TypeRef<>() { });
                    Object error = value.get("error");
                    Object candidate = error instanceof Map<?, ?> map ? map.get("code") : value.get("code");
                    if (candidate instanceof String text && ERROR_CODES.contains(text)) {
                        code = text;
                    }
                } catch (IOException | NullPointerException exception) {
                    // 비표준 오류 본문을 클라이언트에 노출하지 않는다.
                }
                boolean rejected = Set.of(400, 401, 403, 404, 409, 422).contains(response.statusCode());
                throw new CoreApiException(code, rejected ? "NOT_APPLIED" : unknown, requestId);
            }
            if (response.statusCode() == 204 && write) {
                return Map.of();
            }
            if (!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
                throw new CoreApiException("CORE_INVALID_RESPONSE", unknown, requestId);
            }
            Map<String, Object> value = mapper.readValue(response.body(), new TypeRef<>() { });
            if (value == null) {
                throw new CoreApiException("CORE_INVALID_RESPONSE", unknown, requestId);
            }
            return value;
        } catch (IOException exception) {
            throw new CoreApiException("CORE_INVALID_RESPONSE", unknown, requestId);
        } catch (TimeoutException exception) {
            throw new CoreApiException("CORE_TIMEOUT", unknown, requestId);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new CoreApiException("CORE_TIMEOUT", unknown, requestId);
            }
            throw new CoreApiException("CORE_UNAVAILABLE", unknown, requestId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CoreApiException("CORE_UNAVAILABLE", unknown, requestId);
        } finally {
            pending.cancel(true);
            com.copenhagen.mcp.diagnostics.DiagnosticSessions.stage("core_http", coreStarted);
            log.info("MCP timing: requestId={}, stage=core_http, method={}, elapsedMs={}",
                    requestId, method, (System.nanoTime() - coreStarted) / 1_000_000);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
