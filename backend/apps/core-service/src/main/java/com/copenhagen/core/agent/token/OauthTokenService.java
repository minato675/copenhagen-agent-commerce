package com.copenhagen.core.agent.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.authorize.OauthAuthorizationRequest;
import com.copenhagen.core.agent.authorize.OauthAuthorizationRequestRepository;
import com.copenhagen.core.agent.client.OauthClient;
import com.copenhagen.core.agent.client.OauthClientRepository;
import com.copenhagen.core.agent.connection.AgentConnection;
import com.copenhagen.core.agent.connection.AgentConnectionRepository;
import com.copenhagen.core.agent.jwt.AgentJwtIssuer;
import com.copenhagen.core.agent.jwt.AgentOauthProperties;
import com.copenhagen.core.agent.jwt.IssuedAgentToken;
import com.copenhagen.core.agent.refreshtoken.AgentRefreshToken;
import com.copenhagen.core.agent.refreshtoken.AgentRefreshTokenService;
import com.copenhagen.core.agent.refreshtoken.IssuedAgentRefreshToken;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.error.CoreException;

/**
 * {@code POST /oauth/token}. 공식 명세는 {@code authorization_code} 하나만 다루지만, 이미 병합된
 * mcp-server(이슈400) 설계를 그대로 따르기로 사용자가 확정(설계서 §0·§1.1)해 {@code refresh_token}·
 * {@code client_credentials}까지 세 가지 grant를 지원한다.
 */
@Service
public class OauthTokenService {

    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    private static final String GRANT_REFRESH_TOKEN = "refresh_token";
    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";
    private static final String ASSERTION_TYPE_JWT_BEARER =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final TypeReference<List<String>> SCOPES_TYPE = new TypeReference<>() { };

    private final OauthAuthorizationRequestRepository authorizationRequestRepository;
    private final OauthClientRepository clientRepository;
    private final AgentConnectionRepository connectionRepository;
    private final AgentRefreshTokenService refreshTokenService;
    private final AgentJwtIssuer jwtIssuer;
    private final AgentOauthProperties properties;
    private final ClientAssertionValidator assertionValidator;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OauthTokenService(
            OauthAuthorizationRequestRepository authorizationRequestRepository,
            OauthClientRepository clientRepository,
            AgentConnectionRepository connectionRepository,
            AgentRefreshTokenService refreshTokenService,
            AgentJwtIssuer jwtIssuer,
            AgentOauthProperties properties,
            ClientAssertionValidator assertionValidator,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizationRequestRepository = authorizationRequestRepository;
        this.clientRepository = clientRepository;
        this.connectionRepository = connectionRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtIssuer = jwtIssuer;
        this.properties = properties;
        this.assertionValidator = assertionValidator;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // Controller에서 호출하는 프록시 진입점에 적용해야 내부 grant 처리에도 같은 트랜잭션이 유지된다.
    @Transactional
    public TokenResult handle(TokenRequestParams params) {
        return switch (params.grantType() == null ? "" : params.grantType()) {
            case GRANT_AUTHORIZATION_CODE -> handleAuthorizationCode(params);
            case GRANT_REFRESH_TOKEN -> handleRefreshToken(params);
            case GRANT_CLIENT_CREDENTIALS -> handleClientCredentials(params);
            default -> error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "지원하지 않는 grant_type입니다.");
        };
    }

    TokenResult handleAuthorizationCode(TokenRequestParams params) {
        if (isBlank(params.code()) || isBlank(params.redirectUri()) || isBlank(params.clientId())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "필수 파라미터가 누락되었습니다.");
        }

