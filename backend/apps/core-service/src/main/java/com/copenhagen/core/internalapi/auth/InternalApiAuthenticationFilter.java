package com.copenhagen.core.internalapi.auth;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.client.OauthClient;
import com.copenhagen.core.agent.client.OauthClientRepository;
import com.copenhagen.core.agent.connection.AgentConnection;
import com.copenhagen.core.agent.connection.AgentConnectionRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.logging.CoreLogContext;
import com.copenhagen.core.internalapi.error.InternalApiErrorWriter;
import com.copenhagen.core.internalapi.error.InternalErrorCode;

/**
 * {@code /internal/v1/**} 전용 인증. 앱용 {@code CoreAuthenticationFilter}와 완전히 별개다 —
 * 토큰이 두 개(서비스 + 사용자 위임)이고, 오류 바디 형식도 다르며, 보호 경로도 겹치지 않는다
 * ({@code CoreAuthenticationPolicy}에 {@code /internal/v1}을 넣지 않는다).
 *
 * <p>검증 순서는 설계서 §3.2와 같다. Service JWT → Agent JWT → 연동 확인 → scope → 멱등키 형식.
 * 연동이 해제됐으면 토큰이 아직 살아 있어도 업무 API는 막는다(연동 해제 시 발급된 토큰을 폐기하지
 * 않는다는 {@code core-integration.md} 서술과 충돌하지 않는다 — 폐기와 접근 허용은 다른 문제다).
 *
 * <p>{@code @Component}가 아니라 {@link com.copenhagen.core.internalapi.config.InternalApiConfiguration}이
 * 등록한다. 필터 빈은 모든 {@code @WebMvcTest} 슬라이스에 딸려 들어가는데, 이 필터가 요구하는 빈들은
 * 슬라이스에 없어 앱 전체의 컨트롤러 테스트가 컨텍스트 생성부터 실패하기 때문이다.
 */
public class InternalApiAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AGENT_AUTHORIZATION_HEADER = "X-Agent-Authorization";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final TypeReference<List<String>> SCOPES_TYPE = new TypeReference<>() { };

    private final InternalApiTokenVerifier tokenVerifier;
    private final OauthClientRepository clientRepository;
    private final UserRepository userRepository;
    private final AgentConnectionRepository connectionRepository;
    private final InternalApiErrorWriter errorWriter;
    private final ObjectMapper objectMapper;

    public InternalApiAuthenticationFilter(
            InternalApiTokenVerifier tokenVerifier,
            OauthClientRepository clientRepository,
            UserRepository userRepository,
            AgentConnectionRepository connectionRepository,
            InternalApiErrorWriter errorWriter,
            ObjectMapper objectMapper) {
        this.tokenVerifier = tokenVerifier;
        this.clientRepository = clientRepository;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.errorWriter = errorWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DispatcherType.REQUEST.equals(request.getDispatcherType())
                || !InternalScopes.isInternalPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Optional<VerifiedInternalToken> serviceToken =
                tokenVerifier.verify(bearer(request.getHeader(HttpHeaders.AUTHORIZATION)));
        if (serviceToken.isEmpty() || !isActiveConfidentialClient(serviceToken.get().subject())) {
            errorWriter.write(response, InternalErrorCode.UNAUTHENTICATED);
            return;
        }

        Optional<VerifiedInternalToken> agentToken =
                tokenVerifier.verify(bearer(request.getHeader(AGENT_AUTHORIZATION_HEADER)));
        if (agentToken.isEmpty() || agentToken.get().connectionId() == null) {
            errorWriter.write(response, InternalErrorCode.UNAUTHENTICATED);
            return;
        }

        UUID userPublicId = parseUuid(agentToken.get().subject());
        UUID connectionPublicId = parseUuid(agentToken.get().connectionId());
        if (userPublicId == null || connectionPublicId == null) {
            errorWriter.write(response, InternalErrorCode.UNAUTHENTICATED);
            return;
        }

        Optional<User> user = userRepository.findByPublicId(userPublicId);
        if (user.isEmpty()) {
            errorWriter.write(response, InternalErrorCode.ACCESS_DENIED);
            return;
        }
        Optional<AgentConnection> connection =
                connectionRepository.findActiveByPublicIdAndUserId(connectionPublicId, user.get().id());
        if (connection.isEmpty()) {
            errorWriter.write(response, InternalErrorCode.ACCESS_DENIED);
            return;
        }

        // 토큰 발급 이후 동의 범위가 줄어들 수 있어 연동이 실제로 갖고 있는 scope와의 교집합만 인정한다.
        Set<String> scopes = effectiveScopes(agentToken.get().scopes(), connection.get());
        String requiredScope = InternalScopes.requiredScope(request.getRequestURI());
        if (requiredScope != null && !scopes.contains(requiredScope)) {
            errorWriter.write(response, InternalErrorCode.ACCESS_DENIED);
            return;
        }

        UUID idempotencyKey = null;
        if (!HttpMethod.GET.matches(request.getMethod())) {
            idempotencyKey = parseUuid(request.getHeader(IDEMPOTENCY_KEY_HEADER));
            if (idempotencyKey == null) {
                errorWriter.write(response, InternalErrorCode.INVALID_ARGUMENT);
                return;
            }
        }

        InternalRequestContext.set(request, new InternalRequestContext(
                user.get().id(), userPublicId, connection.get().id(), scopes, idempotencyKey));
        CoreLogContext.put(CoreLogContext.USER_ID, userPublicId.toString());
        filterChain.doFilter(request, response);
    }

    private boolean isActiveConfidentialClient(String clientId) {
        return clientRepository.findByClientId(clientId)
                .filter(OauthClient::isActive)
                .filter(client -> !client.isPublic())
                .isPresent();
    }

    private Set<String> effectiveScopes(Set<String> tokenScopes, AgentConnection connection) {
        Set<String> granted = new LinkedHashSet<>(
                objectMapper.readValue(connection.grantedScopesJson(), SCOPES_TYPE));
        Set<String> effective = new LinkedHashSet<>(tokenScopes);
        effective.retainAll(granted);
        return Set.copyOf(effective);
    }

    private String bearer(String headerValue) {
        if (headerValue == null
                || !headerValue.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = headerValue.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
