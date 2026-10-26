package com.copenhagen.contract.ucp;

/** Merchant가 계산한 옵션 단가(기본가와 추가금 합계)와 조회 시점 재고. 최종 금액은 Checkout에서 검증한다. */
public record CatalogOptionValue(String optionValueId, String label, Long priceAmount, Integer stockQuantity) {

    /** 가격·재고 필드가 없던 Catalog 제공자와의 전환 기간 호환 생성자. */
    public CatalogOptionValue(String optionValueId, String label) {
        this(optionValueId, label, null, null);
    }

    public long priceOr(long productPrice) {
        return priceAmount == null ? productPrice : priceAmount;
    }
}
