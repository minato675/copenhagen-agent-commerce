package com.copenhagen.mcp.handoff;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;

/** MCP 내부 정규화 포트. Core 소유 인계 세션이며 실제 HTTP 계약은371에서 확정한다. */
@FunctionalInterface
public interface CorePurchaseHandoff {
    Map<String, Object> prepare(McpIdentity identity, Map<String, Object> request);
}
