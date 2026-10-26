package com.copenhagen.core.address;

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
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.signup.SignupService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 순서 17(배송지 관리) — 실 PostgreSQL로 등록/목록/수정/기본지정/삭제 전체 흐름과 소유권 검사를 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class AddressApiIntegrationTest {

    private static final String PASSWORD = "Password1!";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update(
                "TRUNCATE users, merchants, oauth_clients, idempotency_records RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("등록→목록→수정→기본지정→삭제 전체 흐름과 자동 기본 승격을 확인한다")
    void addressLifecycle_fullFlow_success() throws Exception {
        String accessToken = signupAndLogin("addrflow1", "홍길동");

        String firstAddressId = register(accessToken, "홍길동", "010-1111-1111", "11111", "서울시 1", null, null);
        String secondAddressId =
                register(accessToken, "김철수", "010-2222-2222", "22222", "서울시 2", "101동", "문 앞");

        mockMvc.perform(get("/api/core/v1/addresses").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].addressId").value(firstAddressId))
                .andExpect(jsonPath("$.items[0].isDefault").value(true))
                .andExpect(jsonPath("$.items[1].isDefault").value(false));

        mockMvc.perform(put("/api/core/v1/addresses/" + secondAddressId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody("김철수", "010-3333-3333", "33333", "서울시 3", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("010-3333-3333"))
                .andExpect(jsonPath("$.isDefault").value(false));

        mockMvc.perform(patch("/api/core/v1/addresses/" + secondAddressId + "/default")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        Integer defaultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM addresses WHERE is_default = TRUE AND deleted_at IS NULL", Integer.class);
        assertEquals(1, defaultCount, "기본 배송지는 항상 정확히 1건이어야 한다");

        mockMvc.perform(delete("/api/core/v1/addresses/" + secondAddressId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/core/v1/addresses").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].addressId").value(firstAddressId))
                .andExpect(jsonPath("$.items[0].isDefault").value(true));
    }

    @Test
    @DisplayName("21번째 배송지를 등록하면 409 ADDRESS_LIMIT_EXCEEDED다")
    void register_over20Addresses_returns409() throws Exception {
        String accessToken = signupAndLogin("addrlimit1", "홍길동");
        for (int i = 0; i < 20; i++) {
            register(accessToken, "수령인" + i, "010-0000-" + String.format("%04d", i), "11111", "서울시 " + i, null, null);
        }

        mockMvc.perform(post("/api/core/v1/addresses")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody("초과", "010-9999-9999", "99999", "서울시 초과", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("다른 사용자의 배송지를 수정·삭제·기본지정하면 404 ADDRESS_NOT_FOUND다")
    void crossUserAccess_returns404() throws Exception {
        String ownerToken = signupAndLogin("addrowner1", "홍길동");
        String otherToken = signupAndLogin("addrother1", "김철수");
        String addressId = register(ownerToken, "홍길동", "010-1111-1111", "11111", "서울시", null, null);

        mockMvc.perform(put("/api/core/v1/addresses/" + addressId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody("해킹시도", "010-0000-0000", "00000", "서울시", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));

        mockMvc.perform(delete("/api/core/v1/addresses/" + addressId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));

        mockMvc.perform(patch("/api/core/v1/addresses/" + addressId + "/default")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
    }

    private String register(
            String accessToken, String recipientName, String recipientPhone, String postalCode,
            String addressLine1, String addressLine2, String deliveryRequest) throws Exception {
        String response = mockMvc.perform(post("/api/core/v1/addresses")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody(
                                recipientName, recipientPhone, postalCode, addressLine1,
                                addressLine2, deliveryRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.addressId");
    }

    private static String addressBody(
            String recipientName, String recipientPhone, String postalCode,
            String addressLine1, String addressLine2, String deliveryRequest) {
        return """
                {"recipientName":"%s","recipientPhone":"%s","postalCode":"%s","addressLine1":"%s",
                 "addressLine2":%s,"deliveryRequest":%s}
                """.formatted(
                recipientName, recipientPhone, postalCode, addressLine1,
                quoteOrNull(addressLine2), quoteOrNull(deliveryRequest));
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String signupAndLogin(String loginId, String name) throws Exception {
        signupService.signup(loginId, PASSWORD, loginId + "@example.com", name);
        String body = """
                {"loginId":"%s","password":"%s","deviceId":"device-1","deviceName":"iPhone 15"}
                """.formatted(loginId, PASSWORD);
        String response = mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
