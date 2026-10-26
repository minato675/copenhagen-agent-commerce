package com.copenhagen.core.internalapi.history;

import java.util.List;

/**
 * mcp-server {@code get_purchase_history}가 받는 응답(설계서 §4.2).
 *
 * <p>시각은 {@code Instant.toString()}으로 만든 문자열이다 — 내부 계약의 다른 응답
 * ({@code InternalPurchaseResponse.expiresAt})과 같은 표기이고, mcp-server는 {@code Instant.parse}로
 * 검증한다. 마지막 페이지에서도 {@code nextCursor} 키는 남기고 값만 {@code null}로 둔다(D-F, §4.2).
 */
public record InternalPurchaseHistoryResponse(String periodStart, List<Item> items, String nextCursor) {

    /**
     * @param merchantProductId MCP ID 패턴을 벗어나면 {@code null}이다(D-H). 항목 자체는 유지한다
     * @param merchantVariantId 같은 규칙으로 {@code null}일 수 있다
     * @param optionLabel       옵션이 없으면 빈 문자열
     */
    public record Item(
            String merchantId,
            String merchantName,
            String merchantProductId,
            String merchantVariantId,
            String productName,
            String optionLabel,
            int purchaseCount,
            long totalQuantity,
            long lastUnitPriceAmount,
            String currency,
            String lastPurchasedAt) {
    }
}
