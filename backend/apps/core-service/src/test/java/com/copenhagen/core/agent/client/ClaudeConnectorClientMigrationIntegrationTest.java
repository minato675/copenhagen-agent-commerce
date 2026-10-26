package com.copenhagen.core.agent.client;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 공유 테스트 테이블을 초기화하지 않고 테스트마다 고유 스키마에서 업그레이드를 검증한다. */
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class ClaudeConnectorClientMigrationIntegrationTest {

    private static final String CLIENT_ID = "claude-connector";
    private static final String CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    private static final List<String> SCOPES =
            List.of("catalog:read", "cart:read", "cart:write", "purchase:handoff");

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        schema = "claude_client_" + UUID.randomUUID().toString().replace("-", "");
        String baseUrl = System.getenv("CORE_TEST_DB_URL");
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        dataSource = new DriverManagerDataSource(
                url, System.getenv("CORE_TEST_DB_USER"), System.getenv("CORE_TEST_DB_PASSWORD"));
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanUp() {
        // 이 테스트가 만든 스키마 하나만 제거한다. public 및 다른 테스트 데이터에는 접근하지 않는다.
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .cleanDisabled(false).load().clean();
    }

    @Test
    @DisplayName("빈 DB에 전체 migration을 적용하면 Claude 커넥터를 PKCE 공개 클라이언트로 등록한다")
    void migrate_emptyDatabase_registersClaudeConnector() {
        migrate("20");

        OauthClient client = new JdbcOauthClientRepository(jdbcTemplate).findByClientId(CLIENT_ID).orElseThrow();
        assertEquals("PUBLIC", client.clientType());
        assertEquals("ACTIVE", client.status());
        assertEquals(List.of(CALLBACK), strings(client.redirectUris()));
        assertEquals(SCOPES, strings(client.allowedScopes()));
    }

    @Test
    @DisplayName("운영에서 먼저 등록한 행과 chatgpt-plugin 을 보존하고 재적용해도 바꾸지 않는다")
    void migrate_existingRows_arePreserved() {
        migrate("18");
        jdbcTemplate.update("""
                INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, client_type,
                                           redirect_uris, allowed_scopes, logo_url, status)
                VALUES (?, NULL, 'Claude', 'PUBLIC', ?::jsonb, '["catalog:read"]'::jsonb, NULL, 'SUSPENDED')
                """, CLIENT_ID, new ObjectMapper().writeValueAsString(List.of(CALLBACK)));
        String chatgptBefore = row("chatgpt-plugin");

        migrate("20");
        migrate("20");

        OauthClient client = new JdbcOauthClientRepository(jdbcTemplate).findByClientId(CLIENT_ID).orElseThrow();
        assertEquals("SUSPENDED", client.status());
        assertEquals(List.of("catalog:read"), strings(client.allowedScopes()));
        assertEquals(chatgptBefore, row("chatgpt-plugin"));
    }

    private List<String> strings(String json) {
        return new ObjectMapper().readValue(json, new TypeReference<List<String>>() { });
    }

    private void migrate(String version) {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(version).load().migrate();
    }

    private String row(String clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT row_to_json(c)::text FROM oauth_clients c WHERE client_id = ?", String.class, clientId);
    }
}
