package com.copenhagen.core.common.auth;

/**
 * 보호 대상 라우트를 정의한다. {@code /auth/signup}·{@code /auth/login}·{@code /auth/token/refresh}·
 * {@code /auth/logout}은 공개 API다. {@code GET /api/core/v1/users/me}(순서 10)부터 보호 대상이 생겼고,
 * {@code /api/core/v1/addresses}(순서 17)부터 경로 변수가 있는 라우트도 prefix로 보호한다.
 * {@code /api/core/v1/products}(순서 24)도 동일하게 prefix로 보호한다 — 상품 데이터는 사용자 소유가 아니지만
 * 인증된 요청인지 게이트로만 사용한다. {@code /api/core/v1/cart}(순서 25)는 사용자 소유 자원이라
 * 배송지와 같은 등급으로 보호한다. {@code /api/core/v1/orders}(순서 27)도 사용자 장바구니·배송지를 읽고
 * 구매 요청을 만들므로 같은 등급이다. {@code /api/core/v1/users/me/agent-connections}(순서 19)도 사용자
 * 소유 자원이라 같은 등급으로 보호한다. {@code /oauth/authorize}·{@code /oauth/token}·
 * {@code /.well-known/*}는 Agent·MCP가 익명으로 호출하는 OAuth 표준 공개 엔드포인트라 이 필터의 보호
 * 대상에 넣지 않는다 — 각자 자기 인증 방식(로그인 폼, client 인증)을 스스로 검증한다.
 * {@code /api/core/v1/purchase-requests}(이슈 423)는 앱이 구매 인계를 조회하고 배송지를 골라 재개하는
 * 사용자 소유 경로라 같은 등급으로 보호한다.
 *
 * <p>{@code /api/core/v2/orders}는 주문내역 v2다. v1과 같은 자원을 읽으므로 보호 등급도 같다.
 */
public final class CoreAuthenticationPolicy {

    private static final String USERS_ME_PATH = "/api/core/v1/users/me";
    private static final String ADDRESSES_PATH = "/api/core/v1/addresses";
    private static final String ADDRESSES_PATH_PREFIX = ADDRESSES_PATH + "/";
    private static final String PRODUCTS_PATH_PREFIX = "/api/core/v1/products/";
    private static final String CART_PATH = "/api/core/v1/cart";
    private static final String CART_PATH_PREFIX = CART_PATH + "/";
    private static final String ORDERS_PATH = "/api/core/v1/orders";
    private static final String ORDERS_PATH_PREFIX = ORDERS_PATH + "/";
    private static final String ORDERS_V2_PATH = "/api/core/v2/orders";
    private static final String ORDERS_V2_PATH_PREFIX = ORDERS_V2_PATH + "/";
    private static final String PURCHASE_REQUESTS_PATH = "/api/core/v1/purchase-requests";
    private static final String PURCHASE_REQUESTS_PATH_PREFIX = PURCHASE_REQUESTS_PATH + "/";
    private static final String AGENT_CONNECTIONS_PATH = "/api/core/v1/users/me/agent-connections";
    private static final String AGENT_CONNECTIONS_PATH_PREFIX = AGENT_CONNECTIONS_PATH + "/";

    private CoreAuthenticationPolicy() {
    }

    public static boolean requiresAuthentication(String path) {
        return USERS_ME_PATH.equals(path)
                || ADDRESSES_PATH.equals(path)
                || path.startsWith(ADDRESSES_PATH_PREFIX)
                || path.startsWith(PRODUCTS_PATH_PREFIX)
                || CART_PATH.equals(path)
                || path.startsWith(CART_PATH_PREFIX)
                || ORDERS_PATH.equals(path)
                || path.startsWith(ORDERS_PATH_PREFIX)
                || ORDERS_V2_PATH.equals(path)
                || path.startsWith(ORDERS_V2_PATH_PREFIX)
                || PURCHASE_REQUESTS_PATH.equals(path)
                || path.startsWith(PURCHASE_REQUESTS_PATH_PREFIX)
                || AGENT_CONNECTIONS_PATH.equals(path)
                || path.startsWith(AGENT_CONNECTIONS_PATH_PREFIX);
    }
}
