package com.copenhagen.core.internalapi.history;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 설계서 §4.4 — 커서는 불투명 문자열이고 정렬 키 3개를 손실 없이 왕복해야 한다. */
class PurchaseHistoryCursorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("구분자로 쓸 수 없는 문자가 섞인 merchantProductId도 왕복한다")
    void encodeDecode_keepsIdentifiersWithSeparators() {
        Instant lastPurchasedAt = Instant.parse("2026-09-10T05:12:33.123456Z");
        PurchaseHistoryCursor cursor = PurchaseHistoryCursor.of(lastPurchasedAt, 2L, "urn:item:P-1001-BLK");

        PurchaseHistoryCursor decoded = PurchaseHistoryCursor.decode(
                objectMapper, PurchaseHistoryCursor.encode(objectMapper, cursor));

        assertEquals(cursor, decoded);
        assertEquals(lastPurchasedAt, Instant.ofEpochSecond(decoded.epochSecond(), decoded.nano()));
    }

    @Test
    @DisplayName("빈 커서는 첫 페이지를 뜻한다")
    void decode_blankMeansFirstPage() {
        assertNull(PurchaseHistoryCursor.decode(objectMapper, null));
        assertNull(PurchaseHistoryCursor.decode(objectMapper, "  "));
    }

    @Test
    @DisplayName("해석할 수 없는 커서는 INVALID_ARGUMENT다")
    void decode_rejectsBrokenCursor() {
        assertEquals(InternalErrorCode.INVALID_ARGUMENT, assertThrows(InternalApiException.class,
                () -> PurchaseHistoryCursor.decode(objectMapper, "not-base64!!")).errorCode());
        assertEquals(InternalErrorCode.INVALID_ARGUMENT, assertThrows(InternalApiException.class,
                () -> PurchaseHistoryCursor.decode(objectMapper, "eyJub3RKc29uIjo=")).errorCode());
    }

    @Test
    @DisplayName("merchantProductId가 빠진 커서는 거절한다")
    void decode_rejectsCursorWithoutProductId() {
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"epochSecond\":1,\"nano\":0,\"merchantRefId\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(InternalErrorCode.INVALID_ARGUMENT, assertThrows(InternalApiException.class,
                () -> PurchaseHistoryCursor.decode(objectMapper, cursor)).errorCode());
    }
}
