package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;

/** MCP 내부 정규화 포트. 실제 Core HTTP 경로/DTO 합의 후 어댑터로 구현한다. */
public interface CoreCart {

    Map<String, Object> add(McpIdentity identity, Map<String, Object> request);
}
