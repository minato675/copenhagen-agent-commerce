package com.copenhagen.core.internalapi.auth;

import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;

/**
 * {@code InternalApiAuthenticationFilter}가 검증을 마치고 컨트롤러에 넘기는 값.
 *
 * @param userId       {@code users.id}(내부 PK). 장바구니·구매 요청이 FK로 쓴다
 * @param userPublicId Agent JWT의 {@code sub}
 * @param connectionId {@code agent_connections.id}(내부 PK). 구매 요청의 유입 연동으로 저장한다
 * @param scopes       토큰 scope ∩ 연동이 실제로 동의받은 scope
 * @param idempotencyKey 쓰기 요청에만 있다(조회는 {@code null})
 */
public record InternalRequestContext(
        long userId, UUID userPublicId, long connectionId, Set<String> scopes, UUID idempotencyKey) {

    private static final String ATTRIBUTE = InternalRequestContext.class.getName();

    public static void set(HttpServletRequest request, InternalRequestContext context) {
        request.setAttribute(ATTRIBUTE, context);
    }

    /**
     * 필터가 이미 미인증 요청을 걸러내므로 정상적으로는 항상 값이 있다. 없다면 필터 설정 누락이라
     * 인증 실패로 처리한다.
     */
    public static InternalRequestContext require(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (!(value instanceof InternalRequestContext context)) {
            throw new InternalApiException(InternalErrorCode.UNAUTHENTICATED);
        }
        return context;
    }

    /** 멱등키는 쓰기 경로에서만 요구한다 — 필터가 이미 형식까지 검증한 값이다. */
    public UUID requireIdempotencyKey() {
        if (idempotencyKey == null) {
            throw new InternalApiException(InternalErrorCode.INVALID_ARGUMENT, "Idempotency-Key가 필요합니다.");
        }
        return idempotencyKey;
    }
}
