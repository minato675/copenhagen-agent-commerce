package com.copenhagen.core.internalapi.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.agent.AgentScopes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 순서 43 — 구매내역 조회 경로의 scope 매핑(설계서 D-A). */
class InternalScopesTest {

    @Test
    @DisplayName("구매내역 조회는 기존 purchase:handoff를 요구한다")
    void requiredScope_purchaseHistoryReusesHandoffScope() {
        assertEquals(AgentScopes.PURCHASE_HANDOFF, InternalScopes.requiredScope("/internal/v1/purchase-history"));
    }

    @Test
    @DisplayName("구매 요청 경로 매핑은 그대로다")
    void requiredScope_keepsExistingMappings() {
        assertEquals(AgentScopes.PURCHASE_HANDOFF, InternalScopes.requiredScope("/internal/v1/purchase-requests"));
        assertEquals(AgentScopes.CART_READ, InternalScopes.requiredScope("/internal/v1/carts/current"));
        assertEquals(AgentScopes.CART_WRITE, InternalScopes.requiredScope("/internal/v1/carts/current/items"));
        assertEquals(AgentScopes.CATALOG_READ, InternalScopes.requiredScope("/internal/v1/catalog/products"));
    }

    @Test
    @DisplayName("구매내역 하위 경로는 목록에 없어 404로 떨어진다")
    void requiredScope_unknownSubPathHasNoScope() {
        assertNull(InternalScopes.requiredScope("/internal/v1/purchase-history/1"));
        assertNull(InternalScopes.requiredScope("/internal/v1/purchase-histories"));
    }
}
