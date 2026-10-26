package com.copenhagen.core.agent.refreshtoken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentRefreshTokenServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final long CONNECTION_ID = 1L;
    private static final String SCOPES_JSON = "[\"catalog:read\"]";

    private FakeAgentRefreshTokenRepository repository;
    private AgentRefreshTokenBreachService breachService;
    private AgentRefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = new FakeAgentRefreshTokenRepository();
        breachService = mock(AgentRefreshTokenBreachService.class);
        service = new AgentRefreshTokenService(repository, breachService, Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("발급 시 평문 토큰과 14일 뒤 만료를 반환하고, 저장값은 해시다")
    void issue_returnsPlainTokenAndPersistsHashOnly() {
        IssuedAgentRefreshToken issued = service.issue(CONNECTION_ID, SCOPES_JSON);

        assertEquals(BASE_INSTANT.plus(Duration.ofDays(14)), issued.expiresAt());
        AgentRefreshToken saved = repository.rows.get(0);
        assertEquals(sha256Hex(issued.plainToken()), saved.tokenHash());
        assertNotEquals(issued.plainToken(), saved.tokenHash());
    }

    @Test
    @DisplayName("활성 토큰을 검증하면 해당 행을 반환한다")
    void verifyForRotation_activeToken_returnsRow() {
        String plainToken = "plain-token";
        AgentRefreshToken seeded = seedActive(plainToken);

        AgentRefreshToken verified = service.verifyForRotation(plainToken);

        assertEquals(seeded.id(), verified.id());
        verify(breachService, never()).revokeAllForConnection(anyLong());
    }

    @Test
    @DisplayName("만료된 토큰은 탈취 처리 없이 OAUTH_INVALID_GRANT로만 거부한다")
    void verifyForRotation_expiredToken_throwsInvalidGrant() {
        String plainToken = "expired-token";
        repository.seed(
                CONNECTION_ID, sha256Hex(plainToken), SCOPES_JSON,
                BASE_INSTANT.minus(Duration.ofDays(20)), BASE_INSTANT.minus(Duration.ofDays(6)), null);

        CoreException exception = assertThrows(CoreException.class, () -> service.verifyForRotation(plainToken));

        assertEquals(CoreErrorCode.OAUTH_INVALID_GRANT, exception.errorCode());
        verify(breachService, never()).revokeAllForConnection(anyLong());
    }

    @Test
    @DisplayName("이미 폐기된 토큰을 다시 쓰면 그 연동의 활성 토큰을 전부 폐기한다")
    void verifyForRotation_revokedToken_revokesAllForConnection() {
        String plainToken = "revoked-token";
        repository.seed(
                CONNECTION_ID, sha256Hex(plainToken), SCOPES_JSON,
                BASE_INSTANT, BASE_INSTANT.plus(Duration.ofDays(14)), BASE_INSTANT);

        CoreException exception = assertThrows(CoreException.class, () -> service.verifyForRotation(plainToken));

        assertEquals(CoreErrorCode.OAUTH_INVALID_GRANT, exception.errorCode());
        verify(breachService).revokeAllForConnection(CONNECTION_ID);
    }

    @Test
    @DisplayName("회전하면 기존 행을 폐기하고 같은 connection·scope로 새 토큰을 발급한다")
    void rotate_revokesCurrentAndIssuesNewTokenForSameConnection() {
        String plainToken = "plain-token";
        AgentRefreshToken current = seedActive(plainToken);

        IssuedAgentRefreshToken rotated = service.rotate(current);

        AgentRefreshToken revoked = repository.findById(current.id()).orElseThrow();
        assertEquals(BASE_INSTANT, revoked.revokedAt());

        AgentRefreshToken issued = repository.rows.get(repository.rows.size() - 1);
        assertEquals(sha256Hex(rotated.plainToken()), issued.tokenHash());
        assertEquals(CONNECTION_ID, issued.connectionId());
        assertEquals(SCOPES_JSON, issued.scopesJson());
        assertNull(issued.revokedAt());
    }

    @Test
    @DisplayName("연동 해제 시 그 connection의 활성 토큰을 전부 폐기한다")
    void revokeAllForConnection_marksActiveTokensRevoked() {
        seedActive("token-a");
        seedActive("token-b");

        service.revokeAllForConnection(CONNECTION_ID);

        assertEquals(2, repository.rows.stream().filter(row -> row.revokedAt() != null).count());
    }

    private AgentRefreshToken seedActive(String plainToken) {
        return repository.seed(
                CONNECTION_ID, sha256Hex(plainToken), SCOPES_JSON,
                BASE_INSTANT, BASE_INSTANT.plus(Duration.ofDays(14)), null);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
