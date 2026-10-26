package com.copenhagen.core.internalapi.history;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;

/**
 * 구매내역 커서(설계서 §4.4). 호출자에게는 불투명 문자열이고 내부 표현은 정렬 키 3개를 담은 JSON의
 * base64url이다.
 *
 * <p>{@code merchantProductId}에 {@code :}·{@code -}가 올 수 있어 {@code orderhistory}의 구분자 문자열
 * 방식은 쓰지 않는다. 또 여기 담는 값은 응답에서 D-H로 {@code null} 처리되기 전의 DB 원본이다 —
 * 커서는 다음 페이지의 시작점을 가리켜야 하므로 표현용 값과 분리한다.
 */
public record PurchaseHistoryCursor(long epochSecond, int nano, long merchantRefId, String merchantProductId) {

    public static PurchaseHistoryCursor of(Instant lastPurchasedAt, long merchantRefId, String merchantProductId) {
        return new PurchaseHistoryCursor(
                lastPurchasedAt.getEpochSecond(), lastPurchasedAt.getNano(), merchantRefId, merchantProductId);
    }

    public static String encode(ObjectMapper objectMapper, PurchaseHistoryCursor cursor) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(cursor));
    }

    /** 빈 커서는 첫 페이지를 뜻해 {@code null}을 돌려준다. 해석할 수 없으면 400이다. */
    public static PurchaseHistoryCursor decode(ObjectMapper objectMapper, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            PurchaseHistoryCursor decoded = objectMapper.readValue(
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8),
                    PurchaseHistoryCursor.class);
            if (decoded == null || decoded.merchantProductId() == null
                    || decoded.nano() < 0 || decoded.nano() > 999_999_999) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException | JacksonException exception) {
            throw invalid();
        }
    }

    private static InternalApiException invalid() {
        return new InternalApiException(InternalErrorCode.INVALID_ARGUMENT, "cursor가 올바르지 않습니다.");
    }
}
