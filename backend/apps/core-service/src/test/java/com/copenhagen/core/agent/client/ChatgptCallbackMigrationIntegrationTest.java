package com.copenhagen.core.agent.client;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 공유 테스트 테이블을 초기화하지 않고 테스트마다 고유 스키마에서 업그레이드를 검증한다. */
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class ChatgptCallbackMigrationIntegrationTest {

    private static final String CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";
    private static final String EXISTING_CALLBACK = "https://chatgpt.com/connector/oauth/test-existing";

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        schema = "oauth_callback_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("빈 DB에 전체 migration을 적용하면 ChatGPT 고정 콜백을 허용한다")
    void migrate_emptyDatabase_registersStableCallback() {
        migrate("14");

        List<String> callbacks = callbacks();
        assertTrue(callbacks.contains(CALLBACK));
        assertEquals(1, callbacks.stream().filter(CALLBACK::equals).count());
        OauthClient serviceClient = new JdbcOauthClientRepository(jdbcTemplate)
                .findByClientId("copenhagen-mcp").orElseThrow();
        assertEquals("[]", serviceClient.redirectUris());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("기존 콜백과 정지 상태를 보존하고 사전 등록된 고정 콜백은 중복 추가하지 않는다")
    void migrate_existingClient_preservesSettingsWithoutDuplicates(boolean alreadyRegistered) {
        migrate("13");
        String redirects = new ObjectMapper().writeValueAsString(alreadyRegistered
                ? List.of(EXISTING_CALLBACK, CALLBACK) : List.of(EXISTING_CALLBACK));
        jdbcTemplate.update("""
                UPDATE oauth_clients
                SET redirect_uris = ?::jsonb, allowed_scopes = '["catalog:read"]'::jsonb, status = 'SUSPENDED'
                WHERE client_id = 'chatgpt-plugin'
                """, redirects);
        String serviceClientBefore = jdbcTemplate.queryForObject(
                "SELECT row_to_json(c)::text FROM oauth_clients c WHERE client_id = 'copenhagen-mcp'", String.class);

        migrate("14");
        migrate("14");

        assertEquals(List.of(EXISTING_CALLBACK, CALLBACK), callbacks());
        OauthClient client = new JdbcOauthClientRepository(jdbcTemplate).findByClientId("chatgpt-plugin").orElseThrow();
        assertEquals("PUBLIC", client.clientType());
        assertEquals("SUSPENDED", client.status());
        assertEquals("[\"catalog:read\"]", client.allowedScopes());
        assertEquals(serviceClientBefore, jdbcTemplate.queryForObject(
                "SELECT row_to_json(c)::text FROM oauth_clients c WHERE client_id = 'copenhagen-mcp'", String.class));
    }

    private void migrate(String version) {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(version).load().migrate();
    }

    private List<String> callbacks() {
        String json = new JdbcOauthClientRepository(jdbcTemplate)
                .findByClientId("chatgpt-plugin").orElseThrow().redirectUris();
        return new ObjectMapper().readValue(json, new TypeReference<List<String>>() { });
    }
}
