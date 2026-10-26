package com.copenhagen.core.internalapi.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 설계서 §6 — 같은 키·같은 요청은 업무 처리를 다시 하지 않고, 같은 키·다른 요청은 409다. */
class IdempotencyStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-18T05:00:00Z");
    private static final long USER_ID = 42L;
    private static final String PATH = "/internal/v1/carts/current/items";

    private final FakeIdempotencyRepository repository = new FakeIdempotencyRepository();
    private final IdempotencyStore store =
            new IdempotencyStore(repository, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("같은 키·같은 요청 재전송은 저장된 응답을 그대로 돌려주고 업무 처리를 다시 하지 않는다")
    void sameKeySameRequest_replaysStoredResponse() {
        UUID key = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        String first = store.execute(context(key), "POST", PATH, request(1),
                () -> response(calls.incrementAndGet()));
        String second = store.execute(context(key), "POST", PATH, request(1),
                () -> response(calls.incrementAndGet()));

        assertEquals(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("같은 키·다른 요청은 IDEMPOTENCY_KEY_CONFLICT")
    void sameKeyDifferentRequest_isConflict() {
        UUID key = UUID.randomUUID();
        store.execute(context(key), "POST", PATH, request(1), () -> response(1));

        InternalApiException exception = assertThrows(InternalApiException.class,
                () -> store.execute(context(key), "POST", PATH, request(2), () -> response(2)));

        assertEquals(InternalErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
    }

    @Test
    @DisplayName("다른 키는 업무 처리를 다시 실행한다")
    void differentKey_executesAgain() {
        AtomicInteger calls = new AtomicInteger();

        store.execute(context(UUID.randomUUID()), "POST", PATH, request(1),
                () -> response(calls.incrementAndGet()));
        store.execute(context(UUID.randomUUID()), "POST", PATH, request(1),
                () -> response(calls.incrementAndGet()));

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("만료된 행은 없는 것으로 보고 같은 키를 다시 쓸 수 있다")
    void expiredRecord_isIgnored() {
        UUID key = UUID.randomUUID();
        repository.insert(USER_ID, key, "POST", PATH, "stale-hash", 200, "{}",
                NOW.minus(IdempotencyStore.RETENTION).minusSeconds(60), NOW.minusSeconds(60));
        AtomicInteger calls = new AtomicInteger();

        store.execute(context(key), "POST", PATH, request(1), () -> response(calls.incrementAndGet()));

        assertEquals(1, calls.get());
        assertEquals(1, repository.rows.size());
    }

    @Test
    @DisplayName("멱등키 없이 쓰기 경로를 호출하면 INVALID_ARGUMENT")
    void missingKey_isInvalidArgument() {
        InternalRequestContext context =
                new InternalRequestContext(USER_ID, UUID.randomUUID(), 1L, Set.of(), null);

        InternalApiException exception = assertThrows(InternalApiException.class,
                () -> store.execute(context, "POST", PATH, request(1), () -> response(1)));

        assertEquals(InternalErrorCode.INVALID_ARGUMENT, exception.errorCode());
    }

    private InternalRequestContext context(UUID key) {
        return new InternalRequestContext(USER_ID, UUID.randomUUID(), 1L, Set.of(), key);
    }

    private String request(int quantity) {
        return "merchantId=merchant-a&quantity=" + quantity;
    }

    private Object response(int version) {
        return new ResponseBody(version);
    }

    private record ResponseBody(int version) {
    }
}
