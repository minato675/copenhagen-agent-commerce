package com.copenhagen.core.agent.authorize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.client.OauthClient;
import com.copenhagen.core.agent.client.OauthClientRepository;
import com.copenhagen.core.agent.connection.AgentConnectionService;
import com.copenhagen.core.auth.login.LoginResult;
import com.copenhagen.core.auth.login.LoginService;
import com.copenhagen.core.common.error.CoreException;

/**
 * {@code GET /oauth/authorize}(요청 시작) + {@code POST /oauth/authorize/{requestId}/decision}
 * (로그인+동의 처리)를 담당한다. Core가 로그인·동의 화면을 직접 서빙하기로 확정(설계서 §1.3)했으므로
 * 이 서비스가 그 전체 상태 전이를 갖는다.
 */
@Service
public class AuthorizeConsentService {

    static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    static final Duration CODE_TTL = Duration.ofSeconds(60);
    private static final String CODE_CHALLENGE_METHOD_S256 = "S256";
    private static final int CODE_BYTES = 32;
    private static final Set<String> SHOPPING_SCOPES = Set.of(
            "catalog:read", "cart:read", "cart:write", "purchase:handoff");
    private static final TypeReference<List<String>> SCOPES_TYPE = new TypeReference<>() { };

    private final OauthClientRepository clientRepository;
    private final OauthAuthorizationRequestRepository requestRepository;
    private final AgentConnectionService connectionService;
    private final LoginService loginService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthorizeConsentService(
            OauthClientRepository clientRepository,
            OauthAuthorizationRequestRepository requestRepository,
            AgentConnectionService connectionService,
            LoginService loginService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.clientRepository = clientRepository;
        this.requestRepository = requestRepository;
        this.connectionService = connectionService;
        this.loginService = loginService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AuthorizeStartResult start(
            String clientId,
            String redirectUri,
            String scope,
            String state,
            String codeChallenge,
            String codeChallengeMethod) {
        if (isBlank(clientId) || isBlank(redirectUri) || isBlank(scope) || isBlank(state)
                || isBlank(codeChallenge) || isBlank(codeChallengeMethod)) {
            return new AuthorizeStartResult.Rejected("필수 파라미터가 누락되었습니다.");
        }
        if (!CODE_CHALLENGE_METHOD_S256.equals(codeChallengeMethod)) {
            return new AuthorizeStartResult.Rejected("code_challenge_method는 S256만 지원합니다.");
        }

        OauthClient client = clientRepository.findByClientId(clientId).orElse(null);
        if (client == null || !client.isActive()) {
            return new AuthorizeStartResult.Rejected("알 수 없는 클라이언트입니다.");
        }
        if (!readStrings(client.redirectUris()).contains(redirectUri)) {
            return new AuthorizeStartResult.Rejected("등록되지 않은 리다이렉트 URI입니다.");
        }

        Set<String> allowedScopes = Set.copyOf(readStrings(client.allowedScopes()));
        Set<String> requestedScopes = splitScope(scope);
        if (requestedScopes.isEmpty() || !allowedScopes.containsAll(requestedScopes)) {
            return new AuthorizeStartResult.ScopeRejected(redirectUri, state);
        }
        // Full shopping clients display the complete shopping grant before user consent.
        // Restricted clients retain their registered scope boundaries.
        if (allowedScopes.containsAll(SHOPPING_SCOPES)) {
            requestedScopes = new HashSet<>(requestedScopes);
            requestedScopes.addAll(SHOPPING_SCOPES);
        }

        UUID requestId = UUID.randomUUID();
        Instant now = clock.instant();
        requestRepository.insert(
                requestId, client.id(), redirectUri, writeStrings(requestedScopes), state,
                codeChallenge, codeChallengeMethod, now.plus(REQUEST_TTL), now);

        return new AuthorizeStartResult.Started(
                requestId, client.clientName(), client.logoUrl(), sortedList(requestedScopes));
    }

    @Transactional
    public AuthorizeDecisionResult decide(UUID requestId, String loginId, String password, String decision) {
        OauthAuthorizationRequest authorizationRequest = requestRepository.findByRequestId(requestId).orElse(null);
        if (authorizationRequest == null
                || !OauthAuthorizationRequest.STATUS_PENDING.equals(authorizationRequest.status())
                || authorizationRequest.isExpired(clock.instant())) {
            return new AuthorizeDecisionResult.NotFound("요청을 찾을 수 없거나 만료되었습니다. 처음부터 다시 시도해 주세요.");
        }

        if ("DENY".equals(decision)) {
            requestRepository.markDenied(authorizationRequest.id(), clock.instant());
            return new AuthorizeDecisionResult.Denied(authorizationRequest.redirectUri(), authorizationRequest.state());
        }

        OauthClient client = clientRepository.findById(authorizationRequest.oauthClientId())
                .orElseThrow(() -> new IllegalStateException(
                        "oauth_client missing for authorization request " + authorizationRequest.id()));

        LoginResult loginResult;
        try {
            loginResult = loginService.login(loginId, password);
        } catch (CoreException exception) {
            return new AuthorizeDecisionResult.LoginFailed(
                    requestId, client.clientName(), client.logoUrl(),
                    readStrings(authorizationRequest.requestedScopesJson()), exception.getMessage());
        }

        Set<String> grantedScopes = Set.copyOf(readStrings(authorizationRequest.requestedScopesJson()));
        connectionService.upsertOnAuthorization(loginResult.userInternalId(), client.id(), grantedScopes);

        byte[] codeBytes = new byte[CODE_BYTES];
        secureRandom.nextBytes(codeBytes);
        String plainCode = Base64.getUrlEncoder().withoutPadding().encodeToString(codeBytes);
        Instant now = clock.instant();
        requestRepository.markApproved(
                authorizationRequest.id(), loginResult.userInternalId(), sha256Hex(plainCode),
                now.plus(CODE_TTL), now);

        return new AuthorizeDecisionResult.Approved(
                authorizationRequest.redirectUri(), plainCode, authorizationRequest.state());
    }

    private List<String> readStrings(String json) {
        return objectMapper.readValue(json, SCOPES_TYPE);
    }

    private String writeStrings(Set<String> values) {
        return objectMapper.writeValueAsString(sortedList(values));
    }

    private static List<String> sortedList(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static Set<String> splitScope(String scope) {
        return java.util.Arrays.stream(scope.split("\\s+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
