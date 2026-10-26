package com.copenhagen.mcp.auth;

/**
 * Core가 발급한 JWT의 서명을 검증하는 경계.
 * 연결 해제 후 기존 Access Token은 원래 만료까지 유효하며 introspection은 사용하지 않는다.
 * 서명 없는 decode 결과나 클라이언트의 사용자 헤더를 검증 결과로 사용하지 않는다.
 */
@FunctionalInterface
public interface McpTokenVerifier {
    VerifiedMcpToken verify(String accessToken);
}
