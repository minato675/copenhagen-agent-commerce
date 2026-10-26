package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;

/** Core는 버전 비교·변경·멱등성 기록·주문 검증 무효화를 하나의 트랜잭션으로 처리한다. */
public interface CoreCartEdits {

    Map<String, Object> change(McpIdentity identity, String action, Map<String, Object> request);
}
