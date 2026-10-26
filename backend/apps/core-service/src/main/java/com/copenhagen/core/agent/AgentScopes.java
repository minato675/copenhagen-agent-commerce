package com.copenhagen.core.agent;

import java.util.Set;

/**
 * mcp-server {@code JwksMcpTokenVerifier}에 하드코딩된 scope 카탈로그(이슈 S15P21A207-400).
 * API 명세엔 없던 제약이라 이 프로젝트에서는 이 4개로 scope를 고정한다.
 */
public final class AgentScopes {

    public static final String CATALOG_READ = "catalog:read";
    public static final String CART_READ = "cart:read";
    public static final String CART_WRITE = "cart:write";
    public static final String PURCHASE_HANDOFF = "purchase:handoff";

    public static final Set<String> ALL = Set.of(CATALOG_READ, CART_READ, CART_WRITE, PURCHASE_HANDOFF);

    private AgentScopes() {
    }
}
