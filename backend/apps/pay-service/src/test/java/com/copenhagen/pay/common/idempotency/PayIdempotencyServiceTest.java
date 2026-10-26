package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PayIdempotencyServiceTest {

    private static final String ENCRYPTION_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String REQUEST_KEY = "550e8400-e29b-41d4-a716-446655440010";
    private static final String CONCURRENT_KEY = "550e8400-e29b-41d4-a716-446655440011";
    private static final String SCOPE_KEY = "550e8400-e29b-41d4-a716-446655440012";
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void sameScopeAndHashReplaysEncryptedResponseWithoutInvokingChainAgain() throws Exception {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        PayIdempotencyFilter filter = filter(repository);
        AtomicInteger invocations = new AtomicInteger();
        FilterChain chain = (request, response) -> {
            invocations.incrementAndGet();
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(201);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"credential\":\"mandate-secret\"}");
        };

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(request("{\"a\":1,\"b\":2}"), firstResponse, chain);
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        filter.doFilter(request("{ \"b\": 2, \"a\": 1 }"), replayResponse, chain);

        assertEquals(1, invocations.get());
        assertEquals(201, replayResponse.getStatus());
        assertEquals(firstResponse.getContentAsString(), replayResponse.getContentAsString());
        assertFalse(repository.onlyRecord().responseBodyEncrypted().contains("mandate-secret"));
    }

    @Test
    void sameScopeAndDifferentBodyReturnsConflictWithoutInvokingChain() throws Exception {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        PayIdempotencyFilter filter = filter(repository);
        AtomicInteger invocations = new AtomicInteger();
        FilterChain chain = (request, response) -> invocations.incrementAndGet();

        filter.doFilter(request("{\"amount\":100}"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse conflictResponse = new MockHttpServletResponse();
        filter.doFilter(request("{\"amount\":200}"), conflictResponse, chain);

        assertEquals(1, invocations.get());
        assertEquals(409, conflictResponse.getStatus());
        assertTrue(conflictResponse.getContentAsString().contains("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void directServiceConflictUsesContractError() {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        PayIdempotencyService service = service(repository);
        IdempotencyScope scope = scope(REQUEST_KEY);

        assertTrue(service.acquire(scope, "a".repeat(64)).owner());
        service.complete(scope, 204, null, new byte[0]);

        PayException exception = assertThrows(
                PayException.class,
                () -> service.acquire(scope, "b".repeat(64)));

        assertEquals(PayErrorCode.IDEMPOTENCY_CONFLICT, exception.errorCode());
    }

    @Test
    void concurrentSameRequestClaimsBusinessExecutionOnce() throws Exception {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        PayIdempotencyService service = service(repository);
        IdempotencyScope scope = scope(CONCURRENT_KEY);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger owners = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(
                    () -> acquireAndComplete(service, scope, start, owners));
            Future<Boolean> second = executor.submit(
                    () -> acquireAndComplete(service, scope, start, owners));
            start.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, owners.get());
    }

    @Test
    void callerMethodAndPathArePartOfTheScope() {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        PayIdempotencyService service = service(repository);
        String requestHash = "a".repeat(64);
        IdempotencyScope original = scope(SCOPE_KEY);

        assertTrue(service.acquire(original, requestHash).owner());
        service.complete(original, 204, null, new byte[0]);

        IdempotencyScope differentCaller = new IdempotencyScope(
                "USER",
                "user-002",
                "NONE",
                "POST",
                original.normalizedPath(),
                original.idempotencyKey());
        assertTrue(service.acquire(differentCaller, requestHash).owner());
    }

    @ParameterizedTest
    @CsvSource({
        "PATCH, /api/pay/v1/payment-methods/cards/method-001/default, 200",
        "DELETE, /api/pay/v1/payment-methods/cards/method-001, 204"
    })
    void bodylessStateChangeReachesTheChainInsteadOfFailingToHash(
            String method, String path, int status) throws Exception {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        PayIdempotencyFilter filter = filter(repository);
        AtomicInteger invocations = new AtomicInteger();
        FilterChain chain = (request, response) -> {
            invocations.incrementAndGet();
            ((HttpServletResponse) response).setStatus(status);
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(bodylessRequest(method, path), response, chain);

        assertEquals(1, invocations.get());
        assertEquals(status, response.getStatus());
    }

    @Test
    void bodylessRetryWithSameKeyReplaysTheFirstResponse() throws Exception {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        PayIdempotencyFilter filter = filter(repository);
        AtomicInteger invocations = new AtomicInteger();
        String path = "/api/pay/v1/payment-methods/cards/method-001/default";
        FilterChain chain = (request, response) -> {
            invocations.incrementAndGet();
            ((HttpServletResponse) response).setStatus(200);
        };

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(bodylessRequest("PATCH", path), first, chain);
        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(bodylessRequest("PATCH", path), replay, chain);

        assertEquals(1, invocations.get());
        assertEquals(200, replay.getStatus());
    }

    private PayIdempotencyFilter filter(InMemoryIdempotencyRepository repository) {
        return new PayIdempotencyFilter(
                new IdempotencyKeyResolver(),
                new com.copenhagen.pay.common.error.ApiErrorWriter(new ObjectMapper()),
                new RequestHashGenerator(new ObjectMapper()),
                service(repository));
    }

    private PayIdempotencyService service(InMemoryIdempotencyRepository repository) {
        ArtifactEncryptionService encryptionService = new ArtifactEncryptionService(
                new MandateProperties("pay-service", "key-1", "", ENCRYPTION_KEY));
        return new PayIdempotencyService(
                repository,
                new IdempotencyGuard(),
                encryptionService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/pay/v1/payment-methods/cards/");
        request.addHeader("Idempotency-Key", REQUEST_KEY);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        PayRequestContext.setPrincipal(request, new PayPrincipal(PayPrincipal.Type.USER, "user-001"));
        return request;
    }

    /** 카드 기본 지정·삭제처럼 본문이 없는 것이 정상인 상태 변경 요청. */
    private MockHttpServletRequest bodylessRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Idempotency-Key", REQUEST_KEY);
        request.setContent(new byte[0]);
        PayRequestContext.setPrincipal(request, new PayPrincipal(PayPrincipal.Type.USER, "user-001"));
        return request;
    }

    private IdempotencyScope scope(String key) {
        return new IdempotencyScope(
                "USER",
                "user-001",
                "NONE",
                "POST",
                "/api/pay/v1/payment-methods/cards",
                key);
    }

    private boolean acquireAndComplete(
            PayIdempotencyService service,
            IdempotencyScope scope,
            CountDownLatch start,
            AtomicInteger owners) throws Exception {
        start.await();
        PayIdempotencyService.AcquireResult result = service.acquire(scope, "c".repeat(64));
        if (result.owner()) {
            owners.incrementAndGet();
            Thread.sleep(100);
            service.complete(scope, 204, null, new byte[0]);
        }
        return result.owner();
    }

    private static final class InMemoryIdempotencyRepository implements IdempotencyRepository {

        private final Map<IdempotencyScope, IdempotencyRecord> records = new ConcurrentHashMap<>();

        @Override
        public Optional<IdempotencyRecord> findByScope(IdempotencyScope scope) {
            return Optional.ofNullable(records.get(scope));
        }

        @Override
        public boolean insert(IdempotencyRecord record) {
            return records.putIfAbsent(record.scope(), record) == null;
        }

        @Override
        public boolean complete(
                IdempotencyScope scope,
                int responseStatus,
                String responseContentType,
                String responseBodyEncrypted,
                Instant completedAt) {
            IdempotencyRecord current = records.get(scope);
            if (current == null || current.status() != IdempotencyRecord.Status.PROCESSING) {
                return false;
            }
            records.put(
                    scope,
                    new IdempotencyRecord(
                            current.id(),
                            scope,
                            current.requestHash(),
                            IdempotencyRecord.Status.COMPLETED,
                            responseStatus,
                            responseContentType,
                            responseBodyEncrypted,
                            current.createdAt(),
                            completedAt));
            return true;
        }

        @Override
        public boolean deleteProcessing(IdempotencyScope scope) {
            IdempotencyRecord current = records.get(scope);
            return current != null
                    && current.status() == IdempotencyRecord.Status.PROCESSING
                    && records.remove(scope, current);
        }

        private IdempotencyRecord onlyRecord() {
            return records.values().iterator().next();
        }
    }
}
