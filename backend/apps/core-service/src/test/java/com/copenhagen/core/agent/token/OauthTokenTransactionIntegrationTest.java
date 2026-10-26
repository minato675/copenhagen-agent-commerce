package com.copenhagen.core.agent.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.copenhagen.core.agent.authorize.OauthAuthorizationRequestRepository;
import com.copenhagen.core.agent.connection.AgentConnection;
import com.copenhagen.core.agent.connection.AgentConnectionRepository;
import com.copenhagen.core.agent.connection.AgentConnectionService;
import com.copenhagen.core.agent.jwt.AgentJwtIssuer;
import com.copenhagen.core.agent.refreshtoken.AgentRefreshTokenService;
import com.copenhagen.core.auth.signup.SignupService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 테스트 자체에는 트랜잭션을 열지 않고 HTTP 진입점의 Spring 프록시와 PostgreSQL 커밋·롤백을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class OauthTokenTransactionIntegrationTest {

    private static final String CLIENT_ID = "transaction-test-client";
    private static final String REDIRECT_URI = "https://client.example.com/callback";
    private static final String SCOPES = "[\"catalog:read\"]";
    private static final String VERIFIER = "transaction-test-verifier-1234567890123456789012345";
    private static final String CODE = "transaction-test-authorization-code";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SignupService signupService;
    @Autowired
    private AgentConnectionRepository connections;
    @Autowired
    private AgentConnectionService connectionService;
    @Autowired
    private OauthAuthorizationRequestRepository requests;
    @Autowired
    private AgentRefreshTokenService refreshTokens;
    @Autowired
    private Clock clock;
    @MockitoSpyBean
    private AgentJwtIssuer jwtIssuer;

    private long clientId;
    private long userId;
    private long connectionId;

    @BeforeEach
    void seedAccountAndConnection() {
        jdbc.update("TRUNCATE users, merchants, oauth_clients, idempotency_records RESTART IDENTITY CASCADE");
        signupService.signup("oauthtxuser", "Password1!", "oauth-tx@example.com", "테스트");
        userId = jdbc.queryForObject("SELECT id FROM users", Long.class);
        clientId = jdbc.queryForObject("""
                INSERT INTO oauth_clients (client_id, client_name, client_type, redirect_uris, allowed_scopes)
                VALUES (?, 'Transaction test', 'PUBLIC', ?::jsonb, ?::jsonb) RETURNING id
                """, Long.class, CLIENT_ID, "[\"" + REDIRECT_URI + "\"]", SCOPES);
        connectionId = connections.insert(UUID.randomUUID(), userId, clientId, SCOPES, clock.instant());
    }

    @Test
    @DisplayName("인가 코드 교환 후 연속 갱신이 성공하고 최신 refresh token 하나만 활성으로 남는다")
    void exchange_thenRefreshTwice_commitsRotation() throws Exception {
        seedAuthorizationCode();
        String response = mockMvc.perform(codeRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String first = JsonPath.read(response, "$.refresh_token");
        String second = refresh(first);
        String third = refresh(second);

        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertEquals(3, countTokens());
        assertEquals(1, countActive(connectionId));
        mockMvc.perform(codeRequest()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @DisplayName("미등록 refresh token은 rollback-only 오류 없이 OAuth invalid_grant로 응답한다")
    void refresh_unknownToken_returnsInvalidGrant() throws Exception {
        refreshTokens.issue(connectionId, SCOPES);
        assertInvalidGrant("never-issued-test-token");
        assertEquals(1, countActive(connectionId));
    }

    @Test
    @DisplayName("만료된 refresh token은 500 대신 invalid_grant로 응답한다")
    void refresh_expiredToken_returnsInvalidGrant() throws Exception {
        String token = refreshTokens.issue(connectionId, SCOPES).plainToken();
        jdbc.update("UPDATE agent_refresh_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'");
        assertInvalidGrant(token);
        assertEquals(1, countTokens());
    }

    @Test
    @DisplayName("회전한 토큰을 재사용하면 해당 연동만 폐기하고 invalid_grant로 응답한다")
    void refresh_reusedToken_commitsBreachRevocation() throws Exception {
        String token = refreshTokens.issue(connectionId, SCOPES).plainToken();
        refresh(token);
        long otherClient = jdbc.queryForObject("""
                INSERT INTO oauth_clients (client_id, client_name, client_type, redirect_uris, allowed_scopes)
                VALUES ('other-test-client', 'Other test', 'PUBLIC', '[]', ?::jsonb) RETURNING id
                """, Long.class, SCOPES);
        long otherConnection = connections.insert(UUID.randomUUID(), userId, otherClient, SCOPES, clock.instant());
        refreshTokens.issue(otherConnection, SCOPES);

        assertInvalidGrant(token);

        assertEquals(0, countActive(connectionId));
        assertEquals(1, countActive(otherConnection));
    }

    @Test
    @DisplayName("연결 해제 후 갱신은 토큰 회전 없이 invalid_grant로 응답한다")
    void refresh_revokedConnection_doesNotRotate() throws Exception {
        String token = refreshTokens.issue(connectionId, SCOPES).plainToken();
        connections.revoke(connectionId, clock.instant());
        assertInvalidGrant(token);
        assertEquals(1, countTokens());
    }

    @Test
    @DisplayName("갱신 중 서명 실패 시 기존 토큰 폐기와 새 토큰 삽입이 함께 롤백된다")
    void refresh_signingFailure_rollsBackRotation() throws Exception {
        String token = refreshTokens.issue(connectionId, SCOPES).plainToken();
        failSigning();

        mockMvc.perform(refreshRequest(token)).andExpect(status().isInternalServerError());

        assertEquals(1, countTokens());
        assertEquals(1, countActive(connectionId));
    }

    @Test
    @DisplayName("인가 코드 교환 중 서명 실패 시 인가 코드 소비가 롤백된다")
    void exchange_signingFailure_rollsBackCodeConsumption() throws Exception {
        seedAuthorizationCode();
        failSigning();

        mockMvc.perform(codeRequest()).andExpect(status().isInternalServerError());

        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM oauth_authorization_requests", String.class));
        assertEquals(0, countTokens());
    }

    @Test
    @DisplayName("같은 scope 재인가는 연동을 유지해 먼저 연동한 기기의 refresh token이 계속 갱신된다")
    void reauthorizeSameScopes_keepsOtherDeviceTokenUsable() throws Exception {
        seedAuthorizationCode();
        String deviceA = JsonPath.read(mockMvc.perform(codeRequest())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refresh_token");

        AgentConnection reused = connectionService.upsertOnAuthorization(userId, clientId, Set.of("catalog:read"));

        assertEquals(connectionId, reused.id());
        assertEquals(1, countActiveConnections());
        // 기기 A는 재인가 이후에도 재로그인 없이 갱신된다.
        assertNotEquals(deviceA, refresh(deviceA));
        assertEquals(1, countActive(connectionId));
    }

    @Test
    @DisplayName("scope가 달라진 재인가는 연동을 폐기해 먼저 연동한 기기의 refresh token을 무효화한다")
    void reauthorizeChangedScopes_invalidatesOtherDeviceToken() throws Exception {
        seedAuthorizationCode();
        String deviceA = JsonPath.read(mockMvc.perform(codeRequest())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refresh_token");

        AgentConnection replaced =
                connectionService.upsertOnAuthorization(userId, clientId, Set.of("catalog:read", "cart:read"));

        assertNotEquals(connectionId, replaced.id());
        assertEquals(1, countActiveConnections());
        assertInvalidGrant(deviceA);
        assertEquals(0, countActive(connectionId));
    }

    private void failSigning() {
        doThrow(new IllegalStateException("Test signing failure"))
                .when(jwtIssuer).issueAgentAccessToken(any(), anyString(), any());
    }

    private void seedAuthorizationCode() throws Exception {
        Instant now = clock.instant();
        byte[] challenge = MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII));
        long requestId = requests.insert(UUID.randomUUID(), clientId, REDIRECT_URI, SCOPES, "test-state",
                Base64.getUrlEncoder().withoutPadding().encodeToString(challenge), "S256", now.plusSeconds(600), now);
        byte[] codeHash = MessageDigest.getInstance("SHA-256").digest(CODE.getBytes(StandardCharsets.UTF_8));
        requests.markApproved(requestId, userId, HexFormat.of().formatHex(codeHash), now.plusSeconds(60), now);
    }

    private MockHttpServletRequestBuilder codeRequest() {
        return post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code").param("client_id", CLIENT_ID)
                .param("redirect_uri", REDIRECT_URI).param("code", CODE).param("code_verifier", VERIFIER);
    }

    private MockHttpServletRequestBuilder refreshRequest(String token) {
        return post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token").param("client_id", CLIENT_ID).param("refresh_token", token);
    }

    private String refresh(String token) throws Exception {
        String response = mockMvc.perform(refreshRequest(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.expires_in").value(300))
                .andExpect(jsonPath("$.scope").value("catalog:read"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.refresh_token");
    }

    private void assertInvalidGrant(String token) throws Exception {
        mockMvc.perform(refreshRequest(token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    private int countTokens() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_refresh_tokens", Integer.class);
    }

    private int countActiveConnections() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_connections WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }

    private int countActive(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_refresh_tokens WHERE connection_id = ? AND revoked_at IS NULL",
                Integer.class, id);
    }
}
