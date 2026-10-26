package com.copenhagen.core.internalapi;

import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.AgentScopes;
import com.copenhagen.core.agent.client.OauthClientRepository;
import com.copenhagen.core.agent.connection.AgentConnectionRepository;
import com.copenhagen.core.agent.jwt.AgentJwtIssuer;
import com.copenhagen.core.auth.signup.SignupService;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.CatalogProductView;
import com.copenhagen.core.product.CatalogSearchItem;
import com.copenhagen.core.product.CatalogSearchResult;
import com.copenhagen.core.product.ProductService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 순서 34(이슈 S15P21A207-417) — 실 PostgreSQL로 내부 API 7건을 실제 토큰으로 왕복한다.
 *
 * <p>Merchant Catalog 호출만 대역으로 끊는다. V15 마이그레이션, 인증 필터, 멱등성 저장, 장바구니 제약,
 * 구매 인계 적재는 전부 실제로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class InternalApiIntegrationTest {

    private static final String PASSWORD = "Password1!";
    private static final String MERCHANT_ID = "merchant-a";
    private static final String PRODUCT_ID = "prod-1001";
    private static final String CART_PATH = "/internal/v1/carts/current";
    private static final String ITEMS_PATH = CART_PATH + "/items";
    private static final String PURCHASE_HISTORY_PATH = "/internal/v1/purchase-history";
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentJwtIssuer jwtIssuer;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OauthClientRepository clientRepository;

    @Autowired
    private AgentConnectionRepository connectionRepository;

    @MockitoBean
    private ProductService productService;

    private String serviceToken;
    private String agentToken;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("""
                TRUNCATE users, carts, cart_items, purchase_requests, internal_idempotency_keys,
                    agent_connections
                RESTART IDENTITY CASCADE
                """);
        // merchants 는 V7 시드다. 다른 통합 테스트가 TRUNCATE 하고 갈 수 있어 멱등하게 되살린다.
        jdbcTemplate.update("""
                INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                                       active_capabilities, status)
                VALUES (1, 'Copenhagen Demo Store', 'http://ucp-merchant-mock:8080/.well-known/ucp',
                        'http://ucp-merchant-mock:8080', '{}'::jsonb, '[]'::jsonb, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """);
        // copenhagen-mcp 는 V10 시드다. 다른 통합 테스트가 oauth_clients 를 TRUNCATE 하고 갈 수 있어
        // 멱등하게 되살린다(client_jwks 는 운영에서만 채우는 값이라 여기서도 NULL이다).
        jdbcTemplate.update("""
                INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, client_type,
                                           redirect_uris, allowed_scopes, logo_url, status, client_jwks)
                VALUES ('copenhagen-mcp', NULL, 'Copenhagen MCP Server', 'CONFIDENTIAL', '[]'::jsonb,
                        '["catalog:read","cart:read","cart:write","purchase:handoff"]'::jsonb,
                        NULL, 'ACTIVE', NULL)
                ON CONFLICT (client_id) DO NOTHING
                """);

        String loginId = "mcpuser1";
        signupService.signup(loginId, PASSWORD, loginId + "@example.com", "홍길동");
        long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM local_accounts WHERE login_id = ?", Long.class, loginId);
        UUID userPublicId = userRepository.findById(userId).orElseThrow().publicId();

        long clientId = clientRepository.findByClientId("copenhagen-mcp").orElseThrow().id();
        UUID connectionPublicId = UUID.randomUUID();
        connectionRepository.insert(
                connectionPublicId, userId, clientId,
                "[\"catalog:read\",\"cart:read\",\"cart:write\",\"purchase:handoff\"]",
                java.time.Instant.now());

        serviceToken = jwtIssuer.issueServiceToken("copenhagen-mcp", AgentScopes.ALL).token();
        agentToken = jwtIssuer
                .issueAgentAccessToken(userPublicId, connectionPublicId.toString(), AgentScopes.ALL).token();

        stubCatalog();
    }

    @Test
    @DisplayName("V15 마이그레이션이 적용돼 멱등키 테이블과 PENDING_ADDRESS 상태가 있다")
    void migration_v15_isApplied() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '15' AND success = true
                """, Integer.class);
        assertEquals(1, applied);

        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'purchase_requests' AND column_name IN ('cart_version', 'expires_at')
                """, Integer.class);
        assertEquals(2, columns);

        Integer table = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'internal_idempotency_keys'
                """, Integer.class);
        assertEquals(1, table);
    }

    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHENTICATED이고 바디는 code 필드다")
    void withoutTokens_isUnauthenticated() throws Exception {
        mockMvc.perform(get(CART_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    @DisplayName("검색 → 상세 → 담기 → 수량변경 → 옵션변경 → 삭제 → 구매 인계까지 한 번에 돈다")
    void fullRoundTrip() throws Exception {
        String search = perform(authenticated(get("/internal/v1/catalog/products").param("query", "갤럭시")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        McpContractAssertions.assertSearch(objectMapper.readValue(search, JSON_OBJECT));

        String detail = perform(authenticated(
                get("/internal/v1/merchants/{merchantId}/products/{productId}", MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        McpContractAssertions.assertDetail(objectMapper.readValue(detail, JSON_OBJECT));

        String empty = perform(authenticated(get(CART_PATH)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(empty, "$.cartId");
        int version = JsonPath.read(empty, "$.version");
        assertNotNull(cartId);

        String added = perform(write(post(ITEMS_PATH), UUID.randomUUID())
                .content("""
                        {"cartVersion":%d,"merchantId":"%s","merchantProductId":"%s",
                         "merchantVariantId":"sku-black","quantity":2}
                        """.formatted(version, MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andReturn().getResponse().getContentAsString();
        McpContractAssertions.assertCart(objectMapper.readValue(added, JSON_OBJECT));
        String cartItemId = JsonPath.read(added, "$.items[0].cartItemId");
        int addedVersion = JsonPath.read(added, "$.version");

        String quantityChanged = perform(write(patch(ITEMS_PATH + "/" + cartItemId), UUID.randomUUID())
                .content("""
                        {"cartVersion":%d,"merchantVariantId":"sku-black","quantity":3}
                        """.formatted(addedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andReturn().getResponse().getContentAsString();
        int quantityVersion = JsonPath.read(quantityChanged, "$.version");

        String variantChanged = perform(write(patch(ITEMS_PATH + "/" + cartItemId), UUID.randomUUID())
                .content("""
                        {"cartVersion":%d,"merchantVariantId":"sku-white","quantity":3}
                        """.formatted(quantityVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].merchantVariantId").value("sku-white"))
                .andExpect(jsonPath("$.items[0].selectedOptions[0].value").value("화이트"))
                .andReturn().getResponse().getContentAsString();
        McpContractAssertions.assertCart(objectMapper.readValue(variantChanged, JSON_OBJECT));
        int variantVersion = JsonPath.read(variantChanged, "$.version");
        String variantItemId = JsonPath.read(variantChanged, "$.items[0].cartItemId");

        String removed = perform(write(
                delete(ITEMS_PATH + "/" + variantItemId).param("cartVersion", String.valueOf(variantVersion)),
                UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        int removedVersion = JsonPath.read(removed, "$.version");

        String readded = perform(write(post(ITEMS_PATH), UUID.randomUUID())
                .content("""
                        {"cartVersion":%d,"merchantId":"%s","merchantProductId":"%s",
                         "merchantVariantId":"sku-black","quantity":1}
                        """.formatted(removedVersion, MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int readdedVersion = JsonPath.read(readded, "$.version");

        String handoff = perform(write(post("/internal/v1/purchase-requests"), UUID.randomUUID())
                .content("""
                        {"cartId":"%s","cartVersion":%d}
                        """.formatted(cartId, readdedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_ADDRESS"))
                .andExpect(jsonPath("$.nextAction").value("SELECT_SHIPPING_ADDRESS"))
                .andReturn().getResponse().getContentAsString();
        String purchaseRequestId = JsonPath.read(handoff, "$.purchaseRequestId");
        assertFalse(objectMapper.readTree(handoff).has("appLink"));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT status, request_channel, cart_version, expires_at, agent_connection_id
                FROM purchase_requests WHERE public_id = ?::uuid
                """, purchaseRequestId);
        assertEquals("PENDING_ADDRESS", row.get("status"));
        assertEquals("MCP", row.get("request_channel"));
        assertEquals(readdedVersion, ((Number) row.get("cart_version")).intValue());
        assertNotNull(row.get("expires_at"));
        assertNotNull(row.get("agent_connection_id"));
    }

    @Test
    @DisplayName("같은 멱등키 재전송은 장바구니를 두 번 바꾸지 않는다")
    void sameIdempotencyKey_appliesOnce() throws Exception {
        String empty = perform(authenticated(get(CART_PATH)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(empty, "$.version");
        UUID key = UUID.randomUUID();
        String body = """
                {"cartVersion":%d,"merchantId":"%s","merchantProductId":"%s",
                 "merchantVariantId":"sku-black","quantity":2}
                """.formatted(version, MERCHANT_ID, PRODUCT_ID);

        String first = perform(write(post(ITEMS_PATH), key).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = perform(write(post(ITEMS_PATH), key).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // response_body가 JSONB라 Postgres가 키 순서를 정규화한다. 문자열이 아니라 값이 같아야 한다.
        assertEquals(objectMapper.readValue(first, JSON_OBJECT), objectMapper.readValue(second, JSON_OBJECT));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart_items", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM internal_idempotency_keys", Integer.class));
    }

    @Test
    @DisplayName("같은 키로 다른 본문을 보내면 409 IDEMPOTENCY_KEY_CONFLICT")
    void sameKeyDifferentBody_isConflict() throws Exception {
        String empty = perform(authenticated(get(CART_PATH)))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(empty, "$.version");
        UUID key = UUID.randomUUID();

        perform(write(post(ITEMS_PATH), key).content("""
                {"cartVersion":%d,"merchantId":"%s","merchantProductId":"%s",
                 "merchantVariantId":"sku-black","quantity":2}
                """.formatted(version, MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isOk());

        perform(write(post(ITEMS_PATH), key).content("""
                {"cartVersion":%d,"merchantId":"%s","merchantProductId":"%s",
                 "merchantVariantId":"sku-black","quantity":5}
                """.formatted(version, MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("연동을 해제하면 같은 토큰으로도 403 ACCESS_DENIED")
    void revokedConnection_isForbidden() throws Exception {
        jdbcTemplate.update("UPDATE agent_connections SET revoked_at = CURRENT_TIMESTAMP");

        perform(authenticated(get(CART_PATH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("버전이 어긋난 변경은 409 CART_VERSION_CONFLICT")
    void staleVersion_isConflict() throws Exception {
        perform(authenticated(get(CART_PATH))).andExpect(status().isOk());

        perform(write(post(ITEMS_PATH), UUID.randomUUID()).content("""
                {"cartVersion":99,"merchantId":"%s","merchantProductId":"%s",
                 "merchantVariantId":"sku-black","quantity":1}
                """.formatted(MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("빈 장바구니로 구매 인계를 요청하면 409 CART_EMPTY")
    void emptyCartHandoff_isCartEmpty() throws Exception {
        String empty = perform(authenticated(get(CART_PATH)))
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(empty, "$.cartId");
        int version = JsonPath.read(empty, "$.version");

        perform(write(post("/internal/v1/purchase-requests"), UUID.randomUUID()).content("""
                {"cartId":"%s","cartVersion":%d}
                """.formatted(cartId, version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
    }

    @Test
    @DisplayName("업무 처리가 실패하면 멱등키 행도 남지 않는다(같은 트랜잭션)")
    void businessFailure_rollsBackIdempotencyRow() throws Exception {
        String empty = perform(authenticated(get(CART_PATH)))
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(empty, "$.cartId");
        int version = JsonPath.read(empty, "$.version");
        UUID key = UUID.randomUUID();

        perform(write(post("/internal/v1/purchase-requests"), key).content("""
                {"cartId":"%s","cartVersion":%d}
                """.formatted(cartId, version)))
                .andExpect(status().isConflict());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM internal_idempotency_keys", Integer.class));
        // 같은 키를 다시 쓸 수 있어야 한다 — 실패한 요청의 키가 잠기면 재시도가 막힌다.
        perform(write(post(ITEMS_PATH), key).content("""
                {"cartVersion":%d,"merchantId":"%s","merchantProductId":"%s",
                 "merchantVariantId":"sku-black","quantity":1}
                """.formatted(version, MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("수량 상한(99)을 넘는 입력은 400 INVALID_ARGUMENT")
    void quantityOverSchemaMaximum_isInvalidArgument() throws Exception {
        String empty = perform(authenticated(get(CART_PATH)))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(empty, "$.version");

        perform(write(post(ITEMS_PATH), UUID.randomUUID()).content("""
                {"cartVersion":%d,"merchantId":"%s","merchantProductId":"%s",
                 "merchantVariantId":"sku-black","quantity":100}
                """.formatted(version, MERCHANT_ID, PRODUCT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("응답에 Set-Cookie 같은 불필요한 헤더가 없다")
    void response_hasNoSessionHeaders() throws Exception {
        var response = perform(authenticated(get(CART_PATH)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertEquals(0, response.getCookies().length);
        assertEquals(null, response.getHeader("Set-Cookie"));
        assertEquals(null, response.getHeader("Authorization"));
    }

    @Test
    @DisplayName("잘못된 Content-Type은 502가 아니라 400 INVALID_ARGUMENT다")
    void unsupportedMediaType_isInvalidArgument() throws Exception {
        perform(authenticated(post(ITEMS_PATH))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.TEXT_PLAIN)
                .content("nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 400 INVALID_ARGUMENT다")
    void malformedBody_isInvalidArgument() throws Exception {
        perform(write(post(ITEMS_PATH), UUID.randomUUID()).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("매핑되지 않은 메서드는 405다(바디는 Core 오류 형식이 아님 — Core 전역 이슈)")
    void methodNotAllowed_is405() throws Exception {
        // 매핑 단계 예외는 @RestControllerAdvice(basePackages) 셀렉터를 타지 않아 advice가 적용되지 않는다.
        // Core 전 엔드포인트에 같은 성질이 있고(순서 32·35에서 이미 관찰) 별도 fix 이슈 대상이다.
        // mcp-server는 405를 CORE_SERVICE_ERROR로 분류하므로 이 단계에서 계약상 위험은 없다.
        perform(write(post(CART_PATH), UUID.randomUUID()).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("구매내역이 없어도 200과 빈 페이지를 주고 배송지·결제 정보는 싣지 않는다(순서 43)")
    void purchaseHistory_returnsEmptyPageWithoutPii() throws Exception {
        String body = perform(authenticated(get(PURCHASE_HISTORY_PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.periodStart").exists())
                .andReturn().getResponse().getContentAsString();

        // 마지막 페이지에서도 키는 남기고 값만 null이다 — mcp-server가 키 존재를 검증한다(설계서 §4.2).
        assertTrue(body.contains("\"nextCursor\":null"));
        assertFalse(body.contains("recipient"));
        assertFalse(body.contains("address"));
        assertFalse(body.contains("paymentLabel"));
    }

    @Test
    @DisplayName("pageSize가 범위를 벗어나면 400 INVALID_ARGUMENT다(순서 43)")
    void purchaseHistory_rejectsPageSizeOutOfRange() throws Exception {
        perform(authenticated(get(PURCHASE_HISTORY_PATH).param("pageSize", "51")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("결제 완료 주문이 있으면 계약에 적힌 필드만 상품 단위로 내려준다(순서 43)")
    void purchaseHistory_returnsAggregatedItemWithContractFields() throws Exception {
        seedPaidPurchase("P-1001", "P-1001-BLK-M", 2, 39000L);

        String body = perform(authenticated(get(PURCHASE_HISTORY_PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].merchantId").value(MERCHANT_ID))
                .andExpect(jsonPath("$.items[0].merchantProductId").value("P-1001"))
                .andExpect(jsonPath("$.items[0].merchantVariantId").value("P-1001-BLK-M"))
                .andExpect(jsonPath("$.items[0].productName").value("오버핏 맨투맨"))
                .andExpect(jsonPath("$.items[0].optionLabel").value("블랙 / M"))
                .andExpect(jsonPath("$.items[0].purchaseCount").value(1))
                .andExpect(jsonPath("$.items[0].totalQuantity").value(2))
                .andExpect(jsonPath("$.items[0].lastUnitPriceAmount").value(39000))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> parsed = objectMapper.readValue(body, JSON_OBJECT);
        assertEquals(java.util.Set.of("periodStart", "items", "nextCursor"), parsed.keySet());
        assertEquals(java.util.Set.of("merchantId", "merchantName", "merchantProductId", "merchantVariantId",
                        "productName", "optionLabel", "purchaseCount", "totalQuantity", "lastUnitPriceAmount",
                        "currency", "lastPurchasedAt"),
                ((Map<?, ?>) ((List<?>) parsed.get("items")).getFirst()).keySet());
        // mcp-server CommerceProjection.timestamp()가 Instant.parse로 검증하는 표기여야 한다.
        java.time.Instant.parse((String) parsed.get("periodStart"));
    }

    @Test
    @DisplayName("pageSize 경계값 1·50은 통과하고 숫자가 아니면 400이다(순서 43)")
    void purchaseHistory_boundaryPageSizes() throws Exception {
        perform(authenticated(get(PURCHASE_HISTORY_PATH).param("pageSize", "1"))).andExpect(status().isOk());
        perform(authenticated(get(PURCHASE_HISTORY_PATH).param("pageSize", "50"))).andExpect(status().isOk());
        perform(authenticated(get(PURCHASE_HISTORY_PATH).param("pageSize", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        perform(authenticated(get(PURCHASE_HISTORY_PATH).param("pageSize", "twenty")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("해석할 수 없는 cursor는 400 INVALID_ARGUMENT다(순서 43)")
    void purchaseHistory_rejectsBrokenCursor() throws Exception {
        perform(authenticated(get(PURCHASE_HISTORY_PATH).param("cursor", "not-a-cursor!!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("구매내역 경로는 조회만 허용한다(순서 43)")
    void purchaseHistory_postIsMethodNotAllowed() throws Exception {
        perform(write(post(PURCHASE_HISTORY_PATH), UUID.randomUUID()).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("purchase:handoff를 동의하지 않은 연동은 403 ACCESS_DENIED다(순서 43)")
    void purchaseHistory_withoutHandoffScope_isForbidden() throws Exception {
        jdbcTemplate.update("UPDATE agent_connections SET granted_scopes = '[\"catalog:read\"]'::jsonb");

        perform(authenticated(get(PURCHASE_HISTORY_PATH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** 결제 완료 주문 한 건을 스냅샷·항목까지 직접 넣는다 — 구매 흐름 전체를 태우지 않고 계약만 본다. */
    private void seedPaidPurchase(String productId, String variantId, int quantity, long unitPrice) {
        long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM local_accounts WHERE login_id = ?", Long.class, "mcpuser1");
        long cartId = jdbcTemplate.queryForObject("""
                INSERT INTO carts (public_id, user_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id
                """, Long.class, UUID.randomUUID(), userId);
        long purchaseRequestId = jdbcTemplate.queryForObject("""
                INSERT INTO purchase_requests (public_id, user_id, cart_id, request_channel, status, policy_version)
                VALUES (?, ?, ?, 'MCP', 'COMPLETED', 'v1') RETURNING id
                """, Long.class, UUID.randomUUID(), userId, cartId);
        long merchantCheckoutId = jdbcTemplate.queryForObject("""
                INSERT INTO merchant_checkouts (purchase_request_id, merchant_id, merchant_checkout_id,
                                                checkout_digest, status)
                VALUES (?, 1, ?, repeat('0', 64), 'COMPLETED') RETURNING id
                """, Long.class, purchaseRequestId, UUID.randomUUID().toString());
        long snapshotId = jdbcTemplate.queryForObject("""
                INSERT INTO checkout_snapshots (public_id, merchant_checkout_id, checkout_digest,
                                                merchant_checkout_payload, currency)
                VALUES (?, ?, repeat('0', 64), '{}'::jsonb, 'KRW') RETURNING id
                """, Long.class, UUID.randomUUID(), merchantCheckoutId);
        jdbcTemplate.update("""
                INSERT INTO checkout_snapshot_items (checkout_snapshot_id, merchant_product_id, merchant_variant_id,
                                                     product_name, product_url, selected_options, quantity,
                                                     unit_price_amount, line_total_amount, currency)
                VALUES (?, ?, ?, '오버핏 맨투맨', 'http://ucp-merchant-mock:8080/p',
                        '[{"optionValueId":"v1","name":"색상","label":"블랙"},
                          {"optionValueId":"v2","name":"사이즈","label":"M"}]'::jsonb, ?, ?, ?, 'KRW')
                """, snapshotId, productId, variantId, quantity, unitPrice, unitPrice * quantity);
        long orderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (public_id, checkout_snapshot_id, user_id, merchant_order_id, status)
                VALUES (?, ?, ?, ?, 'CONFIRMED') RETURNING id
                """, Long.class, UUID.randomUUID(), snapshotId, userId, UUID.randomUUID().toString());
        jdbcTemplate.update("""
                INSERT INTO payment_transactions (checkout_snapshot_id, order_id, authorization_session_id,
                                                  pay_transaction_id, status, payment_amount, currency)
                VALUES (?, ?, ?, ?, 'PAID', ?, 'KRW')
                """, snapshotId, orderId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                unitPrice * quantity);
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder);
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + serviceToken)
                .header("X-Agent-Authorization", "Bearer " + agentToken)
                .header("X-Request-Id", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, UUID key) {
        return authenticated(builder)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private void stubCatalog() {
        when(productService.searchForInternal(any(), any(), any(), any())).thenReturn(new CatalogSearchResult(
                List.of(new CatalogSearchItem(
                        MERCHANT_ID, "Copenhagen Demo Store", PRODUCT_ID, "갤럭시 S25", 1200000L,
                        "KRW", "IN_STOCK")),
                List.of()));
        when(productService.detailForInternal(MERCHANT_ID, PRODUCT_ID)).thenReturn(new CatalogProductView(
                MERCHANT_ID, PRODUCT_ID, "갤럭시 S25", 1200000L, "KRW", "IN_STOCK",
                List.of(new CatalogProductView.OptionGroup("색상", List.of(
                        new CatalogProductView.OptionValue("sku-black", "블랙"),
                        new CatalogProductView.OptionValue("sku-white", "화이트"))))));
        when(productService.captureForCart(eq(MERCHANT_ID), eq(PRODUCT_ID), eq(List.of("sku-black"))))
                .thenReturn(snapshot("sku-black", "블랙"));
        when(productService.captureForCart(eq(MERCHANT_ID), eq(PRODUCT_ID), eq(List.of("sku-white"))))
                .thenReturn(snapshot("sku-white", "화이트"));
    }

    private CartProductSnapshot snapshot(String variantId, String label) {
        return new CartProductSnapshot(
                PRODUCT_ID, variantId, "갤럭시 S25",
                "http://ucp-merchant-mock:8080/products/" + PRODUCT_ID, null, 1200000L, "KRW",
                List.of(new CartProductSnapshot.SelectedOption(variantId, "색상", label)));
    }
}
