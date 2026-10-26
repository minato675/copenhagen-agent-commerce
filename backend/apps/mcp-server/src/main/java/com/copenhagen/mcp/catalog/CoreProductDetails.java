package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;

/** Core 상세 API 교체 경계. 실제 쇼핑몰 UCP 통신과 옵션 검증은 Core가 소유한다. */
public interface CoreProductDetails {

    Map<String, Object> get(McpIdentity identity, ProductDetailRequest request);
}
