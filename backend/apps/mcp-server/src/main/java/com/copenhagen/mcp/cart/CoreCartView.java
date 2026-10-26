package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;

/** Core가 소유한 현재 장바구니와 견적 조회 포트. */
public interface CoreCartView {

    Map<String, Object> get(McpIdentity identity);
}
