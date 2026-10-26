package com.copenhagen.core.internalapi.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.AgentScopes;
import com.copenhagen.core.agent.client.FakeOauthClientRepository;
import com.copenhagen.core.agent.client.OauthClient;
import com.copenhagen.core.agent.connection.FakeAgentConnectionRepository;
import com.copenhagen.core.agent.jwt.AgentJwtIssuer;
import com.copenhagen.core.agent.jwt.AgentJwtKeySet;
import com.copenhagen.core.agent.jwt.AgentOauthProperties;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.internalapi.error.InternalApiErrorWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 설계서 §3.2·§3.4 — Service JWT + Agent JWT 이중 검증과 실패 시 상태코드·code 필드. */
class InternalApiAuthenticationFilterTest {

    private static final String ISSUER = "https://core.example";
    private static final String AUDIENCE = "https://core.example/mcp";
    private static final String CLIENT_ID = "copenhagen-mcp";
    private static final String CART_PATH = "/internal/v1/carts/current";
    private static final String ITEMS_PATH = "/internal/v1/carts/current/items";
    private static final String ALL_SCOPES_JSON =
            "[\"catalog:read\",\"cart:read\",\"cart:write\",\"purchase:handoff\"]";
    private static final Instant NOW = Instant.parse("2026-09-18T05:00:00Z");
    private static final long USER_ID = 42L;

    private final UUID userPublicId = UUID.randomUUID();
    private final UUID connectionPublicId = UUID.randomUUID();

