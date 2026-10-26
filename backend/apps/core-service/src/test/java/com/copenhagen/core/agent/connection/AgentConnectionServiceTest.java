package com.copenhagen.core.agent.connection;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.refreshtoken.AgentRefreshTokenService;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentConnectionServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 10L;
    private static final long CLIENT_ID = 1L;

    private FakeAgentConnectionRepository repository;
    private AgentRefreshTokenService refreshTokenService;
    private AgentConnectionService service;

    @BeforeEach
    void setUp() {
        repository = new FakeAgentConnectionRepository();
        refreshTokenService = mock(AgentRefreshTokenService.class);
        service = new AgentConnectionService(
                repository, refreshTokenService, new ObjectMapper(), Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("첫 연동은 새 행을 만들고 refresh token을 건드리지 않는다")
    void upsertOnAuthorization_firstTime_insertsNewRow() {
        AgentConnection connection = service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read"));

        assertEquals(1, repository.rows.size());
        assertEquals(USER_ID, connection.userId());
        verify(refreshTokenService, never()).revokeAllForConnection(anyLong());
    }

    @Test
    @DisplayName("같은 scope 재인가는 기존 연동을 그대로 유지해 다른 기기의 refresh token을 살려둔다")
    void upsertOnAuthorization_sameScopes_keepsExistingConnection() {
        Set<String> scopes = Set.of("catalog:read", "cart:read");
        AgentConnection first = service.upsertOnAuthorization(USER_ID, CLIENT_ID, scopes);

        // 순서만 다른 같은 집합 — 저장된 JSON 배열 순서에 판정이 의존하지 않아야 한다.
        AgentConnection second =
                service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("cart:read", "catalog:read"));

        assertEquals(1, repository.rows.size());
        assertEquals(first.publicId(), second.publicId());
        assertNull(repository.findById(first.id()).orElseThrow().revokedAt());
        verify(refreshTokenService, never()).revokeAllForConnection(anyLong());
    }

    @Test
    @DisplayName("scope가 확대된 재인가는 기존 활성 연동을 폐기하고 새 연동을 만들며, 옛 연동의 refresh token을 전부 폐기한다")
    void upsertOnAuthorization_scopeWidened_revokesOldConnectionAndTokens() {
        AgentConnection first = service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read"));

        AgentConnection second =
                service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read", "cart:read"));

        assertEquals(2, repository.rows.size());
        assertNotEquals(first.publicId(), second.publicId());
        assertEquals(BASE_INSTANT, repository.findById(first.id()).orElseThrow().revokedAt());
        verify(refreshTokenService).revokeAllForConnection(first.id());
    }

    @Test
    @DisplayName("scope가 축소된 재인가도 기존 연동을 폐기한다(옛 토큰이 새 동의보다 넓은 권한을 갖지 않도록)")
    void upsertOnAuthorization_scopeNarrowed_revokesOldConnectionAndTokens() {
        AgentConnection first =
                service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read", "cart:read"));

        AgentConnection second = service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read"));

        assertEquals(2, repository.rows.size());
        assertNotEquals(first.publicId(), second.publicId());
        assertEquals(BASE_INSTANT, repository.findById(first.id()).orElseThrow().revokedAt());
        verify(refreshTokenService).revokeAllForConnection(first.id());
    }

    @Test
    @DisplayName("연동 목록은 해제된 연동을 제외한다")
    void list_excludesRevokedConnections() {
        AgentConnection active = service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read"));
        service.revoke(USER_ID, active.publicId());
        AgentConnection another = service.upsertOnAuthorization(USER_ID, 2L, Set.of("cart:read"));

        List<AgentConnectionSummary> summaries = service.list(USER_ID);

        assertEquals(1, summaries.size());
        assertEquals(another.publicId(), summaries.get(0).connectionId());
    }

    @Test
    @DisplayName("다른 사용자 소유의 연동을 해제하려 하면 AGENT_CONNECTION_NOT_FOUND를 던진다")
    void revoke_notOwner_throwsNotFound() {
        AgentConnection connection = service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read"));

        CoreException exception = assertThrows(
                CoreException.class, () -> service.revoke(USER_ID + 1, connection.publicId()));

        assertEquals(CoreErrorCode.AGENT_CONNECTION_NOT_FOUND, exception.errorCode());
    }

    @Test
    @DisplayName("해제하면 refresh token도 함께 폐기한다")
    void revoke_revokesRefreshTokensToo() {
        AgentConnection connection = service.upsertOnAuthorization(USER_ID, CLIENT_ID, Set.of("catalog:read"));

        service.revoke(USER_ID, connection.publicId());

        assertTrue(repository.findById(connection.id()).orElseThrow().revokedAt() != null);
        verify(refreshTokenService).revokeAllForConnection(connection.id());
    }
}
