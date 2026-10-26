package com.copenhagen.core.internalapi.history;

import java.time.Instant;
import java.util.List;

/** 구매내역 집계 조회(설계서 §4.3). 상품 단위로 묶어 한 페이지씩 읽는다. */
public interface PurchaseHistoryRepository {

    /**
     * @param limit 다음 페이지 존재 여부를 판단하려고 {@code pageSize + 1}을 넘긴다
     */
    List<PurchaseHistoryRow> findPage(long userId, Instant periodStart, PurchaseHistoryCursor cursor, int limit);

    /**
     * @param merchantRefId      {@code merchants.id}(내부 PK). 응답에서는 문자열 merchantId로 바꾼다
     * @param selectedOptions    가장 최근 구매 줄의 {@code selected_options} JSON 문자열
     * @param purchaseCount      이 상품이 포함된 서로 다른 주문 수
     * @param totalQuantity      기간 내 수량 합
     */
    record PurchaseHistoryRow(
            long merchantRefId,
            String merchantName,
            String merchantProductId,
            String merchantVariantId,
            String productName,
            String selectedOptions,
            int purchaseCount,
            long totalQuantity,
            long unitPriceAmount,
            String currency,
            Instant lastPurchasedAt) {
    }
}
