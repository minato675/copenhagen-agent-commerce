package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;
import java.util.Set;

/**
 * MCP 내부 경계. 실제 구현은 합의된 Core HTTP 계약을 MerchantSchemas.CORE_RESPONSE로 변환한다.
 * 사용자 문맥의 서버 간 인증과 Core의 권한 검증이 필요하며 Merchant를 직접 호출하지 않는다.
 * S15P21A207-316: Core 계약 확정 전에는 테스트 구현만 존재한다.
 */
public interface CoreMerchantDirectory {

    /** 서버에서 등록·검증한 판매처별 capability와 버전. Tool 입력이나 Profile로 만들지 않는다. */
    Map<String, Set<Capability>> registeredCapabilities();

    /** 현재 사용자·연결에 대한 최신 목록. 미완성 목록을 전체 목록으로 반환하면 안 된다. */
    Map<String, Object> list(McpIdentity identity);

    record Capability(String name, String version) {

        public Capability {
            if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")) {
                throw new IllegalArgumentException("Capability name and version must be bounded identifiers");
            }
        }
    }
}