    private AgentJwtKeySet keySet;
    private AgentJwtIssuer issuer;
    private FakeOauthClientRepository clientRepository;
    private FakeAgentConnectionRepository connectionRepository;
    private UserRepository userRepository;
    private InternalApiAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        keySet = new AgentJwtKeySet("", "agent-oauth-test");
        issuer = new AgentJwtIssuer(
                keySet, new AgentOauthProperties(ISSUER, AUDIENCE), Clock.fixed(NOW, ZoneOffset.UTC));
        clientRepository = new FakeOauthClientRepository();
        clientRepository.add(client(OauthClient.STATUS_ACTIVE, OauthClient.TYPE_CONFIDENTIAL));
        connectionRepository = new FakeAgentConnectionRepository();
        connectionRepository.insert(connectionPublicId, USER_ID, 1L, ALL_SCOPES_JSON, NOW);
        userRepository = mock(UserRepository.class);
        when(userRepository.findByPublicId(any())).thenReturn(Optional.empty());
        when(userRepository.findByPublicId(userPublicId)).thenReturn(Optional.of(user()));
        rebuildFilter();
    }

    @Test
    @DisplayName("두 토큰이 모두 유효하면 사용자·연동·scope를 컨텍스트에 담아 통과시킨다")
    void validTokens_passRequest() throws Exception {
        MockHttpServletRequest request = authenticated("GET", CART_PATH);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        InternalRequestContext context = InternalRequestContext.require(request);
        assertEquals(USER_ID, context.userId());
        assertEquals(userPublicId, context.userPublicId());
        assertEquals(1L, context.connectionId());
        assertTrue(context.scopes().contains(AgentScopes.CART_READ));
        assertNull(context.idempotencyKey());
    }

    @Test
    @DisplayName("내부 경로가 아니면 필터가 아무 것도 하지 않는다")
    void appPath_isNotFiltered() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/core/v1/cart");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Service JWT가 없으면 401 UNAUTHENTICATED")
    void missingServiceToken_isUnauthenticated() throws Exception {
        MockHttpServletRequest request = request("GET", CART_PATH);
        request.addHeader("X-Agent-Authorization", bearer(agentToken()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Service JWT의 client가 비활성이면 401")
    void inactiveClient_isUnauthenticated() throws Exception {
        clientRepository = new FakeOauthClientRepository();
        clientRepository.add(client("REVOKED", OauthClient.TYPE_CONFIDENTIAL));
        rebuildFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authenticated("GET", CART_PATH), response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Service JWT의 client가 PUBLIC이면 401")
    void publicClient_isUnauthenticated() throws Exception {
        clientRepository = new FakeOauthClientRepository();
        clientRepository.add(client(OauthClient.STATUS_ACTIVE, OauthClient.TYPE_PUBLIC));
        rebuildFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authenticated("GET", CART_PATH), response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Agent JWT가 없으면 401")
    void missingAgentToken_isUnauthenticated() throws Exception {
        MockHttpServletRequest request = request("GET", CART_PATH);
        request.addHeader("Authorization", bearer(serviceToken()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("connection_id가 없는 Service JWT를 위임 토큰 자리에 쓰면 401")
    void serviceTokenAsAgentToken_isUnauthenticated() throws Exception {
        MockHttpServletRequest request = request("GET", CART_PATH);
        request.addHeader("Authorization", bearer(serviceToken()));
        request.addHeader("X-Agent-Authorization", bearer(serviceToken()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("만료된 Agent JWT는 401")
    void expiredAgentToken_isUnauthenticated() throws Exception {
        AgentJwtIssuer expiredIssuer = new AgentJwtIssuer(
                keySet,
                new AgentOauthProperties(ISSUER, AUDIENCE),
                Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withAgentToken(agentToken(expiredIssuer)), response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("audience가 다른 토큰은 401")
    void audienceMismatch_isUnauthenticated() throws Exception {
        AgentJwtIssuer otherIssuer = new AgentJwtIssuer(
                keySet,
                new AgentOauthProperties(ISSUER, "https://other.example/mcp"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withAgentToken(agentToken(otherIssuer)), response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401")
    void forgedSignature_isUnauthenticated() throws Exception {
        // 키 id는 같지만 실제 키쌍이 다르다 — 서명 검증만이 걸러낼 수 있는 경우다.
        AgentJwtIssuer forgedIssuer = new AgentJwtIssuer(
                new AgentJwtKeySet("", "agent-oauth-test"),
                new AgentOauthProperties(ISSUER, AUDIENCE),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withAgentToken(agentToken(forgedIssuer)), response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("issuer가 다른 토큰은 401")
    void issuerMismatch_isUnauthenticated() throws Exception {
        AgentJwtIssuer otherIssuer = new AgentJwtIssuer(
                keySet,
                new AgentOauthProperties("https://other.example", AUDIENCE),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withAgentToken(agentToken(otherIssuer)), response, new MockFilterChain());

        assertRejected(response, 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("연동이 해제됐으면 403 ACCESS_DENIED")
    void revokedConnection_isForbidden() throws Exception {
        connectionRepository.revoke(1L, NOW);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authenticated("GET", CART_PATH), response, new MockFilterChain());

        assertRejected(response, 403, "ACCESS_DENIED");
    }

    @Test
    @DisplayName("연동이 동의받지 않은 scope는 토큰에 있어도 403")
    void scopeNotGranted_isForbidden() throws Exception {
        connectionRepository = new FakeAgentConnectionRepository();
        connectionRepository.insert(connectionPublicId, USER_ID, 1L, "[\"catalog:read\"]", NOW);
        rebuildFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authenticated("GET", CART_PATH), response, new MockFilterChain());

        assertRejected(response, 403, "ACCESS_DENIED");
    }

    @Test
    @DisplayName("쓰기 요청에 Idempotency-Key가 없으면 400 INVALID_ARGUMENT")
    void missingIdempotencyKey_isInvalidArgument() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authenticated("POST", ITEMS_PATH), response, new MockFilterChain());

        assertRejected(response, 400, "INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("UUID가 아닌 Idempotency-Key는 400")
    void malformedIdempotencyKey_isInvalidArgument() throws Exception {
        MockHttpServletRequest request = authenticated("POST", ITEMS_PATH);
        request.addHeader("Idempotency-Key", "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertRejected(response, 400, "INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("쓰기 요청의 멱등키는 컨텍스트로 전달된다")
    void writeRequest_carriesIdempotencyKey() throws Exception {
        UUID key = UUID.randomUUID();
        MockHttpServletRequest request = authenticated("POST", ITEMS_PATH);
        request.addHeader("Idempotency-Key", key.toString());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertEquals(key, InternalRequestContext.require(request).requireIdempotencyKey());
    }

    private void rebuildFilter() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentOauthProperties properties = new AgentOauthProperties(ISSUER, AUDIENCE);
        filter = new InternalApiAuthenticationFilter(
                new InternalApiTokenVerifier(keySet, properties, Clock.fixed(NOW, ZoneOffset.UTC)),
                clientRepository,
                userRepository,
                connectionRepository,
                new InternalApiErrorWriter(objectMapper),
                objectMapper);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private MockHttpServletRequest authenticated(String method, String path) {
        MockHttpServletRequest request = request(method, path);
        request.addHeader("Authorization", bearer(serviceToken()));
        request.addHeader("X-Agent-Authorization", bearer(agentToken()));
        return request;
    }

    private MockHttpServletRequest withAgentToken(String agentToken) {
        MockHttpServletRequest request = request("GET", CART_PATH);
        request.addHeader("Authorization", bearer(serviceToken()));
        request.addHeader("X-Agent-Authorization", bearer(agentToken));
        return request;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String serviceToken() {
        return issuer.issueServiceToken(CLIENT_ID, AgentScopes.ALL).token();
    }

    private String agentToken() {
        return agentToken(issuer);
    }

    private String agentToken(AgentJwtIssuer tokenIssuer) {
        return tokenIssuer.issueAgentAccessToken(
                userPublicId, connectionPublicId.toString(), AgentScopes.ALL).token();
    }

    private void assertRejected(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        assertTrue(response.getContentAsString().contains("\"code\":\"" + code + "\""),
                response.getContentAsString());
    }

    private OauthClient client(String status, String clientType) {
        return new OauthClient(
                1L, CLIENT_ID, null, "Copenhagen MCP", clientType, "[]",
                ALL_SCOPES_JSON, null, status, null);
    }

    private User user() {
        return new User(
                USER_ID, userPublicId, "사용자", UserStatus.ACTIVE, null, "hash", true,
                null, null, null, null, null, NOW, NOW);
    }
}
