package com.copenhagen.core.internalapi.purchase;

/**
 * {@code prepare_purchase_handoff} 응답(설계서 §4.7).
 *
 * <p>mcp-server가 검증하는 생성 시점 스냅샷이다. {@code status}·{@code nextAction}은 고정 문자열,
 * {@code cartId}·{@code cartVersion}은 요청 값 그대로여야 하며 {@code expiresAt}은 31분을 넘지 않아야 한다.
 */
public record InternalPurchaseResponse(
        String purchaseRequestId,
        String status,
        String cartId,
        int cartVersion,
        String expiresAt,
        String nextAction) {

    public static final String STATUS_PENDING_ADDRESS = "PENDING_ADDRESS";
    public static final String NEXT_ACTION_SELECT_ADDRESS = "SELECT_SHIPPING_ADDRESS";
}
