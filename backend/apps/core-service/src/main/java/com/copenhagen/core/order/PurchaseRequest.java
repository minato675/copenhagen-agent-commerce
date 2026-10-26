package com.copenhagen.core.order;

import java.util.UUID;

/**
 * {@code purchase_requests} 한 행. {@code public_id}가 API의 {@code validationId}이고, merchant에 보내는
 * {@code purchase_request_id}이자 Merchant Authorization의 claim이다.
 */
public record PurchaseRequest(long id, UUID publicId, long userId, long cartId, String status) {

    public static final String STATUS_CREATING_CHECKOUTS = "CREATING_CHECKOUTS";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * MCP 구매 인계가 만든 요청. 아직 배송지를 고르지 않은 단계라 merchant 주문서도 만들지 않는다
     * (순서 34). 앱이 배송지를 고른 뒤 이어받는 경로는 별도 순서가 필요하다(설계서 §12).
     */
    public static final String STATUS_PENDING_ADDRESS = "PENDING_ADDRESS";

    /** 앱에서 들어온 구매 요청. MCP 유입 경로는 tool 등록 이후에 생긴다. */
    public static final String CHANNEL_APP = "APP";

    /** ChatGPT 등 Agent가 MCP를 거쳐 넘긴 구매 요청. */
    public static final String CHANNEL_MCP = "MCP";
}
