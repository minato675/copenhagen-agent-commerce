package com.copenhagen.core.agent.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.authorize.FakeOauthAuthorizationRequestRepository;
import com.copenhagen.core.agent.client.FakeOauthClientRepository;
import com.copenhagen.core.agent.client.OauthClient;
import com.copenhagen.core.agent.connection.FakeAgentConnectionRepository;
import com.copenhagen.core.agent.jwt.AgentJwtIssuer;
import com.copenhagen.core.agent.jwt.AgentJwtKeySet;
import com.copenhagen.core.agent.jwt.AgentOauthProperties;
import com.copenhagen.core.agent.refreshtoken.AgentRefreshTokenBreachService;
import com.copenhagen.core.agent.refreshtoken.AgentRefreshTokenService;
import com.copenhagen.core.agent.refreshtoken.FakeAgentRefreshTokenRepository;
import com.copenhagen.core.agent.refreshtoken.IssuedAgentRefreshToken;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OauthTokenServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ISSUER = "https://core.example.com";
    private static final String MCP_AUDIENCE = "https://core.example.com/mcp";
    private static final String REDIRECT_URI = "https://chat.openai.com/callback";

    private FakeOauthAuthorizationRequestRepository authorizationRequestRepository;
    private FakeOauthClientRepository clientRepository;
    private FakeAgentConnectionRepository connectionRepository;
    private AgentRefreshTokenService refreshTokenService;
    private ClientAssertionValidator assertionValidator;
    private UserRepository userRepository;
    private OauthTokenService service;
    private OauthClient publicClient;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        authorizationRequestRepository = new FakeOauthAuthorizationRequestRepository();
        clientRepository = new FakeOauthClientRepository();
        publicClient = new OauthClient(
                1L, "chatgpt-plugin", null, "ChatGPT", OauthClient.TYPE_PUBLIC,
                "[\"" + REDIRECT_URI + "\"]", "[\"catalog:read\",\"cart:read\"]", null,
                OauthClient.STATUS_ACTIVE, null);
        clientRepository.add(publicClient);
        connectionRepository = new FakeAgentConnectionRepository();
        FakeAgentRefreshTokenRepository refreshTokenRepository = new FakeAgentRefreshTokenRepository();
        refreshTokenService = new AgentRefreshTokenService(
                refreshTokenRepository, mock(AgentRefreshTokenBreachService.class),
                Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
        refreshTokenService = transactionalProxy(refreshTokenService, transactionManager);
        assertionValidator = mock(ClientAssertionValidator.class);
        userRepository = mock(UserRepository.class);
        AgentJwtIssuer jwtIssuer = new AgentJwtIssuer(
                new AgentJwtKeySet("", "test-key"),
                new AgentOauthProperties(ISSUER, MCP_AUDIENCE),
                Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));

        service = new OauthTokenService(
                authorizationRequestRepository, clientRepository, connectionRepository, refreshTokenService,
                jwtIssuer, new AgentOauthProperties(ISSUER, MCP_AUDIENCE), assertionValidator, userRepository,
                new BCryptPasswordEncoder(10), new ObjectMapper(), Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
        service = transactionalProxy(service, transactionManager);
    }

    private static <T> T transactionalProxy(T target, DataSourceTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }

    @Test
    @DisplayName("알 수 없는 refresh token은 Spring 트랜잭션 경계를 거쳐도 invalid_grant다")
    void handle_refreshTokenUnknown_returnsInvalidGrant() {
        TokenResult result = service.handle(new TokenRequestParams(
                "refresh_token", null, null, "chatgpt-plugin", null, null, "unknown-test-token", null, null, null));

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, result);
        assertEquals("invalid_grant", error.error());
    }

    @Test
    @DisplayName("정상 authorization_code 교환은 access/refresh token을 발급하고 코드를 소비한다")
    void handle_authorizationCode_success() {
        String codeVerifier = "verifier-value-1234567890123456789012345";
        String codeChallenge = base64UrlSha256(codeVerifier);
        String plainCode = "plain-code-value";
        seedApprovedRequest(plainCode, codeChallenge, Set.of("catalog:read"));
        seedActiveConnection(42L, 1L);
        seedUser(42L);

        TokenResult result = service.handle(new TokenRequestParams(
                "authorization_code", plainCode, REDIRECT_URI, "chatgpt-plugin", codeVerifier,
                null, null, null, null, null));

        TokenResult.Success success = assertInstanceOf(TokenResult.Success.class, result);
        assertEquals(300L, success.expiresIn());
        assertEquals("catalog:read", success.scope());
    }

    @Test
    @DisplayName("같은 코드를 두 번 교환하면 두 번째는 invalid_grant다")
    void handle_authorizationCode_reused_rejected() {
        String codeVerifier = "verifier-value-1234567890123456789012345";
        String codeChallenge = base64UrlSha256(codeVerifier);
        String plainCode = "plain-code-value";
        seedApprovedRequest(plainCode, codeChallenge, Set.of("catalog:read"));
        seedActiveConnection(42L, 1L);
        seedUser(42L);
        TokenRequestParams params = new TokenRequestParams(
                "authorization_code", plainCode, REDIRECT_URI, "chatgpt-plugin", codeVerifier,
                null, null, null, null, null);
        service.handle(params);

        TokenResult second = service.handle(params);

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, second);
        assertEquals("invalid_grant", error.error());
    }

    @Test
    @DisplayName("code_verifier가 code_challenge와 맞지 않으면 invalid_grant다")
    void handle_authorizationCode_pkceMismatch_rejected() {
        String codeChallenge = base64UrlSha256("correct-verifier");
        String plainCode = "plain-code-value";
        seedApprovedRequest(plainCode, codeChallenge, Set.of("catalog:read"));
        seedActiveConnection(42L, 1L);

        TokenResult result = service.handle(new TokenRequestParams(
                "authorization_code", plainCode, REDIRECT_URI, "chatgpt-plugin", "wrong-verifier",
                null, null, null, null, null));

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, result);
        assertEquals("invalid_grant", error.error());
    }

    @Test
    @DisplayName("만료된 코드는 invalid_grant다")
    void handle_authorizationCode_expiredCode_rejected() {
        String codeVerifier = "verifier-value-1234567890123456789012345";
        String codeChallenge = base64UrlSha256(codeVerifier);
        String plainCode = "plain-code-value";
        long requestId = authorizationRequestRepository.insert(
                UUID.randomUUID(), 1L, REDIRECT_URI, "[\"catalog:read\"]", "state-1", codeChallenge, "S256",
                BASE_INSTANT.plus(Duration.ofMinutes(10)), BASE_INSTANT);
        authorizationRequestRepository.markApproved(
                requestId, 42L, sha256Hex(plainCode), BASE_INSTANT.minus(Duration.ofSeconds(1)), BASE_INSTANT);

        TokenResult result = service.handle(new TokenRequestParams(
                "authorization_code", plainCode, REDIRECT_URI, "chatgpt-plugin", codeVerifier,
                null, null, null, null, null));

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, result);
        assertEquals("invalid_grant", error.error());
    }

    @Test
    @DisplayName("refresh_token 교환은 회전된 새 refresh token과 새 access token을 반환한다")
    void handle_refreshToken_success() {
        long connectionId = connectionRepository.insert(
                UUID.randomUUID(), 42L, 1L, "[\"catalog:read\"]", BASE_INSTANT);
        seedUser(42L);
        IssuedAgentRefreshToken issued = refreshTokenService.issue(connectionId, "[\"catalog:read\"]");

        TokenResult result = service.handle(new TokenRequestParams(
                "refresh_token", null, null, "chatgpt-plugin", null, null, issued.plainToken(), null, null, null));

        TokenResult.Success success = assertInstanceOf(TokenResult.Success.class, result);
        assertEquals("catalog:read", success.scope());
    }

    @Test
    @DisplayName("연동이 해제된 뒤의 refresh_token 요청은 invalid_grant다")
    void handle_refreshToken_revokedConnection_rejected() {
        long connectionId = connectionRepository.insert(
                UUID.randomUUID(), 42L, 1L, "[\"catalog:read\"]", BASE_INSTANT);
        IssuedAgentRefreshToken issued = refreshTokenService.issue(connectionId, "[\"catalog:read\"]");
        connectionRepository.revoke(connectionId, BASE_INSTANT);

        TokenResult result = service.handle(new TokenRequestParams(
                "refresh_token", null, null, "chatgpt-plugin", null, null, issued.plainToken(), null, null, null));

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, result);
        assertEquals("invalid_grant", error.error());
    }

    @Test
    @DisplayName("정상 client_credentials 요청은 refresh token 없이 Service JWT만 발급한다")
    void handle_clientCredentials_success() {
        OauthClient confidentialClient = new OauthClient(
                2L, "copenhagen-mcp", null, "Copenhagen MCP Server", OauthClient.TYPE_CONFIDENTIAL,
                "[]", "[\"catalog:read\",\"cart:read\"]", null, OauthClient.STATUS_ACTIVE, "{\"keys\":[]}");
        clientRepository.add(confidentialClient);
        doNothing().when(assertionValidator).validate(any(), anyString(), anyString());

        TokenResult result = service.handle(new TokenRequestParams(
                "client_credentials", null, null, "copenhagen-mcp", null, null, null,
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "assertion-value", null));

        TokenResult.Success success = assertInstanceOf(TokenResult.Success.class, result);
        assertNull(success.refreshToken());
    }

    @Test
    @DisplayName("client_credentials가 허용되지 않은 scope를 요청하면 invalid_scope다")
    void handle_clientCredentials_invalidScope_rejected() {
        OauthClient confidentialClient = new OauthClient(
                2L, "copenhagen-mcp", null, "Copenhagen MCP Server", OauthClient.TYPE_CONFIDENTIAL,
                "[]", "[\"catalog:read\"]", null, OauthClient.STATUS_ACTIVE, "{\"keys\":[]}");
        clientRepository.add(confidentialClient);
        doNothing().when(assertionValidator).validate(any(), anyString(), anyString());

        TokenResult result = service.handle(new TokenRequestParams(
                "client_credentials", null, null, "copenhagen-mcp", null, null, null,
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "assertion-value", "purchase:handoff"));

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, result);
        assertEquals("invalid_scope", error.error());
    }

    @Test
    @DisplayName("assertion 검증 실패는 invalid_client다")
    void handle_clientCredentials_invalidAssertion_rejected() {
        OauthClient confidentialClient = new OauthClient(
                2L, "copenhagen-mcp", null, "Copenhagen MCP Server", OauthClient.TYPE_CONFIDENTIAL,
                "[]", "[\"catalog:read\"]", null, OauthClient.STATUS_ACTIVE, "{\"keys\":[]}");
        clientRepository.add(confidentialClient);
        doThrow(new InvalidClientAssertionException("bad signature"))
                .when(assertionValidator).validate(any(), anyString(), anyString());

        TokenResult result = service.handle(new TokenRequestParams(
                "client_credentials", null, null, "copenhagen-mcp", null, null, null,
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "assertion-value", null));

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, result);
        assertEquals("invalid_client", error.error());
    }

    @Test
    @DisplayName("지원하지 않는 grant_type은 unsupported_grant_type이다")
    void handle_unsupportedGrantType_rejected() {
        TokenResult result = service.handle(new TokenRequestParams(
                "password", null, null, null, null, null, null, null, null, null));

        TokenResult.Error error = assertInstanceOf(TokenResult.Error.class, result);
        assertEquals("unsupported_grant_type", error.error());
    }

    private void seedApprovedRequest(String plainCode, String codeChallenge, Set<String> scopes) {
        String scopesJson = "[\"" + String.join("\",\"", scopes) + "\"]";
        long requestId = authorizationRequestRepository.insert(
                UUID.randomUUID(), 1L, REDIRECT_URI, scopesJson, "state-1", codeChallenge, "S256",
                BASE_INSTANT.plus(Duration.ofMinutes(10)), BASE_INSTANT);
        authorizationRequestRepository.markApproved(
                requestId, 42L, sha256Hex(plainCode), BASE_INSTANT.plus(Duration.ofSeconds(60)), BASE_INSTANT);
    }

    private void seedActiveConnection(long userId, long oauthClientId) {
        connectionRepository.insert(UUID.randomUUID(), userId, oauthClientId, "[\"catalog:read\"]", BASE_INSTANT);
    }

    private void seedUser(long userId) {
        User user = new User(
                userId, UUID.randomUUID(), "tester", UserStatus.ACTIVE, null, null, false, null, null,
                null, null, null, BASE_INSTANT, BASE_INSTANT);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private static String base64UrlSha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
