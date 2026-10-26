package com.copenhagen.core.agent.connection;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.refreshtoken.AgentRefreshTokenService;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * Agent 연동 upsert·목록·해제. 재인가 시 기존 연동 처리는 팀 사전 설계
 * ({@code erd&api수정작업용/erd 수정/agent_oauth_tables.md} "결정 사항 (추가) 1.")가 정한
 * "기존 활성 연동을 revoke하고 새 행을 INSERT"를 <b>동의 범위가 달라졌을 때만</b> 적용한다.
 *
 * <p>그 결정이 막으려던 것은 옛 scope를 실은 {@code agent_refresh_tokens}이 새 동의 범위보다 넓은
 * 권한으로 살아남는 것이다. 요청 scope가 기존 {@code granted_scopes}와 정확히 같으면 그 위험이
 * 없으므로 연동을 그대로 둔다. {@code agent_connections}에는 기기·세션 축이 없어
 * (user × oauth_client 1행) 조건 없이 갈아끼우면 다른 기기의 refresh token까지 함께 폐기되고,
 * 사용자는 기기를 번갈아 쓸 때마다 재로그인해야 했다(이슈 477).
 *
 * <p>부분집합(요청 ⊂ 기존)도 "다름"으로 보고 폐기한다. 이 경우 옛 토큰이 새 동의보다 넓은 권한을
 * 갖게 되어 위 위험이 그대로 성립하기 때문이다.
 */
@Service
public class AgentConnectionService {

    private static final TypeReference<List<String>> SCOPES_TYPE = new TypeReference<>() { };

    private final AgentConnectionRepository connectionRepository;
    private final AgentRefreshTokenService refreshTokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentConnectionService(
            AgentConnectionRepository connectionRepository,
            AgentRefreshTokenService refreshTokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.connectionRepository = connectionRepository;
        this.refreshTokenService = refreshTokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AgentConnection upsertOnAuthorization(long userId, long oauthClientId, Set<String> grantedScopes) {
        AgentConnection existing = connectionRepository
                .findActiveByUserIdAndOauthClientId(userId, oauthClientId)
                .orElse(null);
        if (existing != null) {
            if (grantedScopes.equals(Set.copyOf(readScopes(existing.grantedScopesJson())))) {
                return existing;
            }
            connectionRepository.revoke(existing.id(), clock.instant());
            refreshTokenService.revokeAllForConnection(existing.id());
        }

        UUID publicId = UUID.randomUUID();
        String grantedScopesJson = writeScopes(grantedScopes);
        long id = connectionRepository.insert(publicId, userId, oauthClientId, grantedScopesJson, clock.instant());
        return connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Inserted agent connection not found: " + id));
    }

    public List<AgentConnectionSummary> list(long userId) {
        return connectionRepository.findActiveSummariesByUserId(userId);
    }

    @Transactional
    public void revoke(long userId, UUID connectionId) {
        AgentConnection connection = connectionRepository.findActiveByPublicIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AGENT_CONNECTION_NOT_FOUND));
        connectionRepository.revoke(connection.id(), clock.instant());
        refreshTokenService.revokeAllForConnection(connection.id());
    }

    public List<String> readScopes(String scopesJson) {
        return objectMapper.readValue(scopesJson, SCOPES_TYPE);
    }

    private String writeScopes(Set<String> scopes) {
        return objectMapper.writeValueAsString(scopes.stream().sorted().toList());
    }
}
