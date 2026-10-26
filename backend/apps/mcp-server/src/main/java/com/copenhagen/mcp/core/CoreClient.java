package com.copenhagen.mcp.core;

import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Core 전용 HTTP 경계. 경로와 응답 schema는 합의된 계약을 사용하는 서버 코드가 제공한다.
 * Tool의 URL·인증 헤더를 전달하지 않으며, 실패한 변경 요청을 자동 재시도하지 않는다.
 */
public class CoreClient {

    private static final TypeRef<Map<String, Object>> OBJECT_TYPE = new TypeRef<>() { };

    private final HttpClient httpClient;
    private final CoreProperties properties;
    private final McpJsonMapper mapper;
    private final JsonSchemaValidator validator;

    public CoreClient(HttpClient httpClient, CoreProperties properties,
            McpJsonMapper mapper, JsonSchemaValidator validator) {
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Core client must not follow redirects");
        }
        this.httpClient = httpClient;
        this.properties = properties;
        this.mapper = mapper;
        this.validator = validator;
    }

    public Map<String, Object> get(String path, Map<String, Object> responseSchema) {
        return exchange(request(path).GET().build(), responseSchema);
    }

    public Map<String, Object> post(String path, Map<String, Object> body, Map<String, Object> responseSchema) {
        try {
            return exchange(request(path).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build(), responseSchema);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Core request cannot be serialized", exception);
        }
    }

    private HttpRequest.Builder request(String path) {
        // 현재 경계는 계약의 고정 상대 경로만 받는다. 검색 조건 등은 계약 확정 후 별도로 인코딩한다.
        if (path == null || !path.matches("/(?:[A-Za-z0-9_~-]+/?)*")) {
            throw new IllegalArgumentException("Core path must be a fixed absolute path without a host or query");
        }
        URI destination = properties.baseUrl().resolve(path);
        return HttpRequest.newBuilder(destination)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json");
    }

    private Map<String, Object> exchange(HttpRequest request, Map<String, Object> responseSchema) {
        if (responseSchema == null) {
            throw new IllegalArgumentException("Core response schema is required");
        }
        validator.assertConforms("Core response", responseSchema);
        CompletableFuture<HttpResponse<byte[]>> pending = httpClient.sendAsync(request,
                information -> new LimitedBodySubscriber(properties.maxResponseBytes()));
        try {
            // sendAsync의 완료까지 기다려 헤더 뒤 멈춘 본문에도 전체 응답 제한 시간을 적용한다.
            HttpResponse<byte[]> response = pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CoreClientException(ToolErrorCode.CORE_SERVICE_ERROR);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!("application/json".equals(contentType)
                    || (contentType.startsWith("application/") && contentType.endsWith("+json")))) {
                throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
            }
            Map<String, Object> value = mapper.readValue(response.body(), OBJECT_TYPE);
            if (value == null || !validator.validate(responseSchema, value).valid()) {
                throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
            }
            return value;
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new CoreClientException(ToolErrorCode.CORE_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new CoreClientException(ToolErrorCode.CORE_UNAVAILABLE, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CoreClientException coreException) {
                throw coreException;
            }
            ToolErrorCode code = cause instanceof HttpTimeoutException
                    ? ToolErrorCode.CORE_TIMEOUT : ToolErrorCode.CORE_UNAVAILABLE;
            throw new CoreClientException(code, cause);
        } catch (IOException exception) {
            throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE, exception);
        }
    }
}
