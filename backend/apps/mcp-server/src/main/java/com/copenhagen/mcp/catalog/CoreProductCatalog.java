package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;

/**
 * MCP가 Core에 상품 조회를 요청하는 교체 가능한 어댑터 경계.
 * 쇼핑몰 UCP 통신과 조회 집계는 Core가 담당한다. Mock은 QA 런타임에서만 주입한다.
 * 아래 모델은 MCP 내부 표현이며 실제 Core wire 계약은 확정 후 어댑터에서 변환한다.
 */
public interface CoreProductCatalog {

    Map<String, Object> search(McpIdentity identity, ProductSearchRequest request);
}