        OauthAuthorizationRequest authorizationRequest =
                authorizationRequestRepository.findByCodeHashForUpdate(sha256Hex(params.code())).orElse(null);
        Instant now = clock.instant();
        if (authorizationRequest == null
                || !OauthAuthorizationRequest.STATUS_APPROVED.equals(authorizationRequest.status())
                || authorizationRequest.codeExpiresAt() == null
                || !authorizationRequest.codeExpiresAt().isAfter(now)
                || !authorizationRequest.redirectUri().equals(params.redirectUri())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "유효하지 않거나 만료된 코드입니다.");
        }

        OauthClient client = clientRepository.findById(authorizationRequest.oauthClientId()).orElse(null);
        if (client == null || !client.clientId().equals(params.clientId())) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client", "클라이언트 인증에 실패했습니다.");
        }
        TokenResult clientAuthError = authenticateAuthorizationCodeClient(client, authorizationRequest, params);
        if (clientAuthError != null) {
            return clientAuthError;
        }

        if (authorizationRequestRepository.markConsumed(authorizationRequest.id(), now) != 1) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "이미 사용된 코드입니다.");
        }

        long userId = authorizationRequest.userId();
        AgentConnection connection = connectionRepository
                .findActiveByUserIdAndOauthClientId(userId, client.id())
                .orElseThrow(() -> new IllegalStateException(
                        "agent_connection missing after authorization for user " + userId));
        Set<String> scopes = Set.copyOf(readScopes(authorizationRequest.requestedScopesJson()));
        User user = requireUser(userId);

        IssuedAgentToken accessToken =
                jwtIssuer.issueAgentAccessToken(user.publicId(), connection.publicId().toString(), scopes);
        IssuedAgentRefreshToken refreshToken =
                refreshTokenService.issue(connection.id(), writeScopes(scopes));

        return new TokenResult.Success(
                accessToken.token(), refreshToken.plainToken(), accessToken.expiresInSeconds(),
                String.join(" ", sortedScopes(scopes)));
    }

    TokenResult handleRefreshToken(TokenRequestParams params) {
        if (isBlank(params.refreshToken()) || isBlank(params.clientId())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "필수 파라미터가 누락되었습니다.");
        }

        AgentRefreshToken current;
        try {
            current = refreshTokenService.verifyForRotation(params.refreshToken());
        } catch (CoreException exception) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "유효하지 않은 Refresh Token입니다.");
        }

        AgentConnection connection = connectionRepository.findById(current.connectionId()).orElse(null);
        if (connection == null || connection.revokedAt() != null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "연동이 해제되었습니다.");
        }
        OauthClient client = clientRepository.findById(connection.oauthClientId()).orElse(null);
        if (client == null || !client.clientId().equals(params.clientId())) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client", "클라이언트 인증에 실패했습니다.");
        }
        TokenResult clientAuthError = authenticateConfidentialClientIfNeeded(client, params.clientSecret());
        if (clientAuthError != null) {
            return clientAuthError;
        }

        IssuedAgentRefreshToken rotated = refreshTokenService.rotate(current);
        Set<String> scopes = Set.copyOf(readScopes(current.scopesJson()));
        User user = requireUser(connection.userId());
        IssuedAgentToken accessToken =
                jwtIssuer.issueAgentAccessToken(user.publicId(), connection.publicId().toString(), scopes);

        return new TokenResult.Success(
                accessToken.token(), rotated.plainToken(), accessToken.expiresInSeconds(),
                String.join(" ", sortedScopes(scopes)));
    }

    TokenResult handleClientCredentials(TokenRequestParams params) {
        if (isBlank(params.clientId()) || isBlank(params.clientAssertion())
                || !ASSERTION_TYPE_JWT_BEARER.equals(params.clientAssertionType())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "private_key_jwt 인증 정보가 필요합니다.");
        }
        OauthClient client = clientRepository.findByClientId(params.clientId()).orElse(null);
        if (client == null || !client.isActive() || client.isPublic()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client", "클라이언트 인증에 실패했습니다.");
        }
        try {
            assertionValidator.validate(client, params.clientAssertion(), properties.tokenEndpoint());
        } catch (InvalidClientAssertionException exception) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client", "클라이언트 인증에 실패했습니다.");
        }

        Set<String> allowedScopes = Set.copyOf(readScopes(client.allowedScopes()));
        Set<String> scopes = allowedScopes;
        if (!isBlank(params.scope())) {
            scopes = Set.of(params.scope().trim().split("\\s+"));
            if (!allowedScopes.containsAll(scopes)) {
                return error(HttpStatus.BAD_REQUEST, "invalid_scope", "허용되지 않은 scope가 포함되어 있습니다.");
            }
        }

        IssuedAgentToken serviceToken = jwtIssuer.issueServiceToken(client.clientId(), scopes);
        return new TokenResult.Success(
                serviceToken.token(), null, serviceToken.expiresInSeconds(), String.join(" ", sortedScopes(scopes)));
    }

    private TokenResult authenticateAuthorizationCodeClient(
            OauthClient client, OauthAuthorizationRequest authorizationRequest, TokenRequestParams params) {
        if (client.isPublic()) {
            boolean verifierMissing = isBlank(params.codeVerifier());
            boolean verifierMismatch = !verifierMissing
                    && !pkceMatches(params.codeVerifier(), authorizationRequest.codeChallenge());
            if (verifierMissing || verifierMismatch) {
                return error(HttpStatus.BAD_REQUEST, "invalid_grant", "code_verifier가 code_challenge와 일치하지 않습니다.");
            }
            return null;
        }
        return authenticateConfidentialClientIfNeeded(client, params.clientSecret());
    }

    private TokenResult authenticateConfidentialClientIfNeeded(OauthClient client, String clientSecret) {
        if (client.isPublic()) {
            return null;
        }
        if (isBlank(clientSecret) || client.clientSecretHash() == null
                || !passwordEncoder.matches(clientSecret, client.clientSecretHash())) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client", "클라이언트 인증에 실패했습니다.");
        }
        return null;
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("user missing for agent token issuance: " + userId));
    }

    private List<String> readScopes(String scopesJson) {
        return objectMapper.readValue(scopesJson, SCOPES_TYPE);
    }

    private String writeScopes(Set<String> scopes) {
        return objectMapper.writeValueAsString(sortedScopes(scopes));
    }

    private static List<String> sortedScopes(Set<String> scopes) {
        return scopes.stream().sorted().toList();
    }

    private static boolean pkceMatches(String codeVerifier, String codeChallenge) {
        return codeChallenge.equals(base64UrlSha256(codeVerifier));
    }

    private static String base64UrlSha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static TokenResult.Error error(HttpStatus httpStatus, String error, String description) {
        return new TokenResult.Error(httpStatus, error, description);
    }
}
