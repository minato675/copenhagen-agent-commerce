package com.copenhagen.core.internalapi.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;

/**
 * 내부 API 쓰기 4종(cart 3 + purchase 1)의 멱등 처리(설계서 §6, ADR-0009).
 *
 * <p>업무 트랜잭션과 <b>같은 트랜잭션</b>에서 결과를 기록한다. 응답을 돌려주기 전에 저장이 끝나야
 * "결과 불명 시 같은 키로 재전송"이 장바구니를 두 번 바꾸지 않는다.
 *
 * <p>저장·재생 단위는 직렬화된 JSON 문자열이다. 다만 {@code response_body}가 JSONB라 Postgres가
 * 키 순서·공백을 정규화하므로, 재전송 응답은 최초 응답과 <b>값이 같을 뿐 문자열이 같지는 않다</b>.
 * mcp-server는 필드를 이름으로 읽으므로(={@code CommerceProjection}) 계약상 문제가 없다.
 */
@Service
public class IdempotencyStore {

    /** 보존 기간. mcp-server가 안내하는 "24시간 복구 기한"과 같다. */
    static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyStore(
            IdempotencyRepository repository, ObjectMapper objectMapper, Clock coreClock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = coreClock;
    }

    /**
     * 같은 키·같은 요청이면 저장된 응답을 그대로 돌려주고 {@code action}을 실행하지 않는다.
     * 같은 키·다른 요청이면 {@code IDEMPOTENCY_KEY_CONFLICT}다.
     *
     * @param canonicalRequest 멱등 판단에 쓸 요청 값을 순서가 고정된 문자열로 만든 것.
     *                         {@code X-Request-Id}는 요청마다 달라지므로 쓰지 않는다
     * @param action           업무 처리. 반환값을 JSON으로 직렬화해 저장한다
     */
    @Transactional
    public String execute(
            InternalRequestContext context,
            String method,
            String path,
            String canonicalRequest,
            Supplier<Object> action) {
        UUID key = context.requireIdempotencyKey();
        Instant now = clock.instant();
        String requestHash = hash(method, path, canonicalRequest);

        Optional<IdempotencyRecord> stored = repository.find(context.userId(), key, now);
        if (stored.isPresent()) {
            if (!stored.get().requestHash().equals(requestHash)) {
                throw new InternalApiException(InternalErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return stored.get().responseBody();
        }
        repository.deleteExpired(context.userId(), key, now);

        String responseBody = objectMapper.writeValueAsString(action.get());
        try {
            repository.insert(
                    context.userId(), key, method, path, requestHash,
                    HttpStatus.OK.value(), responseBody, now, now.plus(RETENTION));
        } catch (DuplicateKeyException exception) {
            // 같은 키의 동시 요청. 먼저 커밋한 쪽의 결과만 남기고 이쪽은 재조회를 시킨다.
            throw new InternalApiException(InternalErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return responseBody;
    }

    static String hash(String method, String path, String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String source = method + "\n" + path + "\n" + canonicalRequest;
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
